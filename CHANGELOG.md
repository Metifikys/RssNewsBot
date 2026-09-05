# Changelog

All notable changes to this project are documented in this file.
The format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The project is currently pre-release (`1.0-SNAPSHOT`), so changes are tracked under a
single **Unreleased** section.

## [Unreleased]

### Added
- **Independent per-category schedulers** (`scheduler.perCategory`, default `true`) — every
  category runs on its own single-thread scheduler at `categories.<name>.intervalMinutes`
  (default `scheduler.intervalMinutes`), first run at startup. Runs of one category never
  overlap and a run that outlasts its interval only delays its own next run; no category ever
  waits for the slowest one, so the cycle barrier and its knobs
  (`processing.categoryDeadlineMinutes`, `maxConcurrentCategories`) only matter in the legacy
  global cycle (`perCategory: false`). Housekeeping that used to open and close a cycle —
  retention cleanup, affinity rebuild, the admin status post — runs on a separate maintenance
  tick at the shared interval (`DigestCycle.runMaintenance`), logged per tick. Shutdown stops
  every scheduler under one 30 s budget and interrupts whatever is still running.
- **Decoupled ingestion timer** (`fetcher.intervalMinutes`, per category
  `categories.<name>.fetchIntervalMinutes`) — when set, a category runs on two timers: ingestion
  (RSS fetch → link-dedup → enrich → per-article summarize → insert → embedding dedup) on the
  fetch cadence, and the digest (Step 1 + Step 2 on whatever is already in the DB) on the digest
  cadence. An evening burst of articles is enriched and summarized as it arrives instead of in
  front of the digest, so a digest run is only the two LLM steps. Unset keeps ingestion inside
  the digest run.
- **Startup reclaim of orphaned `PROCESSING` rows** — every row a previous JVM left
  `PROCESSING` and no pending Batch API job owns goes straight back to `UNPROCESSED` at startup
  (`NewsDatabase.reclaimOrphanedProcessing`), instead of waiting out `staleTimeoutHours` and then
  landing in one cycle as a single oversized Step-1 prompt (a forced shutdown mid Step 1 had
  parked ~100 rows for 26 h and produced a 134 KB prompt that timed out for 3 h).
- **Queue-model RSS fetcher + `fetcher:` config block** — all feeds go through one shared
  bounded pool (`maxConcurrentFetches`), one HTTP attempt per pass, retryable failures re-enter
  the queue after `retryDelaySeconds` (a server `Retry-After` wins) up to `maxAttempts`, and the
  whole stage is capped by `fetchDeadlineSeconds`; stragglers defer to the next run, link-dedup
  makes that a delay, never a loss. Retries cover the whole transient family (408/429/5xx), not
  just 502.
- **Shared self-tuning per-host throttle** (`HostThrottle`) used by both `RssFetcher` and
  `ArticleFetcher`, so a 429 earned by either slows both down for that host.
- **Reddit link-post rewrite** — reddit feed items that are link posts are repointed at the
  article their `[link]` anchor targets (boilerplate card dropped, full-content fetch forced).
- **Ingestion text hygiene** — feed HTML is stripped from descriptions at ingestion
  (`HtmlText.strip`; t.me mirrors and reddit shipped 40–60 % markup in the prompt budget), known
  site chrome is scrubbed from extracted article text before the length cut, and
  `og:description` / `twitter:description` / meta description serve as the text when body
  extraction yields nothing (paywall teasers, YouTube pages). Tracking variants of a link
  (`utm_*`, `fbclid`, host casing, trailing slash, fragment) are normalized to one row
  (`LinkNormalizer`).
- **Keyword mute** (`preferences.mute`, merged with a per-category `mute:` list) — whole-word,
  case-insensitive match on title + description; matching articles are marked `PROCESSED` before
  Step 1 so the LLM never sees them. `mute-suggestions.yaml` carries data-driven candidates.
- **OpenRouter model ladder** (`openrouter.models`, up to 4 distinct entries) — the global
  default sync paths (summarize, render, extract-alternate) walk the list on failure via a
  `FallbackLlmClient` chain, so a withdrawn free-tier model fails over to the next instead of
  taking the use case down. Each link is metered separately for `/status`. Per-category `llm.*`
  overrides still pin one model and bypass the ladder.
