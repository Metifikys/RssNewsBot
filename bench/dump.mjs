// Dump model outputs for manual judging: digests.md + summaries.md
import { readFileSync, writeFileSync } from 'node:fs';
const [,, resultsPath, inputPath, outDir] = process.argv;
const input = JSON.parse(readFileSync(inputPath, 'utf8'));
const rows = readFileSync(resultsPath, 'utf8').trim().split('\n').map(l => JSON.parse(l));
const byKey = new Map();
for (const r of rows) byKey.set(r.model + '|' + r.task, r);
const models = [...new Set([...byKey.keys()].map(k => k.split('|')[0]))].sort();

let d = '# DIGEST OUTPUTS (politics)\n\n## BASELINE (prod)\n' + input.digest_task.baseline.summary + '\n';
for (const m of models) {
  const r = byKey.get(m + '|digest-politics');
  d += `\n## ${m}` + (r?.ok ? ` (${r.ms}ms, ${r.provider})` : ' FAILED') + '\n';
  d += r?.ok ? r.content : ('ERROR: ' + (r?.error ?? 'missing'));
  d += '\n';
}
writeFileSync(outDir + '/digests.md', d);

let s = '# SUMMARY OUTPUTS\n';
for (const t of input.summarize_tasks) {
  s += `\n════════ ${t.id} [${t.category}] ${t.title}\nSOURCE (${t.description.length} ch): ${t.description.slice(0, 600)}\n`;
  if (t.prod_summary) s += `\n-- PROD BASELINE --\n${t.prod_summary}\n`;
  for (const m of models) {
    const r = byKey.get(m + '|' + t.id);
    s += `\n-- ${m}` + (r?.ok ? ` (${r.ms}ms)` : ' FAILED') + ' --\n';
    s += r?.ok ? r.content : ('ERROR: ' + (r?.error ?? 'missing'));
    s += '\n';
  }
}
writeFileSync(outDir + '/summaries.md', s);
console.log('dumped digests.md + summaries.md');
