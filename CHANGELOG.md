# Changelog

All notable changes to this project are documented in this file.
The format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The project is currently pre-release (`1.0-SNAPSHOT`), so changes are tracked under a
single **Unreleased** section.

## [Unreleased]

### Added
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
