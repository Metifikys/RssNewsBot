import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';

// The `factCheck` verdicts (table `fact_checks`) for calibrating the judge by hand.
//
// usage: node bench/factcheck-dump.mjs [db=logs/news.db] [out=scratch/factcheck.md]
//                                      [--days 14] [--category tech] [--verdict unsupported]
//
// Per category: checks, unsupported / minor share; then every non-"supported" verdict (or the
// --verdict one) with the post, the flagged claims and the judge's notes. Mark each flagged claim
// true/false alarm in the file to estimate the judge's precision before trusting the /status alert.

const args = process.argv.slice(2);
const flag = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : def;
};
const positional = args.filter((a, i) => !a.startsWith('--') && !(i > 0 && args[i - 1].startsWith('--')));
const dbPath = positional[0] || 'logs/news.db';
const out = positional[1] || 'scratch/factcheck.md';
const days = Number(flag('days', 14));
const category = flag('category', null);
const verdict = flag('verdict', null);

const db = new DatabaseSync(dbPath, { readOnly: true });
if (!db.prepare(`SELECT name FROM sqlite_master WHERE type='table' AND name='fact_checks'`).get()) {
  console.log(`${dbPath}: no fact_checks table — factCheck has not run yet`);
  process.exit(0);
}
const rows = db.prepare(`SELECT * FROM fact_checks WHERE checked_at >= datetime('now','localtime', ?) ${category ? 'AND category = ?' : ''} ORDER BY checked_at DESC`)
  .all(...[`-${days} days`, ...(category ? [category] : [])]);
db.close();

const byCat = new Map();
for (const r of rows) {
  const c = byCat.get(r.category) ?? { category: r.category, checks: 0, unsupported: 0, minor: 0, avgSourceChars: 0 };
  c.checks++;
  if (r.verdict === 'unsupported') c.unsupported++;
  if (r.verdict === 'minor') c.minor++;
  c.avgSourceChars += r.source_chars;
  byCat.set(r.category, c);
}
const summary = [...byCat.values()].map((c) => ({
  ...c,
  unsupportedPct: +((c.unsupported / c.checks) * 100).toFixed(1),
  minorPct: +((c.minor / c.checks) * 100).toFixed(1),
  avgSourceChars: Math.round(c.avgSourceChars / c.checks),
}));

let md = `# Fact checks — ${dbPath}, last ${days} day(s)${category ? `, ${category}` : ''}\n\n`;
md += '| category | checks | unsupported | minor | avg source chars |\n|---|---|---|---|---|\n';
for (const s of summary) md += `| ${s.category} | ${s.checks} | ${s.unsupported} (${s.unsupportedPct}%) | ${s.minor} (${s.minorPct}%) | ${s.avgSourceChars} |\n`;
const shown = rows.filter((r) => (verdict ? r.verdict === verdict : r.verdict !== 'supported'));
for (const r of shown) {
  let claims = [];
  try { claims = JSON.parse(r.claims_json); } catch { /* keep empty */ }
  md += `\n## ${r.checked_at} · ${r.category} · ${r.verdict} · ${r.event_key ?? '—'} · msg ${r.chat_id}/${r.message_id} · ${r.judge_provider} ${r.judge_model}\n\n`;
  md += r.post_text.split('\n').map((l) => `> ${l}`).join('\n') + '\n\n';
  for (const c of claims) md += `- [ ] **${c.status}**: ${c.claim}${c.note ? ` — _${c.note}_` : ''}\n`;
}
if (!shown.length) md += '\nNothing to review in the window.\n';

fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, md);
console.table(summary);
console.log(`wrote ${out} (${shown.length} verdict(s) to review)`);
