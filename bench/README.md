# bench/ — free-LLM benchmark & DB extraction audit

Standalone Node harness (Node 22.5+, uses built-in `node:sqlite` and `fetch` — no npm deps)
for comparing OpenRouter models on the bot's REAL tasks, plus SQL audits of extraction
quality. Everything runs from the **repo root** and reads the OpenRouter key from
`config.yaml` at runtime — no secrets live in this directory.

Data locations: prod DB snapshot at `logs/news.db` (the default in every script), retention
archive (`archive.enabled`) under `archive/`. Raw request dumps, per-model `laya-*.jsonl`
outputs and `.npz` embeddings under `results/` are gitignored; reports and `results.jsonl`
are committed.

Longer history: `bench/lib/archive.mjs` merges the archive files into a session (TEMP tables that
shadow `articles`, `covered_events`, … so the SQL stays unchanged). `jev-prep.mjs` takes it as
`--archive <dir>`; other scripts call `mergeArchive(db, dir, tables)` right after opening the DB.

First run: 2026-08-28 (frozen inputs + raw outputs in `results/2026-08-28/`, published
report: "Free-LLM бенчмарк RssNewsBot" artifact). Winners then: `nemotron-3-ultra`
(summarize), `minimax-m3` (digest render), `dots-3-note-preview`, `minimax-m2.7`.

## Benchmark pipeline

```bash
# 1. Build benchmark inputs from a prod DB snapshot (picks recent summarize articles
#    + reconstructs one real politics digest prompt with previous-digest context).
#    Review/adjust the picked digest cycle inside prep.mjs when the snapshot changes.
node bench/prep.mjs bench/results/<date>/bench-input.json logs/news.db

# 2. Run all models × tasks. Resume-safe: re-running skips (model, task) pairs already
#    recorded as ok/permanent in results.jsonl. Retries 429/5xx with backoff; 3 models
#    in flight; ~1.5s pause between calls (free tier is ~20 req/min without credits).
#    Optional 4th arg filters models by substring (smoke test one model first).
node bench/bench.mjs bench/results/<date>/bench-input.json bench/results/<date>/results.jsonl config.yaml [modelFilter]

# 3. Auto-metrics: latency, tokens, language (Cyrillic share + russian letters), digest
#    link discipline (valid / repeats-from-previous-digests / invented URLs), format.
node bench/analyze.mjs bench/results/<date>/results.jsonl bench/results/<date>/bench-input.json bench/results/<date>/metrics.json

# 4. Human-judging dumps: all outputs side-by-side with sources and prod baselines.
node bench/dump.mjs bench/results/<date>/results.jsonl bench/results/<date>/bench-input.json bench/results/<date>
```

Subscription CLI models (`codex exec`, same transport as `CodexCli.kt`: system prompt via
`model_instructions_file`, prompt on stdin, answer from `--output-last-message`, neutral temp dir)
run on the same inputs and write the same row shape (model id `codex:<model>`), so steps 3–4 apply:

```bash
node bench/bench-cli.mjs bench/results/<date>/bench-input.json bench/results/<date>/results.jsonl gpt-5.6-luna,gpt-6-luna [effort]
```

First CLI run 2026-10-10 (`results/2026-10-10-cli/report.md`): `gpt-5.6-luna` ≥ `gpt-5.5` at
40–60 % of the tokens, `gpt-6-luna` close behind (one meta tail); `gpt-5.4-mini` is refused for
ChatGPT-account codex logins.

Update the `MODELS` list in `bench.mjs` from https://openrouter.ai/api/v1/models
(filter ids ending `:free`) before a new run. Auto-metrics deliberately do NOT replace
reading `digests.md` / `summaries.md` — the 2026-08 run caught a Spanish reply, invented
facts, and russisms that the metrics can't see (a Latin-script wrong language passes the
Cyrillic check).

Caveats: free-tier latency/availability swing by the hour (whole providers were down for
a full day during the first run) — treat relative comparisons as reliable, absolutes as a
snapshot, and re-test unavailable models another day. The bench shares the prod OpenRouter
key's daily free-request quota; a full 17-model run is ~110 calls.

