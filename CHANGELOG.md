# Changelog

All notable changes to this project are documented in this file.
The format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
The project is currently pre-release (`1.0-SNAPSHOT`), so changes are tracked under a
single **Unreleased** section.

## [Unreleased]

### Added
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