- **Step-1 prompt bounds** (`dedup.extractMaxArticles`, default 100; `dedup.extractMaxPromptChars`,
  default 80 000) — `capForExtract` keeps the newest articles by `pubDate` within both budgets,
  always admitting at least one; overflow stays `UNPROCESSED` for the next run. For a CLI-backed
  extract (`codex exec` with gpt-5.4-mini) prompts above ~110 KB total took 10–60 min or timed
  out, so ~40 articles is the practical ceiling there.
- **Reaction feedback loop** (`feedback:` block, off by default) — channel reactions are
  aggregated into `audience_affinity` (cohort z-score with a zero-reaction baseline,
  winsorization, half-life decay, empirical-Bayes shrinkage toward the category prior, `n_tone`
  evidence counts) on a throttled rebuild. Phase 2 feeds it back into selection behind config:
  `ranker.reactionWeight` adds an affinity term to the shortlist ranker (log-only by default,
  `reactionLogOnly: false` to apply; `maxAffinityBoost` clamps it, an epsilon floor keeps
  one-emoji categories on their deterministic tiebreak chain), and an `{{AUDIENCE_SIGNALS}}`
  placeholder in extract prompts injects top liked/disliked franchises and event types once a key
  has ≥ 3 tone samples. `/status` shows the ranked affinity table; `[AffinityRank]` logs the exact
  selection diff each run. Env-gated prod replay harness (`REPLAY_DB`) for what-if runs.
- **Bench harness** (`bench/`, Node, no deps) — builds real summarize/digest tasks from a DB
  snapshot, runs the OpenRouter model matrix, and computes language/link/format metrics; the
  2026-08-28 run that produced the current ladder is frozen under `bench/results/`.
- **Server deploy kit** (`deploy/`) — systemd unit (`Restart=always`, `-Xmx512m`,
  `+ExitOnOutOfMemoryError`), daily online SQLite backup with rotation, one-shot `VACUUM INTO`
  compaction, env-file example, and a deploy README. CI workflow (`./gradlew build` with JaCoCo
  on push/PR), committed Gradle wrapper, `.gitattributes` keeping scripts at LF, and a real
  `gradle.lockfile`.
- **Configurable retention** — `processing.articleRetentionDays` (replaces the hard-coded 1500),
  `llmCallRetentionDays`, `embeddingRetentionDays`; `llm_calls` and article embeddings are now
  actually pruned (they were only pruned from tests, and `news.db` had reached 700+ MB). Validated
  at load against `weekly.lookbackDays` and `semanticDedup.windowDays`. `covered_events` are kept
  for `max(summaryHistory.retentionDays, feedback.lookbackDays)` so the affinity join never loses
  reactions.
- **Event-level dedup hard filter (`semanticDedup.eventHardThreshold`)** — graduates the
  log-only `EventSemanticAnalyzer` (Layer 3.5) to an optional hard filter. When set, a shortlist
  event with `status == "new"` whose top covered-event cosine ≥ the threshold is dropped before
  Step 2 renders (logged `[EventSemanticDedup][REJECT]`) and never reaches the digest. Cosine-only
  (no subject gate), tuned per category via the existing `semanticDedup` block; `meaningful_update`
  follow-ups are never dropped, rejected events are not persisted, and any embed/billing/scan error
  fails open (returns the shortlist unchanged) so a transient failure can never empty a digest.
  Validated `eventHardThreshold >= eventThreshold` at config load. No schema change.
- **Telegram reaction tracking** (opt-in `telegram.updatesPolling`, default off) — a daemon
  `getUpdates` long-poll collects anonymous channel-post `message_reaction_count` updates
  (`allowed_updates` restricted to that single type, carrying no user identity). Each delivered
  digest message is persisted (`digest_messages`, mapping topic URL → Step 1 `event_key`); counts
  are replace-all upserted into `reaction_counts` with the poll offset in `bot_state`, and `/status`
  gains a "Reactions (7d)" panel (totals, posts, top emoji per category). Loud 409 handling for
  Telegram's one-getUpdates-consumer-per-token limit; rows pruned after 90 days. New
  `digest_messages` / `reaction_counts` / `bot_state` tables.
