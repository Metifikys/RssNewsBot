// Materialize exactly the Jev requests (state + questions) so the Python Laya runner sends
// byte-identical questions. usage: node bench/laya-dump.mjs <input.json> <requests.jsonl>
import { readFileSync, writeFileSync } from 'node:fs';
import { questionsFor, stateFor } from './jev-questions.mjs';
const [,, inputPath, outPath] = process.argv;
const { items } = JSON.parse(readFileSync(inputPath, 'utf8'));
writeFileSync(outPath, items.map(i => JSON.stringify({ id: i.id, state: stateFor(i), questions: questionsFor(i.category) })).join('\n') + '\n');
console.log(`wrote ${items.length} requests`);
