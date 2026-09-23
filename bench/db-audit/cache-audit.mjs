import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';

// Prompt-caching audit (idea 32, step 1a): would Anthropic `cache_control` on the static system
// prompt pay off? Read-only over a news.db snapshot; no behaviour change anywhere.
//
// usage: node bench/db-audit/cache-audit.mjs [db=logs/news.db] [--days 30] [--prompts prompts]
//                                            [--price-in 2.0] [--now "YYYY-MM-DD HH:MM"]
//
// Per (category, use_case, provider, model) over the window: calls/day, the gap between
// neighbouring calls (median, p90, share <= 5 / 10 / 60 min — the ceiling of a cache hit rate for
// that TTL: a call can only hit when the previous one with the same prefix is younger than the
// TTL), average prompt tokens, the static prefix (the category's `extract.system` /
// `render.system`, chars/4 — too low for Cyrillic), and what that prefix would save per month
// on the PAID Anthropic API at `--price-in` $/M input (default: Sonnet 5, $2), for TTL 5 min
// (write 1.25x) and 1 h (write 2x), reads at 0.1x:
//   saving = hits * P * price * 0.9 - writes * P * price * (writeMult - 1)
// The go/no-go gate of the plan: (a) some category runs EXTRACT/RENDER on the paid Anthropic API
// (provider `anthropic`, not the CLIs) for >= 2 weeks, and (b) the forecast net saving is
// >= $5/month and >= 20% of that provider's bill. The CLI rows are shown as "if it were the API"
// only: a subscription pays nothing per token.

const args = process.argv.slice(2);
const flag = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : def;
};
const positional = args.filter((a, i) => !a.startsWith('--') && !(i > 0 && args[i - 1].startsWith('--')));
const dbPath = positional[0] || 'logs/news.db';
const days = Number(flag('days', 30));
const promptsDir = flag('prompts', 'prompts');
const priceIn = Number(flag('price-in', 2.0));
const now = flag('now', null);

const db = new DatabaseSync(dbPath, { readOnly: true });
const q = (sql, ...p) => db.prepare(sql).all(...p);
const nowExpr = now ? `'${now}'` : `datetime('now','localtime')`;
const since = q(`SELECT datetime(${nowExpr}, '-${days} days') s`)[0].s;

// ── static prefix sizes from the prompt files ────────────────────────────
// Minimal YAML block-scalar reader: `extract:` / `render:` then `  system: |` and its indented lines.
function blockScalar(text, section, key) {
  const lines = text.split(/\r?\n/);
  let inSection = false;
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i];
    if (/^\S/.test(l)) inSection = l.startsWith(`${section}:`);
    if (!inSection) continue;
    const m = l.match(new RegExp(`^(\\s+)${key}:\\s*[|>][-+]?\\s*$`));
    if (!m) continue;
    const out = [];
    let indent = null;
    for (let j = i + 1; j < lines.length; j++) {
      const s = lines[j];
      if (s.trim() === '') { out.push(''); continue; }
      const ind = s.match(/^\s*/)[0].length;
      if (indent === null) indent = ind;
      if (ind < indent || ind <= m[1].length) break;
      out.push(s.slice(indent));
    }
    return out.join('\n').trim();
  }
  return null;
}
const promptFileFor = (category) => {
  const names = [`${category}-dedup.yaml`, `${category.replace(/^gaming$/, 'games')}-dedup.yaml`];
  for (const n of names) {
    const p = path.join(promptsDir, n);
    if (fs.existsSync(p)) return p;
  }
  return null;
};
const staticChars = {};
for (const { category } of q(`SELECT DISTINCT category FROM llm_calls WHERE category IS NOT NULL`)) {
  const file = promptFileFor(category);
  if (!file) continue;
  const text = fs.readFileSync(file, 'utf8');
  staticChars[`${category}|EXTRACT`] = (blockScalar(text, 'extract', 'system') ?? '').length;
  staticChars[`${category}|RENDER`] = (blockScalar(text, 'render', 'system') ?? '').length;
  staticChars[`${category}|file`] = file;
}

// ── per-group gaps ───────────────────────────────────────────────────────
const rows = q(`SELECT category, use_case, provider, model, ts, prompt_tokens, est_cost_usd
  FROM llm_calls WHERE ts >= ? AND use_case IN ('EXTRACT','RENDER') ORDER BY ts`, since);
