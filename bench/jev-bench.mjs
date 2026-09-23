// Run every benchmark item through TypeSafe Jev via OpenRouter's decisions endpoint.
// usage: node bench/jev-bench.mjs <input.json> <results.jsonl> <config.yaml> [limit] [concurrency]
// Resume-safe: items already recorded with ok:true are skipped. ~$0.00002 per item.
import { readFileSync, existsSync, appendFileSync } from 'node:fs';

const [,, inputPath, outPath, configPath, limitArg = '0', concArg = '8'] = process.argv;
const cfg = readFileSync(configPath, 'utf8');
const keyMatch = cfg.match(/apiKey:\s*"(sk-or-[^"]+)"/);
if (!keyMatch) { console.error('no OpenRouter key found in config'); process.exit(1); }
const API_KEY = keyMatch[1];
const MODEL = 'typesafe/jev-1.13';
const ENDPOINT = 'https://openrouter.ai/api/alpha/decisions';

import { questionsFor, stateFor } from './jev-questions.mjs';

// ── Runner ──────────────────────────────────────────────────────────────────
const input = JSON.parse(readFileSync(inputPath, 'utf8'));
let items = input.items;
const limit = Number(limitArg);
if (limit > 0) {
  // spread the smoke sample over categories/labels
  const per = Math.max(1, Math.floor(limit / 6)); const out = [];
  for (const c of ['gaming', 'tech', 'science']) for (const l of ['rejected', 'covered']) out.push(...items.filter(i => i.category === c && i.label === l).slice(0, per));
  items = out;
}
const done = new Set();
if (existsSync(outPath)) for (const line of readFileSync(outPath, 'utf8').split('\n')) {
  if (!line.trim()) continue; try { const r = JSON.parse(line); if (r.ok) done.add(r.id); } catch {}
}
items = items.filter(i => !done.has(i.id));
console.log(`to run: ${items.length} (skipped ${done.size} done)`);

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
async function callOne(item) {
  const body = { model: MODEL, state: stateFor(item), questions: questionsFor(item.category) };
  for (let attempt = 0; attempt < 6; attempt++) {
    const t0 = Date.now();
    try {
      const res = await fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${API_KEY}`, 'Content-Type': 'application/json',
                   'HTTP-Referer': 'https://github.com/metifikys/RssNewsBot', 'X-Title': 'RssNewsBot-bench' },
        body: JSON.stringify(body), signal: AbortSignal.timeout(60_000),
      });
      const ms = Date.now() - t0;
      const text = await res.text();
      let j; try { j = JSON.parse(text); } catch { j = null; }
      if (res.status === 429 || res.status === 529 || res.status >= 500) { await sleep(1500 * 2 ** attempt); continue; }
      if (!res.ok || !j || j.error || !j.answers) {
        return { id: item.id, ok: false, ms, http: res.status, error: (j?.error?.message ?? text).slice(0, 300) };
      }
      const a = j.answers;
      return { id: item.id, ok: true, ms, model: j.model, usage: j.usage,
        keep: a.keep?.noul,
        kind: a.kind?.choice, kindConfidence: a.kind?.confidence, kindProbs: a.kind?.probabilities,
        fit: a.fit?.score, fitConfidence: a.fit?.confidence, fitProbs: a.fit?.probabilities };
    } catch (e) {
      if (attempt === 5) return { id: item.id, ok: false, ms: Date.now() - t0, error: String(e).slice(0, 300) };
      await sleep(1500 * 2 ** attempt);
    }
  }
  return { id: item.id, ok: false, error: 'exhausted retries' };
}

let idx = 0, okN = 0, errN = 0, cost = 0;
const conc = Number(concArg);
async function worker() {
  while (idx < items.length) {
    const item = items[idx++];
    const r = await callOne(item);
    appendFileSync(outPath, JSON.stringify(r) + '\n');
    if (r.ok) { okN++; cost += r.usage?.cost ?? 0; } else { errN++; console.log('ERR', item.id, r.http, r.error); }
    if ((okN + errN) % 100 === 0) console.log(`${okN + errN}/${items.length} ok=${okN} err=${errN} cost=$${cost.toFixed(4)}`);
  }
}
await Promise.all(Array.from({ length: conc }, worker));
console.log(`done ok=${okN} err=${errN} cost=$${cost.toFixed(4)}`);
