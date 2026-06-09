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

### Changed
- **Digest channel rendering switched from Markdown to Telegram HTML**
  (`TopicFormatter.toHtml`), so labels and URLs containing brackets or parentheses no
  longer corrupt on a parse failure; the plain-text fallback keeps links readable. The
  status path remains on legacy Markdown.