## DB extraction audit (`db-audit/`)

Read-only sweeps over a `news.db` snapshot; default path `logs/news.db`, override with
the first arg.

- `overview.mjs` — volumes per category/status, description-length buckets, recent
  digests, `llm_calls` usage per provider/model, pending batches.
- `quality.mjs` — per-host quality (empty/tiny/short descriptions, reddit boilerplate,
  raw HTML, paywall/read-more markers, summaries coverage) + concrete bad-row samples.
- `quality2.mjs` — markup share of the prompt budget per host, empty-desc hosts,
  site-chrome boilerplate counts, teaser-host length percentiles, t.me service-noise
  (mute candidates).
- `cache-audit.mjs [db] [--days 30] [--prompts prompts] [--price-in 2.0]` — would Anthropic
  prompt caching of the static system prompt pay? Per (category, use case, provider, model):
  calls/day, gap between neighbouring calls (p50 / p90, share ≤ 5 / 10 / 60 min = the ceiling
  of the hit rate for that TTL), static prefix vs whole prompt (chars/4 from the local prompt
  files), and the monthly net saving at `--price-in` $/M for TTL 5 min and 1 h; ends with the
  go / no-go of the plan (paid Anthropic API for EXTRACT/RENDER ≥ 2 weeks AND ≥ $5/month and
  ≥ 20% of that bill).

  **Decision 2026-09-23: no-go, no `cache_control` code.** On the prod snapshot (30 days) no
  EXTRACT/RENDER call went to the paid Anthropic API — Step 1 and render run on the `claude` /
  `codex` subscriptions, per token $0. The static system prompt is only 5–17% of the input
  (tech Step 1: ≈2.3K of ≈20K tokens), calls of one prompt are 30–60 min apart (≤ 5 min:
  0–1%, ≤ 60 min: 44–89%), so even priced as if on the API at $2/M the whole EXTRACT/RENDER
  traffic would save at most ≈ $2.8/month with a 1 h TTL, and lose money with the 5 min one.
  Re-run the audit when a category moves to the paid Anthropic API.

These audits produced the 2026-08 fixes: HTML strip at ingestion (`HtmlText`), site-chrome
scrubbing + meta-description fallback in `ArticleFetcher`, politics mute keys, and the
`openrouter.models` failover ladder.

Timestamps in the DB are the server's **local** time stored as text, so date windows compare
with `datetime('now','localtime',…)`, never the bare (UTC) `datetime('now',…)`.

### Report (`db-audit/report.mjs`)

A weekly look at the whole picture without hand-written SQL: one self-contained HTML page
(inline CSS + SVG charts, no scripts, no network), opened straight from disk.

```bash
node bench/db-audit/report.mjs [db=logs/news.db] [out=scratch/db-report.html] \
     [--days 30] [--stale 26] [--now "YYYY-MM-DD HH:MM"] [--prices prices.json] [--archive archive/]
```

