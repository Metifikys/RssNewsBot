// Score Jev answers against Step 1 verdicts.
// usage: node bench/jev-analyze.mjs <results.jsonl> <input.json> [report.md]
import { readFileSync, writeFileSync } from 'node:fs';
const [,, resPath, inputPath, reportPath] = process.argv;
const input = JSON.parse(readFileSync(inputPath, 'utf8'));
const byId = Object.fromEntries(input.items.map(i => [i.id, i]));
const rows = [];
for (const line of readFileSync(resPath, 'utf8').split('\n')) {
  if (!line.trim()) continue; const r = JSON.parse(line); if (!r.ok) continue;
  const i = byId[r.id]; if (!i) continue; rows.push({ ...r, item: i });
}
const out = [];
const log = (s = '') => { out.push(s); console.log(s); };
const pct = (x) => (100 * x).toFixed(1) + '%';

// AUC via rank-sum (probability a random covered item scores above a random rejected one)
function auc(pos, neg) {
  let wins = 0; for (const p of pos) for (const n of neg) wins += p > n ? 1 : p === n ? 0.5 : 0;
  return pos.length && neg.length ? wins / (pos.length * neg.length) : NaN;
}
// gate: drop when score < t. Report share of rejected dropped (junk removed) and share of covered dropped (false drops)
function gateTable(name, score, cats, thresholds) {
  log(`\n### ${name} — drop when ${name} < t`);
  log('| category | t | junk dropped (rejected) | lost news (covered) | precision of drop |');
  log('|---|---|---|---|---|');
  for (const c of cats) {
    const rs = rows.filter(r => r.item.category === c);
    const rej = rs.filter(r => r.item.label === 'rejected').map(score);
    const cov = rs.filter(r => r.item.label === 'covered').map(score);
    for (const t of thresholds) {
      const dr = rej.filter(s => s < t).length, dc = cov.filter(s => s < t).length;
      log(`| ${c} | ${t} | ${pct(dr / rej.length)} (${dr}/${rej.length}) | ${pct(dc / cov.length)} (${dc}/${cov.length}) | ${dr + dc ? pct(dr / (dr + dc)) : '-'} |`);
    }
  }
}

const cats = [...new Set(rows.map(r => r.item.category))];
log(`# Jev pre-filter benchmark — ${rows.length} answered items`);
const cost = rows.reduce((a, r) => a + (r.usage?.cost ?? 0), 0);
const ms = rows.map(r => r.ms).sort((a, b) => a - b);
log(`total cost $${cost.toFixed(4)}, latency p50 ${ms[Math.floor(ms.length / 2)]}ms p90 ${ms[Math.floor(ms.length * 0.9)]}ms, avg input tokens ${Math.round(rows.reduce((a, r) => a + (r.usage?.input_tokens ?? 0), 0) / rows.length)}`);

log('\n## Separation (AUC: covered above rejected; 0.5 = coin flip)');
log('| category | n rej / cov | AUC keep | AUC fit | AUC step1 digestFit (reference) |');
log('|---|---|---|---|---|');
for (const c of [...cats, 'ALL']) {
  const rs = c === 'ALL' ? rows : rows.filter(r => r.item.category === c);
  const rej = rs.filter(r => r.item.label === 'rejected'), cov = rs.filter(r => r.item.label === 'covered');
  log(`| ${c} | ${rej.length} / ${cov.length} | ${auc(cov.map(r => r.keep), rej.map(r => r.keep)).toFixed(3)} | ${auc(cov.map(r => r.fit), rej.map(r => r.fit)).toFixed(3)} | ${auc(cov.map(r => r.item.step1.digestFit), rej.map(r => r.item.step1.digestFit)).toFixed(3)} |`);
}

gateTable('keep', r => r.keep, cats, [0.05, 0.1, 0.2, 0.3, 0.5]);
gateTable('fit', r => r.fit, cats, [0.3, 0.5, 0.75, 1.0]);

// combined gate: both keep < a AND fit < b
log('\n### combined — drop when keep < 0.2 AND fit < 0.75');
log('| category | junk dropped | lost news | precision |');
log('|---|---|---|---|');
for (const c of cats) {
  const rs = rows.filter(r => r.item.category === c);
  const d = (l) => rs.filter(r => r.item.label === l && r.keep < 0.2 && r.fit < 0.75).length;
  const n = (l) => rs.filter(r => r.item.label === l).length;
  log(`| ${c} | ${pct(d('rejected') / n('rejected'))} | ${pct(d('covered') / n('covered'))} | ${pct(d('rejected') / (d('rejected') + d('covered') || 1))} |`);
}

log('\n## Kind distribution (Jev) by Step 1 verdict');
for (const c of cats) {
  log(`\n### ${c}`);
  log('| kind | rejected | covered |');
  log('|---|---|---|');
  const rs = rows.filter(r => r.item.category === c);
  const kinds = [...new Set(rs.map(r => r.kind))].sort((a, b) => rs.filter(r => r.kind === b).length - rs.filter(r => r.kind === a).length);
  for (const k of kinds) log(`| ${k} | ${rs.filter(r => r.kind === k && r.item.label === 'rejected').length} | ${rs.filter(r => r.kind === k && r.item.label === 'covered').length} |`);
}

log('\n## Disagreements worth eyeballing');
const show = (r) => `- [${r.item.category}] keep=${r.keep} fit=${r.fit} kind=${r.kind} | step1 ${r.item.step1.eventType} nw=${r.item.step1.newsworthiness} fit=${r.item.step1.digestFit} | ${r.item.title.slice(0, 90)} <${r.item.url}>`;
log('\n### Covered by Step 1 but Jev says junk (keep < 0.1) — false drops');
rows.filter(r => r.item.label === 'covered' && r.keep < 0.1).sort((a, b) => b.item.step1.digestFit - a.item.step1.digestFit).slice(0, 40).forEach(r => log(show(r)));
log('\n### Rejected by Step 1 but Jev is confident it is news (keep > 0.9)');
rows.filter(r => r.item.label === 'rejected' && r.keep > 0.9).slice(0, 25).forEach(r => log(show(r)));
if (reportPath) writeFileSync(reportPath, out.join('\n') + '\n');