- **Weekly "top story of the week" roundup** — an optional `weekly:` config block enables a
  dedicated scheduler that fires once per week at a configurable `dayOfWeek` + `time` (the bot's
  local zone). For each participating category (the explicit `weekly.categories` list, else every
  category with a `dedup:` block) it ranks the week's covered events by how many times each story
  was seen — the canonical coverage plus every later `duplicate` detection from `rejected_events`,
  with near-duplicate phrasings merged via an embedding pass (`WeeklyClusterer`, reusing
  `Embedder`/`VectorMath`). The top-by-mention candidates are handed to the category's render LLM
  (honoring its `llm.render` override) to pick, order, and write the roundup, which is posted to
  the category's own channel. No schema migration — the "most duplicates" signal is read from the
  existing `covered_events` / `rejected_events` tables. Built-in Ukrainian prompts, overridable via
  `weekly.promptFile` (`weekly.system` / `weekly.user`) or inline `weekly.prompts`. Prompt-injection
  URL guard mirrors `DigestDeliverer`. Off by default. See `WeeklyDigest` / `WeeklyScheduler`.
  Each bullet shows a `🔁 N` mention badge (N = how many times the story was seen that week,
  matched to the bullet by URL so it is always exact; toggle with `weekly.showMentions`), and an
  optional `weekly.hashtag` (e.g. `#головне`) is appended at the end of the post.
  Categories without a `dedup:` block (no events) fall back automatically to ranking the week's raw
  **articles** — clustered the same way (`WeeklyDigest.buildEventsFromArticles` →
  `WeeklyClusterer`), with a wider `weekly.articleCandidatePoolSize` (default 40) so a
  low-duplication / single-source category still hands the LLM a broad weekly sample. Backed by a
  new `NewsDatabase.fetchRecentArticles`.
- **`TopicFormatter.toHtml` now renders every inline `[label](url)` link, not just a trailing
  one** — single-pass conversion of `**bold**` / `` `code` `` / links anywhere in the text. Fixes
  multi-link messages (the weekly roundup, sent as one message) where only the last link was
  converted and the rest leaked as literal Markdown. The per-bullet digest path is unaffected
  (one trailing link → identical output).
- **On-failure provider fallback** — any sync LLM slot (`extract`, `extractAlternate`, `render`,
  `summarize`, `batchFallback`) may declare a nested `fallback: { provider, model }`. When the
  primary call fails (usage/credit limit, expired login, bad model, or an exhausted-retry
  timeout), the same call is transparently retried against the fallback provider+model — e.g.
  Claude CLI → Codex CLI. Implemented as a `FallbackLlmClient` decorator wired in
  `LlmClientsFactory`; each leg is metered separately so `/status` attributes a fallback-served
  call to the provider that answered it. Sync-only and distinct from the A/B `extractAlternate`
  and the backpressure `batchFallback`; chains up to depth 3, with cycle and inert-combination
  validation at config load.
- **Event-level semantic analyzer (Layer 3.5), log-only** — `EventSemanticAnalyzer`, the
  event-level counterpart to the article-level `SemanticDedupDetector`. After
  `EventExtractor` produces a shortlist it embeds each event (subject + coreFact) keyed by
  `eventKey`, cosine-scans recently-covered events in the same category, and logs
  near-duplicate candidates. Analyze-only: never removes, mutates, or filters the digest.
  Backed by a new `event_embeddings` table and `SemanticDedupConfig.eventEnabled` /
  `eventThreshold` config.
- **Claude CLI LLM provider** (`claude -p`, Claude Code print mode) — a sync-only
  `LlmClient` that shells out to the local Claude CLI, authenticating via the machine's
  existing CLI login rather than an API key. Selectable via per-category
  `llm.{extract,render,summarize,batchFallback}` overrides. Batch is unsupported and
  rejected at config load.
