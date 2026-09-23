import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';

// Side-by-side view of `llm.shadow` runs (table `shadow_runs`): A (live) | B (shadow) on the same input.
//
// usage: node bench/shadow-dump.mjs [db=logs/news.db] [outDir=scratch/shadow] [--category tech] [--days 7]
//
// Writes <outDir>/shadow.md and a static <outDir>/shadow.html (inline CSS, no scripts, no network).
// Per run — EXTRACT: every shortlist URL marked both / only A / only B with each side's status and
// newsworthiness/digestFit, plus B's drops; RENDER: the prepared topics of both sides. Aggregates per
// (category, stage, B model): runs, B failures (call error or unparseable), mean Jaccard of the
// links, B latency p50 / p95, topics dropped by the URL whitelist, mean topic length, Cyrillic share.

const args = process.argv.slice(2);
const flag = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : def;
};
const positional = args.filter((a, i) => !a.startsWith('--') && !(i > 0 && args[i - 1].startsWith('--')));
const dbPath = positional[0] || 'logs/news.db';
const outDir = positional[1] || 'scratch/shadow';
const category = flag('category', null);
const days = Number(flag('days', 7));

const db = new DatabaseSync(dbPath, { readOnly: true });
const hasTable = db.prepare(`SELECT name FROM sqlite_master WHERE type='table' AND name='shadow_runs'`).get();
if (!hasTable) {
  console.log(`${dbPath}: no shadow_runs table — no llm.shadow run recorded yet`);
  process.exit(0);
}
const runs = db.prepare(`SELECT * FROM shadow_runs WHERE created_at >= datetime('now','localtime', ?) ${category ? 'AND category = ?' : ''} ORDER BY created_at`)
  .all(...[`-${days} days`, ...(category ? [category] : [])]);
db.close();

const lines = (s) => (s ?? '').split('\n').filter(Boolean);
const parse = (s) => { try { return s ? JSON.parse(s) : null; } catch { return null; } };
const jaccard = (a, b) => {
  const A = new Set(a), B = new Set(b);
  const union = new Set([...A, ...B]).size;
  return union === 0 ? 1 : [...A].filter((x) => B.has(x)).length / union;
};
const pct = (xs, p) => {
  if (!xs.length) return null;
  const s = [...xs].sort((x, y) => x - y);
  return s[Math.min(s.length - 1, Math.floor(p * (s.length - 1)))];
};
const cyrShare = (text) => {
  const letters = (text.match(/\p{L}/gu) ?? []).length;
  return letters ? (text.match(/\p{Script=Cyrillic}/gu) ?? []).length / letters : 0;
};
const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const f2 = (x) => (x == null ? '—' : x.toFixed(2));

// ── aggregates ──────────────────────────────────────────────────────────
const groups = new Map();
for (const r of runs) {
  const k = `${r.category} · ${r.stage} · B ${r.b_provider} ${r.b_model}`;
  if (!groups.has(k)) groups.set(k, []);
  groups.get(k).push(r);
}
const agg = [...groups].map(([k, rs]) => {
  const ok = rs.filter((r) => !r.b_error && r.a_output);
  const topics = (side) => rs.flatMap((r) => parse(side === 'a' ? r.a_output : r.b_output)?.topics ?? []);
  const dropped = (side) => rs.reduce((n, r) => n + Object.values(parse(side === 'a' ? r.a_output : r.b_output)?.dropped ?? {}).reduce((x, y) => x + y, 0), 0);
  const aT = topics('a'), bT = topics('b');
  const lat = rs.map((r) => r.b_duration_ms).filter((x) => x != null);
  return {
    group: k, runs: rs.length,
    bFail: rs.filter((r) => r.b_error).length,
    bFailShare: rs.length ? rs.filter((r) => r.b_error).length / rs.length : 0,
    jaccard: ok.length ? ok.reduce((a, r) => a + jaccard(lines(r.a_urls), lines(r.b_urls)), 0) / ok.length : null,
    bP50s: lat.length ? pct(lat, 0.5) / 1000 : null, bP95s: lat.length ? pct(lat, 0.95) / 1000 : null,
    whitelistA: dropped('a'), whitelistB: dropped('b'),
    lenA: aT.length ? aT.reduce((a, t) => a + t.length, 0) / aT.length : null,
    lenB: bT.length ? bT.reduce((a, t) => a + t.length, 0) / bT.length : null,
    cyrA: aT.length ? cyrShare(aT.join(' ')) : null,
    cyrB: bT.length ? cyrShare(bT.join(' ')) : null,
  };
});

// ── per-run comparison ──────────────────────────────────────────────────
function extractRows(r) {
  const a = parse(r.a_output), b = parse(r.b_output);
  const byUrl = (o) => new Map((o?.shortlist ?? []).map((i) => [i.url, i]));
  const A = byUrl(a), B = byUrl(b);
  const urls = [...new Set([...A.keys(), ...B.keys()])];
  return urls.map((u) => {
    const x = A.get(u), y = B.get(u);
    return {
      url: u, where: x && y ? 'both' : x ? 'only A' : 'only B',
      a: x ? `${x.status} ${x.newsworthiness}/${x.digestFit}` : '', b: y ? `${y.status} ${y.newsworthiness}/${y.digestFit}` : '',
    };
  });
}
const cell = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\n/g, '<br>');

