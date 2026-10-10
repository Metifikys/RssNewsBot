// CLI-model benchmark (subscription `codex exec`) on the same summarize/digest tasks as bench.mjs.
// usage: node bench/bench-cli.mjs <bench-input.json> <results.jsonl> <model,model,...> [effort]
// Transport mirrors src/main/kotlin/metifikys/ai/CodexCli.kt: system prompt via a UTF-8
// model_instructions_file, user prompt on stdin, answer from --output-last-message, run in a
// neutral temp dir (no AGENTS.md / repo context). Rows have bench.mjs's shape, so analyze.mjs
// and dump.mjs work unchanged; the model id is recorded as `codex:<model>`.
import { readFileSync, writeFileSync, existsSync, appendFileSync, mkdtempSync, rmSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [,, inputPath, outPath, modelList, effort] = process.argv;
if (!modelList) { console.error('usage: bench-cli.mjs <input> <results.jsonl> <model,...> [effort]'); process.exit(1); }
const MODELS = modelList.split(',').map(s => s.trim()).filter(Boolean);
const COMMAND = process.env.CODEX_CMD ?? 'codex';
const TIMEOUT_MS = 600_000;
// Same strict-JSON suffix as CodexCli.completeJson is NOT appended: the bench tasks are
// summarize (complete) and digest render (summarizeShortlist), both plain-text calls.

const input = JSON.parse(readFileSync(inputPath, 'utf8'));
const tasks = [
  ...input.summarize_tasks.map(t => ({ id: t.id, system: t.system, user: t.user })),
  { id: input.digest_task.id, system: input.digest_task.system, user: input.digest_task.user },
];

const done = new Set();
if (existsSync(outPath)) {
  for (const line of readFileSync(outPath, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    try { const r = JSON.parse(line); if (r.ok || r.permanent) done.add(r.model + '|' + r.task); } catch {}
  }
}

const now = () => new Date().toISOString().slice(11, 19);
const workDir = mkdtempSync(join(tmpdir(), 'rssbot-bench-codex-'));

function runCodex(model, task) {
  const sysFile = join(workDir, `sys-${task.id}.md`);
  const outFile = join(workDir, `last-${task.id}.txt`);
  writeFileSync(sysFile, task.system, 'utf8');
  writeFileSync(outFile, '', 'utf8');
  const args = [
    'exec', '--skip-git-repo-check', '--model', model,
    '-c', `model_instructions_file="${sysFile.replace(/\\/g, '/')}"`,
    // the dev box's ~/.codex/config.toml starts a serena MCP server on every run; the prod
    // server has none, so keep it out of the latency
    '-c', 'mcp_servers.serena.enabled=false',
    ...(effort ? ['-c', `model_reasoning_effort="${effort}"`] : []),
    '--output-last-message', outFile, '-',
  ];
  const t0 = Date.now();
  return new Promise((resolve) => {
    const p = spawn(COMMAND, args, { cwd: workDir, windowsHide: true });
    let stdout = '', stderr = '';
    p.stdout.on('data', d => { stdout += d; });
    p.stderr.on('data', d => { stderr += d; });
    const timer = setTimeout(() => p.kill('SIGKILL'), TIMEOUT_MS);
    p.on('error', e => { clearTimeout(timer); resolve({ ok: false, ms: Date.now() - t0, error: String(e) }); });
    p.on('close', (code, signal) => {
      clearTimeout(timer);
      const ms = Date.now() - t0;
      const log = stdout + '\n' + stderr;
      // codex echoes the prompt; everything we parse comes after it
      const marker = task.user.trim().slice(-200);
      const idx = log.lastIndexOf(marker);
      const tail = idx >= 0 ? log.slice(idx + marker.length) : log;
      const tok = tail.match(/tokens used\s*\n\s*([\d\s,]+)/i);
      const meta = {
        effort: (log.match(/reasoning effort:\s*(\S+)/) ?? [])[1] ?? null,
        web_searches: (tail.match(/^web search:/gm) ?? []).length,
        tokens_used: tok ? Number(tok[1].replace(/[\s,]/g, '')) : null,
      };
      if (code !== 0) {
        const detail = tail.trim().slice(-600) || `<no output> signal=${signal}`;
        resolve({ ok: false, ms, exit: code, error: detail, aborted: signal === 'SIGKILL',
                  permanent: /model.*(not found|not supported|does not exist)|unknown model/i.test(detail), ...meta });
        return;
      }
      let content = '';
      try { content = readFileSync(outFile, 'utf8').trim(); } catch {}
      if (!content) content = stdout.trim();
      resolve({
        ok: true, ms, provider: 'codexcli', finish: 'stop',
        content, content_len: content.length,
        // codex's web-search tool leaks "citeturn0search1"-style markers into the text
        cite_leaked: (content.match(/cite[\w†-]*turn\d/g) ?? []).length,
        think_leaked: 0, reasoning_len: 0,
        usage: meta.tokens_used != null ? { total: meta.tokens_used } : null,
        ...meta,
      });
    });
    p.stdin.end(task.user, 'utf8');
  });
}

for (const model of MODELS) {
  const id = `codex:${model}`;
  console.log(`${now()} === start ${id} ===`);
  for (const task of tasks) {
    const key = id + '|' + task.id;
    if (done.has(key)) { console.log(`${now()} skip ${key} (done)`); continue; }
    let rec;
    for (let attempt = 1; attempt <= 2; attempt++) {
      const r = await runCodex(model, task);
      rec = { model: id, task: task.id, attempt, ...r };
      if (r.ok || r.permanent) break;
      console.log(`${now()} retry ${key} attempt=${attempt} err=${(r.error || '').slice(0, 160)}`);
      await new Promise(res => setTimeout(res, 15_000));
    }
    appendFileSync(outPath, JSON.stringify(rec) + '\n');
    const label = rec.ok
      ? `${rec.ms}ms len=${rec.content_len} tok=${rec.tokens_used} web=${rec.web_searches} cite=${rec.cite_leaked}`
      : `FAIL ${rec.error?.slice(0, 160)}`;
    console.log(`${now()} ${key} -> ${label}`);
  }
}
try { rmSync(workDir, { recursive: true, force: true }); } catch {}
console.log('DONE');
