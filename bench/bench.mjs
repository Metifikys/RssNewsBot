// OpenRouter free-model benchmark for RssNewsBot summarize/digest tasks.
// usage: node bench.mjs <bench-input.json> <results.jsonl> <path-to-config.yaml> [modelFilter]
import { readFileSync, existsSync, appendFileSync } from 'node:fs';

const [,, inputPath, outPath, configPath, modelFilter] = process.argv;

const cfg = readFileSync(configPath, 'utf8');
const keyMatch = cfg.match(/apiKey:\s*"(sk-or-[^"]+)"/);
if (!keyMatch) { console.error('no OpenRouter key found in config'); process.exit(1); }
const API_KEY = keyMatch[1];

const MODELS = [
  'inclusionai/ling-3.0-flash-fin:free',
  'dots-studio/dots-3-note-preview:free',
  'liquid/lfm-2.5-2.6b:free',
  'nvidia/nemotron-3.5-lightning:free',
  'thinkingmachines/inkling-small:free',
  'poolside/laguna-s-2.1:free',
  'thinkingmachines/inkling:free',
  'poolside/laguna-xs-2.1:free',
  'cohere/north-mini-code:free',
  'z-ai/glm-5.2:free',
  'nvidia/nemotron-3-ultra-550b-a55b:free',
  'minimax/minimax-m3:free',
  'nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free',
  'google/gemma-4-26b-a4b-it:free',
  'google/gemma-4-31b-it:free',
  'minimax/minimax-m2.7:free',
  'nvidia/nemotron-3-super-120b-a12b:free',   // current prod summarize model
].filter(m => !modelFilter || m.includes(modelFilter));

const input = JSON.parse(readFileSync(inputPath, 'utf8'));
const tasks = [
  ...input.summarize_tasks.map(t => ({ id: t.id, system: t.system, user: t.user })),
  { id: input.digest_task.id, system: input.digest_task.system, user: input.digest_task.user },
];

// resume: skip pairs already recorded with ok:true or permanent error
const done = new Set();
if (existsSync(outPath)) {
  for (const line of readFileSync(outPath, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    try { const r = JSON.parse(line); if (r.ok || r.permanent) done.add(r.model + '|' + r.task); } catch {}
  }
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const now = () => new Date().toISOString().slice(11, 19);

async function callModel(model, task) {
  const body = {
    model,
    messages: [
      { role: 'system', content: task.system },
      { role: 'user', content: task.user },
    ],
    usage: { include: true },
  };
  const t0 = Date.now();
  const controller = new AbortController();
  const to = setTimeout(() => controller.abort(), 240_000);
  try {
    const res = await fetch('https://openrouter.ai/api/v1/chat/completions', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${API_KEY}`,
        'Content-Type': 'application/json',
        'HTTP-Referer': 'https://github.com/metifikys/RssNewsBot',
        'X-Title': 'RssNewsBot-bench',
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const ms = Date.now() - t0;
    const text = await res.text();
    let j; try { j = JSON.parse(text); } catch { j = null; }
    if (!res.ok || !j || j.error) {
      const code = j?.error?.code ?? res.status;
      const msg = (j?.error?.message ?? text).slice(0, 300);
      return { ok: false, ms, http: res.status, code, error: msg,
               permanent: [400, 401, 402, 403, 404].includes(Number(code)) };
    }
    const choice = j.choices?.[0];
    let content = choice?.message?.content ?? '';
    const reasoning = choice?.message?.reasoning ?? null;
    // strip inline <think> blocks some models leak into content
    let think = '';
    content = content.replace(/<think>[\s\S]*?<\/think>/gi, (m) => { think += m; return ''; }).trim();
    return {
      ok: true, ms,
      provider: j.provider ?? null,
      finish: choice?.finish_reason ?? null,
      content,
      content_len: content.length,
      think_leaked: think.length,
      reasoning_len: reasoning ? reasoning.length : 0,
      usage: j.usage ? {
        prompt: j.usage.prompt_tokens, completion: j.usage.completion_tokens,
        reasoning: j.usage.completion_tokens_details?.reasoning_tokens ?? null,
      } : null,
    };
  } catch (e) {
    return { ok: false, ms: Date.now() - t0, error: String(e).slice(0, 200), aborted: e.name === 'AbortError' };
  } finally { clearTimeout(to); }
}

async function runModel(model) {
  for (const task of tasks) {
    const key = model + '|' + task.id;
    if (done.has(key)) { console.log(`${now()} skip ${key} (done)`); continue; }
    let rec = null;
    for (let attempt = 1; attempt <= 4; attempt++) {
      const r = await callModel(model, task);
      if (r.ok || r.permanent) { rec = { model, task: task.id, attempt, ...r }; break; }
      const backoff = [0, 4000, 10000, 25000][attempt] ?? 30000;
      console.log(`${now()} retry ${key} attempt=${attempt} err=${(r.error || r.code || '').toString().slice(0, 120)} wait=${backoff}ms`);
      rec = { model, task: task.id, attempt, ...r };
      await sleep(backoff + Math.random() * 2000);
    }
    appendFileSync(outPath, JSON.stringify(rec) + '\n');
    const label = rec.ok ? `${rec.ms}ms len=${rec.content_len} fin=${rec.finish}` : `FAIL ${rec.error?.slice(0, 100)}`;
    console.log(`${now()} ${key} -> ${label}`);
    await sleep(1500);
  }
}

// 3 models in flight, staggered start
const queue = [...MODELS];
const workers = Array.from({ length: Math.min(3, queue.length) }, async (_, w) => {
  await sleep(w * 3000);
  while (queue.length) {
    const m = queue.shift();
    console.log(`${now()} === start ${m} (worker ${w}) ===`);
    await runModel(m);
  }
});
await Promise.all(workers);
console.log('DONE');
