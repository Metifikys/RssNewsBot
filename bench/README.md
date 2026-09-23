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