Sections: messages per category/day and digest runs; reactions (share of messages with one,
per emoji, top 10); LLM calls per provider/model/use case with tokens, avg/p95 latency and
cost; feed/domain health (sources derived from article links, regular sources quiet for more
than 3 days in red); who writes first (per-outlet first rate and median lag over the
`event_articles` story clusters with 3+ outlets, needs the bot's `storyIntel`); dedup layers
(L2 semantic, Step 1 rejected / duplicate / covered); article
status, queue and PROCESSING orphans older than `--stale` hours, batch jobs; CLI health, digest
pauses, Telegram mutes, mute proposals and moderation presses; the audience-affinity snapshot.
Sections whose table is missing (an older DB) or empty say so instead of failing.

- **Time:** windows are whole local days ending now; run on the server or a machine in the
  same time zone, or pin the end with `--now` (a snapshot copied hours ago otherwise shows
  "last message 9 h ago").
- **Cost:** `est_cost_usd` is 0 when the bot has no `pricing:` block, so the script prices
  tokens itself from a small table (`PRICES` in the script; `:free` models cost 0, BATCH is
  half price). `claudecli` / `codexcli` are marked *subscription*: their figure is the API
  equivalent, not a bill. Override or add prices with
  `--prices file.json` = `{"model-id or prefix*": {"input": 2, "output": 10}}` (USD per 1M).
- **History:** retention trims `rejected_events` (~30 d), `covered_events` (14 d or
  `feedback.lookbackDays`), `digest_messages` / `reaction_counts` (90 d) and `llm_calls`;
  `--archive <dir>` merges the monthly archive files through `lib/archive.mjs`.
- Not in the DB, so not counted: L1 link dedup, Step 0 mutes, cooldown drops, L3.5.
- The page contains real titles and counts: keep it under the gitignored `scratch/`.

## Shadow runs (`shadow-dump.mjs`)

The bot's `llm.shadow` (a B model / prompt next to the live A on the same input, only A posted)
stores every pair in `shadow_runs`. This dumps them side by side:

```bash
node bench/shadow-dump.mjs [db=logs/news.db] [outDir=scratch/shadow] [--category tech] [--days 7]
```

`shadow.md` + a static `shadow.html`: per (category, stage, B model) the runs, B failures (call
error or unparseable answer), mean Jaccard of the links, B latency p50 / p95, topics the URL
whitelist dropped, mean topic length and Cyrillic share; then every run — Step 1 as a URL table
(both / only A / only B, status and newsworthiness/digestFit of each side, B's drops, B's raw
answer when it did not parse), render as the two sides' topics next to each other. Both sides
went through the same post-processing (Step 1 without the audience term), so a difference is
the models' or prompts', not the code's.

## Fact checks (`factcheck-dump.mjs`)

The bot's `factCheck` judge stores a verdict per sampled post in `fact_checks`. Before trusting
the `/status` ⚠️ alert, estimate the judge's precision by hand:

```bash
node bench/factcheck-dump.mjs [db=logs/news.db] [out=scratch/factcheck.md] [--days 14] [--category tech] [--verdict unsupported]
```

Per category: checks, unsupported and minor share, average source size; then every verdict other
than `supported` (or only `--verdict`) with the post and each flagged claim as a checkbox with the
judge's note — tick the real ones; false alarms point at what to tell the prompt
(`prompts/factcheck.example.yaml`).

## Jev (TypeSafe) pre-filter benchmark (`jev-*.mjs`)

Question: can a cheap "decision model" (`typesafe/jev-1.13` via OpenRouter's
`/api/alpha/decisions`, $0.042/M input, no output cost) drop junk *before* Step 1 so the
LLM sees smaller batches? Ground truth = Step 1's own verdicts in a prod snapshot
(`rejected_events.status='rejected'` vs `covered_events`); duplicates excluded (Jev can't dedup).

```bash
node bench/jev-prep.mjs    bench/results/<date>-jev/input.json logs/news.db 250   # 250 rej + 250 cov per category
node bench/jev-bench.mjs   bench/results/<date>-jev/input.json bench/results/<date>-jev/results.jsonl config.yaml [limit] [conc]
node bench/jev-analyze.mjs bench/results/<date>-jev/results.jsonl bench/results/<date>-jev/input.json bench/results/<date>-jev/report.md
```

Per article Jev answers three questions: `keep` (noul), `kind` (17-way choice), `fit`
(0–3 score). First run 2026-09-18 (`results/2026-09-18-jev/report.md`): 1500 items,
$0.09 total, p50 370 ms. Gate `keep < 0.1` removes 50 % of tech junk / 61 % of gaming junk
at ≤ 4 % loss of shortlisted items; science needs `keep < 0.2` (37 % of junk, zero loss).
Most "lost" items were reviews/previews/opinion that Step 1 let through against its own policy.

### Laya (local, open-weights "Jev clone") — 2026-09-23

`convaiinnovations/laya` (ModernBERT-large 421M + `multilingual/` mmBERT-base 322M), run locally
on the RTX 5080 with the checkpoint's own `rl_agent_api.RLAgent`. Same 1500 items and
byte-identical requests (`laya-dump.mjs` → `requests.jsonl`; `laya-bench.py` writes the
jev-bench row shape, so `jev-analyze.mjs` scores it). Env: isolated `C:\Users\user\laya-test`
(uv + Python 3.12 + torch cu128). Result: AUC of `keep` 0.55 (english) / 0.49 (multilingual)
with our questions, 0.68 / 0.55 with short README-style questions (`requests-short.jsonl`),
vs Jev 0.86–0.90. No usable gate threshold; english checkpoint scores Cyrillic items ~0.
Fast (p50 25 ms english / 16 ms multilingual on GPU) but not good enough for us.

### Vega 0.8B (local "decision model", `vega-bench.py`) — 2026-10-10

`nandakishorm/vega-08b-public-intents`: a frozen Qwen3.5-0.8B read at layers 13/19 plus a 57 MB
"physics engine" head, Jev-shaped API (`choice` / `boolean` / `score`; our `noul` is sent as
`boolean`, `fit` = expected score 0–3). Same 1500 items and requests as Laya, run with the
checkpoint's own `vega_api.Vega` in the `C:\Users\user\laya-test` venv (model under `laya-test\vega`,
backbone in its HF cache, `HF_HOME=C:\Users\user\laya-test\hf`; no Triton / fla on Windows, the torch
fallbacks are enough: p50 128–158 ms per item on the RTX 5080).

```bash
HF_HOME=C:/Users/user/laya-test/hf python bench/vega-bench.py bench/results/2026-09-18-jev/requests.jsonl \
    bench/results/2026-09-18-jev/vega-08b.jsonl C:/Users/user/laya-test/vega [device] [views] [adapters]
```

Result: no signal. AUC of `keep` 0.45–0.50 with our questions (`vega-08b-report.md`), 0.50–0.65 with the
short ones (`vega-08b-short-report.md`), 0.52–0.58 with the adapters forced on and two views
(`vega-08b-short-v2-on-report.md`); `fit` 0.54–0.70. Jev: 0.86–0.90, Laya-short 0.66–0.72, the
embedding classifier 0.90. The shipped `public_intents` adapter (banking / e-mail routing) never
gates on for our questions; `kind` collapses to `update_patch` / `other`, and the `keep` probability
sits at 0.75–0.92 for everything with the long questions and below 0.01 with the short ones, so there
is no threshold to gate on. The card itself says Jev leads on most of its benchmarks; ours is one of them.

### Embedding classifier (`emb-clf.py`) — 2026-09-23

Logistic regression on the `article_embeddings` we already store (text-embedding-3-small,
title + summary|description), labels = Step 1 verdicts, time split train < 08-20 ≤ val < 08-24
≤ test; thresholds picked on val. tech/gaming only (science has no embeddings). Report:
`results/2026-09-23-emb/report.md`. Test AUC 0.90 (tech) / 0.91 (gaming). Gate at ~1.5 % loss of
kept items removes 47 % / 44 % of junk (40 % / 26 % of Step 1 input). Head-to-head on the same
bench items in the test window: tech 0.87 vs Jev 0.89, gaming 0.945 vs Jev 0.887. Zero cost,
no extra call; host one-hot adds little.
Low-loss points (`emb-clf-lowloss.py`, thresholds from 5-fold time OOF on all pre-test data,
report `results/2026-09-23-emb/lowloss.md`): tech target 0.5 % → 1/775 kept lost, 32 % junk
dropped, −26 % Step 1 input; gaming target 0.25 % → 0/727 lost, 25 % junk, −15 % input.
Frozen check on fresh prod data (`emb-clf-frozen.py`, 2026-09-23): model refit from the
2026-09-05 DB copy on decisions 08-06..08-23 (the 09-02 snapshot was overwritten; sanity on the
old test window matches), saved as `results/2026-09-23-emb/model-frozen-train-lt-2026-08-24.npz`,
scored without refit on decisions 09-03..09-23 (`frozen-eval.md`): AUC 0.889 tech / 0.884 gaming.
tech @0.5 %: 11/1602 kept lost (8 of them historical-curiosity items), 33 % junk, −28 % input;
tech @0.25 %: 2/1602, 22 % junk, −18 %. gaming @0.25 %: 2/1476, 16 % junk, −9 %; @1 %: 0.95 %, 33 %.
