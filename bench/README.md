# bench/ — free-LLM benchmark & DB extraction audit

Standalone Node harness (Node 22.5+, uses built-in `node:sqlite` and `fetch` — no npm deps)
for comparing OpenRouter models on the bot's REAL tasks, plus SQL audits of extraction
quality. Everything runs from the **repo root** and reads the OpenRouter key from
`config.yaml` at runtime — no secrets live in this directory.

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

These audits produced the 2026-08 fixes: HTML strip at ingestion (`HtmlText`), site-chrome
scrubbing + meta-description fallback in `ArticleFetcher`, politics mute keys, and the
`openrouter.models` failover ladder.