- **Codex CLI LLM provider** (`codex exec`) — a sync-only provider parallel to the Claude
  CLI, subscription-billed with no API key. Mirrors ClaudeCli's transport, retry/billing
  semantics, and JSON handling; wired through the same enum/factory/config/validation/
  metering points. Sync-only — batch use is rejected at load.
- **Per-category `skipBatch`** — when set, a category never uses the Batch API; Step-2
  render runs synchronously via `llm.batchFallback` (when configured) or the sync render
  client. Rejected at load if combined with any batch override.
- **og:image / Twitter Card preview fetch for image-less news** — when a category has
  `enableImages=true` but a news item carries no RSS image, the article page's Open Graph /
  Twitter Card preview image is fetched and used as `Article.imageUrl`, so the
  photo+caption delivery path has an image to post (`ArticleFetcher.fillPreviewImages` /
  `extractOgImage`, reusing the existing fetch path and SSRF URL validation; no new deps).

### Fixed
- **A CLI timeout is terminal for both retry loops.** A `codex exec` / `claude -p` call that hit
  its `timeoutSeconds` ceiling was retried by the inner per-call loop and again by
  `completeJson(maxRetry)`: 3 × 4 = 12 × 20 min ≈ 4 h for one oversized Step-1 prompt, and with
  the old cycle barrier the whole bot. `CliTimeoutException` now fails on the first timeout so
  Step 1 falls back to the legacy chunked path and the next run retries with fresh input; the
  message carries the tail of stdout/stderr, the model and the prompt size.
- **OpenRouter numeric error codes are decoded.** OpenRouter sends `"code":404` as a bare number;
  the String-typed field failed to decode, the envelope fell through as a retryable `IOException`
  and a model withdrawn from the free tier was retried 5× a minute apart for every article.
  `code` is a `JsonPrimitive` for the chat and embeddings clients, so 4xx fails on the first
  attempt and the ladder takes over.