const groups = new Map();
for (const r of rows) {
  const k = [r.category ?? '-', r.use_case, r.provider, r.model].join('|');
  if (!groups.has(k)) groups.set(k, []);
  groups.get(k).push(r);
}
const toMs = (ts) => Date.parse(ts.replace(' ', 'T'));
const pct = (xs, p) => {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(p * (s.length - 1)))];
};
const monthly = 30 / days;
const table = [];
for (const [k, list] of groups) {
  const [category, useCase, provider, model] = k.split('|');
  const gaps = [];
  for (let i = 1; i < list.length; i++) gaps.push((toMs(list[i].ts) - toMs(list[i - 1].ts)) / 60000);
  const share = (m) => (gaps.length ? gaps.filter((g) => g <= m).length / gaps.length : 0);
  const spanDays = list.length > 1 ? (toMs(list.at(-1).ts) - toMs(list[0].ts)) / 86400000 : 0;
  const chars = staticChars[`${category}|${useCase}`] ?? 0;
  const prefix = Math.floor(chars / 4);
  const avgPrompt = list.reduce((a, r) => a + r.prompt_tokens, 0) / list.length;
  const calls = list.length;
  const save = (ttlMin, writeMult) => {
    const hits = calls * share(ttlMin);
    const writes = calls - hits;
    const usd = (hits * prefix * priceIn * 0.9 - writes * prefix * priceIn * (writeMult - 1)) / 1e6;
    return usd * monthly;
  };
  table.push({
    category, useCase, provider, model,
    calls, perDay: +(calls / days).toFixed(1), spanDays: +spanDays.toFixed(1),
    gapP50m: +(pct(gaps, 0.5) ?? 0).toFixed(1), gapP90m: +(pct(gaps, 0.9) ?? 0).toFixed(1),
    le5: +share(5).toFixed(2), le10: +share(10).toFixed(2), le60: +share(60).toFixed(2),
    avgPromptTok: Math.round(avgPrompt), prefixTok: prefix,
    prefixShare: avgPrompt ? +(prefix / avgPrompt).toFixed(2) : 0,
    billUsd: +list.reduce((a, r) => a + r.est_cost_usd, 0).toFixed(2),
    save5mUsdMo: +save(5, 1.25).toFixed(2), save1hUsdMo: +save(60, 2).toFixed(2),
  });
}
table.sort((a, b) => b.calls - a.calls);

console.log(`=== prompt-cache audit: ${dbPath}, ${days} days since ${since}, price-in $${priceIn}/M ===`);
console.log('static prefixes (chars):', Object.fromEntries(Object.entries(staticChars)));
console.table(table);

// ── go / no-go ───────────────────────────────────────────────────────────
const paid = table.filter((r) => r.provider === 'anthropic');
const paidLongEnough = paid.filter((r) => r.spanDays >= 14);
const anthropicBill = q(`SELECT COALESCE(SUM(est_cost_usd),0) c FROM llm_calls WHERE ts >= ? AND provider = 'anthropic'`, since)[0].c * monthly;
const paidSaving = paidLongEnough.reduce((a, r) => a + Math.max(r.save5mUsdMo, r.save1hUsdMo), 0);
const hypothetical = table.reduce((a, r) => a + Math.max(0, r.save5mUsdMo, r.save1hUsdMo), 0);
const byProvider = q(`SELECT provider, use_case, COUNT(*) n, ROUND(SUM(est_cost_usd),2) cost FROM llm_calls WHERE ts >= ? GROUP BY 1,2 ORDER BY n DESC`, since);
console.log('=== calls and recorded cost per provider / use case ===');
console.table(byProvider);
const a = paidLongEnough.length > 0;
const b = paidSaving >= 5 && anthropicBill > 0 && paidSaving / anthropicBill >= 0.2;
console.log('=== go / no-go ===');
console.log(`(a) EXTRACT/RENDER on the paid Anthropic API for >= 14 days: ${a ? 'yes' : 'no'} (${paid.length} group(s), ${paidLongEnough.length} long enough)`);
console.log(`(b) forecast net saving >= $5/mo and >= 20% of the Anthropic bill: ${b ? 'yes' : 'no'} (saving $${paidSaving.toFixed(2)}/mo, bill $${anthropicBill.toFixed(2)}/mo)`);
console.log(`for scale: every EXTRACT/RENDER group above priced as if on the API at $${priceIn}/M would save at most $${hypothetical.toFixed(2)}/mo`);
console.log(`decision: ${a && b ? 'GO — implement Anthropic cache_control (plan 1b)' : 'NO-GO — no cache_control code'}`);
db.close();