let md = `# Shadow runs — ${dbPath}, last ${days} day(s)${category ? `, ${category}` : ''}\n\n`;
md += '| group | runs | B fail | Jaccard | B p50 s | B p95 s | whitelist drops A/B | avg topic len A/B | Cyrillic A/B |\n|---|---|---|---|---|---|---|---|---|\n';
for (const g of agg) {
  md += `| ${g.group} | ${g.runs} | ${g.bFail} (${(g.bFailShare * 100).toFixed(0)}%) | ${f2(g.jaccard)} | ${f2(g.bP50s)} | ${f2(g.bP95s)} | ${g.whitelistA}/${g.whitelistB} | ${g.lenA == null ? '—' : Math.round(g.lenA)}/${g.lenB == null ? '—' : Math.round(g.lenB)} | ${f2(g.cyrA)}/${f2(g.cyrB)} |\n`;
}
let html = '';
for (const r of runs) {
  const head = `${r.created_at} · ${r.category} · ${r.stage} · A ${r.a_provider} ${r.a_model} (${r.a_prompt_hash}) | B ${r.b_provider} ${r.b_model} (${r.b_prompt_hash})` +
    ` · B ${r.b_duration_ms ?? '—'} ms · Jaccard ${f2(jaccard(lines(r.a_urls), lines(r.b_urls)))}`;
  md += `\n## ${head}\n\n`;
  html += `<section><h2>${esc(head)}</h2>`;
  if (r.b_error) {
    md += `**B failed:** ${r.b_error}\n\n`;
    html += `<p class="err">B failed: ${esc(r.b_error)}</p>`;
  }
  if (r.stage === 'EXTRACT') {
    const rows = extractRows(r);
    md += '| | URL | A | B |\n|---|---|---|---|\n' + rows.map((x) => `| ${x.where} | ${cell(x.url)} | ${x.a} | ${x.b} |`).join('\n') + '\n';
    html += '<table><tr><th></th><th>URL</th><th>A</th><th>B</th></tr>' +
      rows.map((x) => `<tr class="${x.where.replace(' ', '-')}"><td>${x.where}</td><td>${esc(x.url)}</td><td>${esc(x.a)}</td><td>${esc(x.b)}</td></tr>`).join('') + '</table>';
    const bDrops = parse(r.b_output)?.drops ?? [];
    if (bDrops.length) {
      md += '\nB drops: ' + bDrops.map((d) => `${d.reason} ${d.url}`).join('; ') + '\n';
      html += `<p class="drops">B drops: ${esc(bDrops.map((d) => `${d.reason} ${d.url}`).join('; '))}</p>`;
    }
    if (r.b_error && r.b_output) html += `<pre>${esc(r.b_output.slice(0, 4000))}</pre>`;
  } else {
    const aT = parse(r.a_output)?.topics ?? [], bT = parse(r.b_output)?.topics ?? [];
    const n = Math.max(aT.length, bT.length);
    md += '| A | B |\n|---|---|\n';
    html += '<table class="topics"><tr><th>A</th><th>B</th></tr>';
    for (let i = 0; i < n; i++) {
      md += `| ${cell(aT[i])} | ${cell(bT[i])} |\n`;
      html += `<tr><td><pre>${esc(aT[i] ?? '')}</pre></td><td><pre>${esc(bT[i] ?? '')}</pre></td></tr>`;
    }
    html += '</table>';
  }
  html += '</section>';
}
if (!runs.length) md += '\nNo shadow runs in the window.\n';

const aggHtml = '<table><tr><th>group</th><th>runs</th><th>B fail</th><th>Jaccard</th><th>B p50 s</th><th>B p95 s</th><th>whitelist A/B</th><th>topic len A/B</th><th>Cyrillic A/B</th></tr>' +
  agg.map((g) => `<tr><td>${esc(g.group)}</td><td>${g.runs}</td><td>${g.bFail}</td><td>${f2(g.jaccard)}</td><td>${f2(g.bP50s)}</td><td>${f2(g.bP95s)}</td><td>${g.whitelistA}/${g.whitelistB}</td><td>${g.lenA == null ? '—' : Math.round(g.lenA)}/${g.lenB == null ? '—' : Math.round(g.lenB)}</td><td>${f2(g.cyrA)}/${f2(g.cyrB)}</td></tr>`).join('') + '</table>';
const page = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Shadow runs</title>
<style>
:root{--bg:#fff;--fg:#1d1d1f;--muted:#6e6e73;--line:#d2d2d7;--both:#e8f5e9;--a:#fff3e0;--b:#e3f2fd;--err:#b3261e}
@media (prefers-color-scheme:dark){:root{--bg:#161618;--fg:#f2f2f2;--muted:#a1a1a6;--line:#3a3a3c;--both:#1e3323;--a:#3a2a14;--b:#15283a;--err:#ff8a80}}
body{background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif;margin:0;padding:16px;max-width:1200px}
table{border-collapse:collapse;width:100%;margin:8px 0}td,th{border:1px solid var(--line);padding:4px 6px;vertical-align:top;text-align:left;word-break:break-word}
tr.both{background:var(--both)}tr.only-A{background:var(--a)}tr.only-B{background:var(--b)}
pre{white-space:pre-wrap;margin:0;font:13px/1.4 ui-monospace,monospace}h2{font-size:14px;color:var(--muted);margin-top:24px}.err{color:var(--err)}
.topics td{width:50%}
</style></head><body><h1>Shadow runs</h1><p>${esc(dbPath)} · last ${days} day(s)${category ? ' · ' + esc(category) : ''} · ${runs.length} run(s)</p>${aggHtml}${html}</body></html>`;

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'shadow.md'), md);
fs.writeFileSync(path.join(outDir, 'shadow.html'), page);
console.table(agg.map((g) => ({ ...g, jaccard: f2(g.jaccard), bFailShare: f2(g.bFailShare), cyrA: f2(g.cyrA), cyrB: f2(g.cyrB) })));
console.log(`wrote ${path.join(outDir, 'shadow.md')} and shadow.html (${runs.length} run(s))`);