- **Cancellation is cancellation, not an LLM failure.** A cycle-barrier / shutdown interrupt was
  retried by the CLI `completeJson` loops, turned into a legacy fallback by `EventExtractor`,
  spawned a second CLI call from the sync fallback, and left both interrupted `claude -p` children
  running. CLI `runOnce` now `destroyForcibly()`s the child on interrupt, every retry loop
  (shared `RetryPolicy`) propagates `InterruptedException` with the flag restored and refuses to
  retry on an already-interrupted thread (okhttp's `InterruptedIOException` included),
  `CategoryProcessor` reverts articles to `UNPROCESSED` on every interrupt branch, and the
  pipeline skips embed/digest on a cancelled worker.
- **Semantic dedup hard filter anchors on the closest `PROCESSED` neighbour, not top-1** — a
  `DUPLICATE` row sitting at #1 used to shadow the canonical article and let a re-post through.
- **Meaningful-update cooldown looks the previous event up by key** in `covered_events` when it
  has scrolled out of the `maxContextEvents` prompt context, so a multi-day cooldown is no longer
  silently capped at ~1–2 days of context.
- **Topics that failed to send no longer persist `covered_events`** — their articles stayed
  `UNPROCESSED` for retry, but the covered row made Step 1 classify the retry as a duplicate and
  the story silently never reached the channel.
- **BUG-003/006/007/009/010/011/012/015/016/019/020/021/023/025** — linkless topics are dropped
  and an all-invalid digest reverts to `UNPROCESSED`; feed fetch retries 408/429/5xx with
  `Retry-After`; Telegram 429 `retry_after` is honored and only a 400 falls back to plain text;
  caption truncation never splits a surrogate pair or a link; bare non-markdown URLs are rejected
  by the whitelist; retention cleanup runs at cycle start and skips while batches are pending;
  deterministic `ORDER BY` for the ready query; `markProcessing` claims only `UNPROCESSED` rows;
  pending-batch count matches CSV list membership; link normalization at ingestion; enrich /
  preview fetches run on a bounded pool with a time cap; OpenAI `Retry-After` header preferred
  over the regex scrape; the startup migration only clears stray `processing_started_at` and logs
  the count; `stream=false` on the sync chat request.
- **CLI providers (`claude -p` / `codex exec`) now surface the real failure and stop retry
  storms.** On a non-zero exit these CLIs print the actual error — expired login, usage
  limit, unknown model — to **stdout**, but the providers inspected only stderr, so failures
  logged a useless `claude CLI exited with code 1: <no stderr>` while retrying up to ~24
  times per category (the source of the apparent "timeouts"). The error path now classifies
  the combined stdout+stderr: usage/credit limits raise `BillingException` and auth/login or
  unknown-model failures raise the new `NonRetryableCliException`; both short-circuit every
  retry loop (`request` and `completeJson(maxRetry)`), so a persistent failure fails fast and
  the category is skipped for the cycle instead of stalling it. Inner per-call retry default
  lowered 5→2.

### Changed
- **The global digest cycle is legacy.** `scheduler.perCategory: false` restores it; its
  `processing.categoryDeadlineMinutes` barrier now defaults to `0` (wait for every category — each
  stage is internally bounded, so nothing hangs forever) after a positive value kept cancelling
  healthy hour-long CLI Step-1 chains. `processing.maxConcurrentCategories` bounds its fan-out.
- **Step-1 legacy chunk size 100 → 50.**
- **Full LLM prompts and responses are logged at DEBUG only**; INFO keeps model id and lengths
  (BUG-022 log bloat).
- **`/status` metrics overhauled** — the non-actionable token in/out estimates and (inaccurate)
  USD cost figure are replaced with operational signals: per-provider average + nearest-rank p95
  LLM latency over 24h/7d (captured in `MeteredLlmClient` for synchronous calls; batch jobs leave
  `duration_ms` null since their wall-clock is poll-wait, not model latency), and per-category
  ✅ published (`SUM(summaries.articleCount)` ≈ channel posts) / 📰 articles (PROCESSED by pubDate) /
  ⛔ blocked (DUPLICATE dedup hard-rejects) counts with a block %. New nullable `duration_ms` column
  (auto-migrates) plus `fetchProviderLatency` / `fetchArticleStatusCounts` /
  `fetchPublishedTopicCounts` queries; token/cost columns are still recorded, only the rendering
  drops them.
- **SQLite now opens in WAL mode** (`journal_mode=WAL`, `synchronous=NORMAL`, `busy_timeout`
  raised 5s → 15s) so the concurrent writers (category workers, reaction poller, batch callbacks,
  weekly digest) no longer hit "database is locked" under the default rollback journal, where a
  reader blocks the writer and a deferred tx that upgrades to a write can hit `SQLITE_BUSY` before
  `busy_timeout` helps. The effective `journal_mode` is logged at startup so a silent WAL fallback
  (e.g. on a network share) is visible.
- **Categories within a digest cycle are now processed concurrently (one worker per category)
  instead of sequentially on the scheduler thread.** Previously, in fully-sync mode (batching
  off via `primaryMaxPending: 0` or per-category `skipBatch`), each category's blocking
  render/extract call ran inline in a single loop, so one slow or stuck category stalled every
  other category in that cycle. `CategoryProcessor.process()` now fans each category out onto a
  bounded worker pool and barrier-waits, so a stuck category only stalls itself — the others
  render and deliver independently. New `processing.maxConcurrentCategories` knob (default 12)
  caps the pool, which is sized `min(readyCategories, maxConcurrentCategories)`, so the default
  gives every category its own worker. A per-cycle safety deadline cancels a pathological hang
  (its articles stay PROCESSING and retry next cycle), and an unexpected per-category exception
  is now recorded and contained instead of aborting the whole cycle. SQLite opens with
  `busy_timeout=5000` so the now-concurrent writers wait-and-retry instead of failing fast on a
  held write lock.
- **Digest channel rendering switched from Markdown to Telegram HTML**
  (`TopicFormatter.toHtml`), so labels and URLs containing brackets or parentheses no
  longer corrupt on a parse failure; the plain-text fallback keeps links readable. The
  status path remains on legacy Markdown.
