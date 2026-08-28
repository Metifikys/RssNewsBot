// Auto-metrics over bench results.jsonl
import { readFileSync, writeFileSync } from 'node:fs';

const [,, resultsPath, inputPath, outJson] = process.argv;
const input = JSON.parse(readFileSync(inputPath, 'utf8'));
const inputLinks = new Set(input.digest_task.input_links);
// links that appear in the "previous digests" context block = repeat violations if used
const prevLinks = new Set([...input.digest_task.user.matchAll(/\]\((https?:\/\/[^)\s]+)\)/g)].map(m => m[1])
  .filter(u => !inputLinks.has(u)));

const rows = readFileSync(resultsPath, 'utf8').trim().split('\n').map(l => JSON.parse(l));
// keep the LAST record per (model, task)
const byKey = new Map();
for (const r of rows) byKey.set(r.model + '|' + r.task, r);

const cyr = (s) => (s.match(/[Ѐ-ӿ]/g) ?? []).length;
const letters = (s) => (s.match(/\p{L}/gu) ?? []).length;
const ruLetters = (s) => (s.match(/[ыэъёЫЭЪЁ]/g) ?? []).length;
const uaLetters = (s) => (s.match(/[іїєґІЇЄҐ]/g) ?? []).length;
const sentences = (s) => (s.split(/[.!?]+\s/).filter(x => x.trim().length > 10)).length;

const out = { summarize: [], digest: [] };

for (const [key, r] of byKey) {
  const [model, task] = key.split('|');
  if (!r.ok) {
    (task === 'digest-politics' ? out.digest : out.summarize).push({
      model, task, ok: false, error: (r.error || '').slice(0, 120), ms: r.ms ?? null });
    continue;
  }
  const c = r.content ?? '';
  const base = {
    model, task, ok: true, ms: r.ms, provider: r.provider, finish: r.finish,
    chars: c.length, tok_out: r.usage?.completion ?? null, tok_reason: r.usage?.reasoning ?? null,
    think_leak: r.think_leaked > 0, reasoning_len: r.reasoning_len,
  };
  if (task === 'digest-politics') {
    const bullets = c.split('\n').filter(l => l.trim().startsWith('•')).length;
    const links = [...c.matchAll(/\]\((https?:\/\/[^)\s]+)\)/g)].map(m => m[1]);
    const validLinks = links.filter(u => inputLinks.has(u)).length;
    const repeatLinks = links.filter(u => prevLinks.has(u)).length;
    const inventedLinks = links.filter(u => !inputLinks.has(u) && !prevLinks.has(u)).length;
    const lettersN = letters(c) || 1;
    out.digest.push({
      ...base, bullets, links: links.length, valid: validLinks, repeats: repeatLinks, invented: inventedLinks,
      'cyr%': Math.round(100 * cyr(c) / lettersN), ru: ruLetters(c), ua: uaLetters(c),
    });
  } else {
    const t = input.summarize_tasks.find(x => x.id === task);
    const srcCyr = cyr(t.description) / Math.max(1, letters(t.description));
    const outCyr = cyr(c) / Math.max(1, letters(c));
    const langOk = srcCyr > 0.3 ? outCyr > 0.5 : outCyr < 0.3;   // reply in source language
    const fmtBad = /^\s*(#|\*\*|Here is|Ось |Summary|Резюме|Короткий зміст|TL;DR)/i.test(c) ||
      /^\s*[-•*]\s/m.test(c);
    out.summarize.push({
      ...base, sent: sentences(c), langOk, ru: ruLetters(c), fmtBad,
    });
  }
}

// order: prod model last for visibility
const models = [...new Set([...byKey.keys()].map(k => k.split('|')[0]))];

console.log('=== DIGEST (politics render) ===');
console.table(out.digest.sort((a, b) => a.model.localeCompare(b.model)).map(r => r.ok ? {
  model: r.model.replace(':free', '').slice(0, 40), ms: r.ms, bullets: r.bullets,
  valid: r.valid, repeats: r.repeats, invented: r.invented,
  'cyr%': r['cyr%'], ru: r.ru, chars: r.chars, tokR: r.tok_reason, fin: r.finish,
} : { model: r.model.replace(':free', '').slice(0, 40), ms: r.ms, bullets: 'FAIL: ' + r.error }));

console.log('=== SUMMARIZE (per-article), aggregated per model ===');
const agg = [];
for (const m of models) {
  const rs = out.summarize.filter(r => r.model === m);
  const ok = rs.filter(r => r.ok);
  agg.push({
    model: m.replace(':free', '').slice(0, 40),
    ok: `${ok.length}/${rs.length}`,
    avg_ms: ok.length ? Math.round(ok.reduce((s, r) => s + r.ms, 0) / ok.length) : null,
    p_max_ms: ok.length ? Math.max(...ok.map(r => r.ms)) : null,
    avg_chars: ok.length ? Math.round(ok.reduce((s, r) => s + r.chars, 0) / ok.length) : null,
    lang_bad: ok.filter(r => !r.langOk).length,
    ru_leak: ok.filter(r => r.ru > 0).length,
    fmt_bad: ok.filter(r => r.fmtBad).length,
    think: ok.filter(r => r.think_leak).length,
    sent_over5: ok.filter(r => r.sent > 6).length,
  });
}
console.table(agg.sort((a, b) => (a.avg_ms ?? 9e9) - (b.avg_ms ?? 9e9)));

writeFileSync(outJson, JSON.stringify(out, null, 2));
console.log('written', outJson);
