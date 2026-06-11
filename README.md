# RssNewsBot

A Kotlin/JVM Telegram bot that aggregates RSS feeds by category, **deduplicates near-identical
stories at four independent layers** (link, semantic vector, LLM event-extraction, editorial
ranker), generates Ukrainian-language digests through a two-step LLM pipeline
(**extract → render**), and delivers them to Telegram channels on a configurable schedule.

Pluggable across **OpenAI, Anthropic, and OpenRouter** with per-category, per-use-case routing;
first-class support for **OpenAI Batch API** and **Anthropic Message Batches** (50 %-priced
async) with three-tier sync fallback; SQLite persistence; per-call cost telemetry surfaced
via a `/status` admin snapshot.

---

## Table of contents

1. [Feature overview](#feature-overview)
2. [End-to-end pipeline](#end-to-end-pipeline)
3. [The deduplication stack](#the-deduplication-stack) ← all four layers explained
4. [LLM routing & multi-provider support](#llm-routing--multi-provider-support)
5. [Batch API, backpressure, three-tier fallback](#batch-api-backpressure-three-tier-fallback)
6. [Article lifecycle & status state machine](#article-lifecycle--status-state-machine)
7. [Telegram delivery & formatting](#telegram-delivery--formatting)
8. [Cost telemetry & the `/status` snapshot](#cost-telemetry--the-status-snapshot)
9. [Security & hardening](#security--hardening)
10. [Setup](#setup)
11. [Configuration reference](#configuration-reference)
12. [Build, run, tests](#build-run-tests)
13. [Database schema](#database-schema)
14. [Project layout](#project-layout)
15. [Dependencies](#dependencies)

---

## Feature overview

### News intake
- **RSS / Atom fetching** via Rome with HTTP retry-on-502 (configurable attempts and delay).
- **SSRF guard** — RFC-1918 / loopback / link-local hosts are rejected before any network
  call; only `http`/`https` schemes are allowed.
- **No-redirect HTTP** — Telegram client refuses redirects so the bot token cannot leak to a
  third-party host.
- **Image extraction from RSS** — falls back across `<enclosure>`, Media RSS module
  (`<media:content>`, `<media:thumbnail>`), and the first `<img>` in the description HTML.
- **Optional full-article fetch** (`fetchFullContent: true` per feed) via Jsoup, before
  per-article summarization.

### Deduplication (four independent layers — see [§3](#the-deduplication-stack))
1. **Link dedup** in SQL — `articles.link` unique index + pre-enrichment skip of already-known
   URLs.
2. **Semantic vector dedup** — OpenAI `text-embedding-3-small` + L2-normalized cosine; soft
   log threshold + optional hard reject against already-PROCESSED neighbours.
3. **LLM event extraction (Step 1)** — JSON-mode shortlist of new/updated events vs. a
   structured covered-events memory.
4. **Editorial ranker + cooldown** — `newsworthiness × digestFit` composite score, diversity
   caps per franchise/subject, weak-shortlist hold-back, meaningful-update cooldown.

### LLM rendering & routing (see [§4](#llm-routing--multi-provider-support))
- **Multi-provider**: OpenAI / Anthropic / OpenRouter mixable per category and per use case
  (`extract`, `extractAlternate`, `render`, `batch`, `summarize`, `batchFallback`).
- **A/B alternation on Step 1** — when both `extract` and `extractAlternate` are set, every
  second extract request goes to the "B" side.
- **On-failure fallback** — any sync slot can carry a nested `fallback` provider+model; when the
  primary call fails (usage limit, expired login, bad model, timeout) the same call is retried
  against the fallback (e.g. Claude CLI → Codex CLI). Distinct from A/B (`extractAlternate`) and
  backpressure (`batchFallback`).
- **Per-article summarization** — opt-in per feed; stored on `articles.summary` and used by
  downstream prompts in place of the raw RSS description.
- **Inline prompt overrides** — `dedup.prompts.extractSystem/extractUser/renderSystem/renderUser`
  take priority over the prompt file.

### Batch API (see [§5](#batch-api-backpressure-three-tier-fallback))
- **Native OpenAI `/v1/batches`** and **Anthropic `/v1/messages/batches`** — 50 % cheaper,
  async, used for Step 1 extract and Step 2 render.
- **Three-tier routing**: primary batch → `batchFallback` (sync alt LLM) → sync render.
- **Restart-safe** — pending batches persist in DB and are polled to completion after a
  crash/restart.

### Scheduling & concurrency
- Single-threaded `ScheduledExecutorService`; first cycle on startup, then every
  `scheduler.intervalMinutes`.
- Independent per-category submission — a fast category delivers immediately without
  waiting for slow ones to finish.
- Graceful 30-second shutdown that flushes the scheduler before exit.

### Telegram delivery
- Direct HTTP via OkHttp (no `telegrambots` library).
- **Markdown → plain-text fallback** on parse errors.
- **Photo + caption mode** (`enableImages: true`) — when the first linked article has an
  image, the bullet is posted as a single `sendPhoto` call (caption truncated to 1024 chars).
- **Strict three-block topic layout** (emoji + bold headline / body / source link) with
  graceful degradation when the LLM emits unexpected shapes.
- **At-least-once with rollback** — partial-send failures revert just the affected articles to
  `UNPROCESSED`, success wins on overlap.

### Observability
- **Logback** with day-rolling, gzipped archive; structured `[Category:foo]` /
  `[SemanticDedup]` / `[Batch]` / `[Ranker]` prefixes.
- **`/status` admin snapshot** — per-category last digest + ready count + pending batches +
  24h/7d LLM cost (per provider × category), posted one-message-at-a-time so the admin chat
  always shows the latest state.
- **Per-LLM-call cost ledger** — `llm_calls` table records `(provider, model, category,
  useCase, promptTokens, completionTokens, estCostUsd, ts)` for every call, including the
  Batch API auto-halving.

### Operational ergonomics
- **Configurable via YAML** with environment overrides for every secret
  (`TELEGRAM_BOT_TOKEN`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY`).
- **Schema validation at load time** — invalid `hardThreshold` ranges, missing provider
  blocks for `summarize: anthropic`, unsupported `provider: openrouter` on the Batch API,
  path-traversal in `database.path`, etc. all fail fast with explicit messages.
- **Dependency locking** — `gradle.lockfile` pins all transitive versions to defend against
  silent supply-chain shifts.
- **Auto-migration** — schema changes via Exposed `createMissingTablesAndColumns`; legacy
  `articles.processed = 1` rows are migrated to the `status` column on first boot.

---

## End-to-end pipeline

```
config.yaml ──► AppConfig (env overrides + validation)
                        │
                        ▼
       ┌────────────  DigestCycle ────────────┐
       │                                       │
       ▼                                       │
RssFetcher (Rome + SSRF guard + retry)         │
       │                                       │
       ▼                                       │
Pre-link-dedup ─► findExistingLinks() ─────────┤  Layer 1
       │                                       │
       ▼                                       │
ArticleFetcher (Jsoup, optional full-page)     │
       │                                       │
       ▼                                       │
ArticleSummarizer (per-feed `summarize:`)      │
       │                                       │
       ▼                                       │
NewsDatabase.insertArticles (insertIgnore)     │
       │                                       │
       ▼                                       │
SemanticDedupDetector ─► [HIT] / [REJECT] ─────┤  Layer 2
       │                                       │
       ▼                                       │
CategoryProcessor.process(byCategory) ─────────┤
       │                                       │
       ├─ pre-cycle gates: minArticles, batch backpressure
       │                                       │
       ▼                                       │
EventExtractor (Step 1, JSON mode) ────────────┤  Layer 3
       │                                       │
       ├─ URL whitelist filter ────────────────┤
       ├─ meaningful_update cooldown ──────────┤  Layer 4
       ├─ ShortlistRanker.rank ────────────────┤  Layer 4
       │                                       │
       ▼                                       │
EventSemanticAnalyzer ─► [HIT] (log-only) ─────┤  Layer 3.5
       │                                       │
       ▼                                       │
PromptBuilder ─► LlmClient (Batch or sync)     │
       │                                       │
       ▼                                       │
DigestDeliverer ─► TopicFormatter ─► Telegram  │
       │                                       │
       ▼                                       │
status reconciliation + CoveredEvent persist   │
       │                                       │
       ▼                                       │
deleteOlderThan / pruneOldCoveredEvents / …    │
       │                                       │
       └──► StatusPoster.post() (admin chat)
```

---

## The deduplication stack

The bot is built around the assumption that **the same story will be filed by many
different sources**, and that the LLM should only ever see a clean shortlist. There are four
independent dedup layers, applied in this order:

### Layer 1 — Link dedup (cheapest, exact match)

- `articles.link` carries a SQLite `UNIQUE INDEX`.
- Before enrichment, `NewsDatabase.findExistingLinks(...)` filters out already-stored URLs so
  full-article fetching and per-article summarization never run for duplicates.
- `insertArticles` uses `insertIgnore`, so a race with a parallel feed is still safe.

### Layer 2 — Semantic vector dedup (catches paraphrased / re-syndicated stories)

Implemented in [`SemanticDedupDetector`](src/main/kotlin/metifikys/digest/SemanticDedupDetector.kt)
and [`VectorMath`](src/main/kotlin/metifikys/digest/VectorMath.kt).

After articles are inserted, for each category that has `semanticDedup.enabled: true`:

1. Build the embedding text: `title + (summary | description)`, capped at 6000 chars.
2. Call OpenAI `/v1/embeddings` (`text-embedding-3-small` by default) — batched per
   category — and L2-normalize the vector so cosine ≡ dot product.
3. Persist into `article_embeddings(article_id, model, dim, vector, created_at)` as
   little-endian Float32 bytes (`dim * 4` bytes per row).
4. Pull recent neighbours: rows in the same category with `pubDate ≥ now - windowDays`,
   capped at `maxRecent`, excluding the just-inserted ids.
5. Brute-force cosine; sort descending; take `topK`.
6. Emit one log line per article:
   - `[SemanticDedup][HIT]` when `top_sim ≥ threshold`
   - `[SemanticDedup][REJECT]` when `top_sim ≥ hardThreshold` **and** the neighbour's status
     is already `PROCESSED` (was sent in a previous digest)
   - `[SemanticDedup]` otherwise

The **hard filter** is opt-in (`hardThreshold` null by default). When it fires it calls
`db.markDuplicate(articleId, canonicalId)` which:

- sets `articles.status = DUPLICATE`
- writes `articles.duplicate_of = <canonical row id>` (soft pointer, no FK constraint —
  the canonical row may be pruned by `deleteOlderThan` later)
- **only updates rows whose current status is neither `PROCESSED` nor `PROCESSING`** — the
  detector cannot demote articles the digest pipeline has already claimed

Why "only against PROCESSED neighbours"? Matches against `UNPROCESSED` / `PROCESSING`
neighbours are deliberately *not* rejected here — the Step 1 event extractor still owns
within-batch dedup of unsent articles, and we don't want the embedding detector pre-empting
that decision.

**Schema-level invariant:** `hardThreshold ≥ threshold` is validated at config load — the
hard filter is a strict superset of what gets logged.

**Cost:** ~$0.02 / 1M input tokens. At ~200 tokens/article, ~$0.000004/article — cheaper
than a single line of Step 1 prompt context.

**Why opt-in?** It's designed as a **stat-collection phase first**: run with logs only,
inspect `[HIT]` lines to calibrate `threshold` and `hardThreshold` for your corpus
(suggested production values in `config.example.yaml`: gaming/tech ≈ 0.85, politics ≈ 0.78),
*then* turn on the hard filter.

### Layer 3 — LLM event extraction (semantic, structured)

Implemented in [`EventExtractor`](src/main/kotlin/metifikys/ai/dedup/EventExtractor.kt) +
[`PromptLoader`](src/main/kotlin/metifikys/ai/dedup/PromptLoader.kt).

When a category has a `dedup:` block, the bot runs **Step 1** before submitting Step 2 (the
digest renderer). Step 1 calls an OpenAI-compatible chat.completions endpoint in **JSON
mode** with:

- `{{CURRENT_BATCH_JSON}}` — serialized 0-indexed input articles (title, url, description,
  pubDate).
- `{{PREVIOUSLY_COVERED_EVENTS_JSON}}` — structured events already covered in the
  `covered_events` table within `dedup.contextDays`, capped at `maxContextEvents`.

The LLM returns:

```json
{
  "extractions": [
    {
      "event_key": "...",
      "subject": "...",
      "franchise": "...",
      "event_type": "release_date|delay|major_announcement|…",
      "core_fact": "...",
      "importance": 0-10,
      "newsworthiness": 0-10,
      "digest_fit": 0-10,
      "url": "...",
      "status": "new|meaningful_update|duplicate|rejected",
      "related_previous_event_key": "...",
      "article_index": 0
    }
  ],
  "shortlist": [ /* subset of extractions, with status in {new, meaningful_update} */ ]
}
```

What happens with the result:

- **`rejected` / `duplicate`** rows are saved to `rejected_events` for prompt-tuning
  retrospectives (pruned after 30 days).
- **`new` / `meaningful_update`** rows flow into the shortlist.
- **URL hallucination filter** — any shortlist item whose `url` is not in the current
  article set is dropped with a warning. (Defends against prompt-injection payloads
  smuggled in RSS titles or descriptions.)
- **Empty shortlist** → all input articles are marked `PROCESSED` and the cycle skips Step 2.

**A/B alternation.** When both `llm.extract` and `llm.extractAlternate` are configured,
`EventExtractor` flips between them on a per-request counter — every second extract request
goes to the "B" side. When only `extract` is set, alternation is suppressed (legacy
behaviour). When neither is set, the global default is `openai` (plus OpenRouter as
the auto-derived alternate when an `openrouter:` block exists).

### Layer 4 — Editorial ranker + meaningful-update cooldown

Implemented in [`ShortlistRanker`](src/main/kotlin/metifikys/ai/dedup/ShortlistRanker.kt)
and the cooldown helper inside `EventExtractor.applyMeaningfulUpdateCooldown`.

Applied **after Step 1's URL filter, before Step 2 render**. Driven by `dedup.digest.*`:

**Meaningful-update cooldown** (run first, removes flapping):
- For every `status: meaningful_update` item, look up its `related_previous_event_key` in the
  covered-events memory.
- If the previous coverage was less than `meaningfulUpdateCooldownMinutes` ago **and** the
  follow-up's `newsworthiness < newsworthinessOverride`, drop it.
- Strong updates (`newsworthiness ≥ newsworthinessOverride`, e.g. a release date confirmation
  after a teaser) bypass the cooldown.

**Floor filter:**
- `digestFit < minDigestFit` drops the item, **unless** `newsworthiness ≥
  newsworthinessOverride` rescues it.

**Composite scoring & priority tiebreaks:**
- `score = newsworthinessWeight * newsworthiness + (1 - newsworthinessWeight) * digestFit`.
- Ties broken by **priority event types** in this order: `major_announcement`,
  `release_date`, `platform_policy`, `industry_news`, `major_dlc_expansion`, `delay`,
  `cancellation`, `lawsuit`, `acquisition`, `studio_closure`.
- Final tiebreak: `newsworthiness` descending.

**Diversity caps (subject is narrower than franchise, so it wins on overlap):**
- `maxPerSubject` — typically 1 per specific game title.
- `maxPerFranchise` — typically 2 per franchise.

**Hard size clamp:** `maxDigestItems` — anything beyond is dropped with reason
`maxDigestItems=N reached`.

**Weak-shortlist hold-back** (in [`CategoryProcessor`](src/main/kotlin/metifikys/digest/CategoryProcessor.kt)):
- If post-rank shortlist size `< minStrongItems`, skip publishing this cycle and mark the
  articles `PROCESSED` (we've already seen them — no point reprocessing).
- **Unless** the last digest was more than `maxWaitHours` ago, in which case fall back to
  `minItemsOnForcePublish` as the floor (so quiet days still get a digest eventually).

All drops are logged as `[Category:X][Ranker] drop <eventKey>: <reason>` for QA.

### Layer 3.5 — Event-level semantic analyzer (log-only)

Implemented in [`EventSemanticAnalyzer`](src/main/kotlin/metifikys/digest/EventSemanticAnalyzer.kt).
The **event-level counterpart of Layer 2's article-level `SemanticDedupDetector`** — it embeds
Step 1's *structured events* instead of raw articles.

For each category whose `semanticDedup.eventEnabled: true`, once the finalized shortlist is
about to be rendered (the single chokepoint in `CategoryProcessor.submitOrSync`, so the sync,
extract-batch, and startup-resume routes all pass through it):

1. Embed each shortlist event's `subject + coreFact`, keyed by `event_key`, via the same
   OpenAI `/v1/embeddings` client (reusing `model` / `windowDays` / `topK` / `maxRecent`).
2. L2-normalize and brute-force cosine-scan recent **previously-covered** event vectors in the
   same category (`event_embeddings` table, candidate set excludes the current shortlist's own
   keys).
3. Log one line per event: `[EventSemanticDedup][HIT]` when `top_sim ≥ eventThreshold`,
   `[EventSemanticDedup]` otherwise.
4. Persist the event's vector so it becomes a candidate next cycle.

**Analyze-only:** it never drops events, mutates state, or filters the digest — a
stat-collection phase (mirroring how Layer 2 shipped log-only first) to calibrate
`eventThreshold` before any hard filter is considered. Failures are caught and logged so the
detector can never break a cycle.

> Note: vectors are persisted for every shortlisted event regardless of Telegram delivery
> success, so the candidate set is "events we shortlisted" — a close proxy for "previously
> covered" during this calibration phase.

### Extra: URL whitelist after Step 2 ("prompt injection defence in depth")

After Step 2 renders the digest text, [`DigestDeliverer`](src/main/kotlin/metifikys/digest/DigestDeliverer.kt)
runs one more pass:

- Extract every `[label](url)` from the rendered digest.
- Drop entire topics whose URLs are not all present in the current article set
  (`foreignUrls = topicUrls - allArticleLinks`). This catches LLM hallucinations and prompt-
  injection payloads that survived earlier filters.
- Persist only filtered topics to the `summaries` table — never the raw LLM output —
  so the previous-digest URL set used for cross-cycle dedup can never be polluted.

---

## LLM routing & multi-provider support

Three providers, six use cases, all per-category overridable.

| Use case             | Default                                      | Overridable? | Batch-capable provider? |
|----------------------|----------------------------------------------|--------------|--------------------------|
| `extract`            | OpenAI (`openai.model`)                      | yes          | OpenAI ✓, Anthropic ✓    |
| `extractAlternate`   | auto: OpenRouter when configured             | yes          | OpenAI ✓, Anthropic ✓    |
| `render`             | OpenRouter when configured, else OpenAI      | yes          | n/a (sync)               |
| `batch`              | OpenAI (`openai.batchModel`)                 | yes          | OpenAI ✓, Anthropic ✓    |
| `summarize`          | per-feed `summarize:` provider               | yes          | n/a (sync)               |
| `batchFallback`      | none                                         | yes          | n/a (sync)               |

Any sync slot (`extract`, `extractAlternate`, `render`, `summarize`, `batchFallback`) may also
carry a nested `fallback: { provider, model }` — an on-failure secondary tried when the primary
call fails (usage limit, expired login, bad model, exhausted-retry timeout). Sync-only, may chain
(depth ≤ 3), and independent of the A/B `extractAlternate` and the backpressure `batchFallback`.

Validation rules enforced at config load:

- `provider: openrouter` is rejected on `batch` / `extract.batch=true` /
  `extractAlternate.batch=true` (OpenRouter has no compatible Batch API).
- `provider: anthropic` requires the top-level `anthropic:` block.
- `provider: openrouter` requires the top-level `openrouter:` block.
- `extractAlternate` without `extract` is rejected — it is the "B" side of an A/B pair.
- A `fallback` must differ from its slot's primary and from every other link in the chain
  (cycles rejected), may not exceed depth 3, and may not set `batch: true`.
- `fallback` is rejected on the `batch` slot and on a `batch: true` extract/extractAlternate
  leg — the async Batch API path has no sync fallback.
- `summarize: openrouter` / `summarize: anthropic` on a feed requires the matching
  top-level block.
- Every `pricing[]` entry must declare non-negative input/output.

---

## Batch API, backpressure, three-tier fallback

Step 2 and (optionally) Step 1 can run as async batches at 50 % of the sync price. Both
[OpenAI `/v1/batches`](src/main/kotlin/metifikys/ai/OpenAIBatch.kt) and
[Anthropic `/v1/messages/batches`](src/main/kotlin/metifikys/ai/AnthropicBatch.kt) are
implemented natively.

**Why backpressure?** A stuck OpenAI batch endpoint would otherwise block every cycle: each
new cycle adds another pending batch, work piles up, and digests stop flowing. So the
scheduler reads the per-category pending count and routes accordingly:

```
pending <  primaryMaxPending                            → primary batch (llm.batch)
primaryMaxPending ≤ pending < primary + secondary       → llm.batchFallback (sync alt LLM)
pending ≥ primary + secondary                            → sync render (llm.render)
```

When the category has no `batchFallback` configured, the secondary band collapses and the
sync cutoff drops to `primaryMaxPending` — preserving the original two-tier behaviour.

Set `primaryMaxPending: 0` to disable the primary render Batch API entirely: no primary
batch is ever submitted, so every render goes synchronously — via `llm.batchFallback` when
the category configures one, otherwise via the sync render client. (The Step-1 extract batch
is governed separately by whether the extract endpoint is batch-capable, not by this knob.)

Defaults from `config.example.yaml`:

- `primaryMaxPending: 2` (`0` disables batch)
- `secondaryMaxPending: 1`

Additional protections:

- **Volume gate** — when batching is already stuck, the cycle is skipped entirely for a
  category if `newArticles < 2 × processing.minArticles`. Tiny cycles don't justify
  burning the sync quota.
- **Sync-fallback cap** — synchronous render submissions are capped at 60 articles
  (`SYNC_FALLBACK_CAP` in `CategoryProcessor`).
- **Restart resume** — pending batches persist in `pending_batches` with their kind
  (`render` / `extract`), category, article links, and serialized shortlist JSON. On
  startup, `DigestCycle.resumePendingBatches()` polls each batch to completion and routes
  the result through the same code path as a fresh batch result.

---

## Article lifecycle & status state machine

```
                              ┌────────────────────────────┐
                              │      UNPROCESSED           │
                              │  (just inserted, or        │
                              │   reverted after failure)  │
                              └──────────────┬─────────────┘
                                             │
                       Step 1/2 submission   │
                                             ▼
                              ┌────────────────────────────┐
   stale > processing         │      PROCESSING            │
   .staleTimeoutHours         │  (claim with timestamp)    │
   ◄─────────────────────────┤                            │
   reclaimed                  └──────────────┬─────────────┘
                                             │
                       Telegram send OK      │  hard-filter REJECT
                                             ▼  (only against PROCESSED neighbour)
                              ┌────────────────────────────┐
                              │       PROCESSED            │  ─►  DUPLICATE
                              │  (terminal)                │      (duplicate_of set)
                              └────────────────────────────┘
```

- `markProcessing` does **not** downgrade `PROCESSED` rows (idempotent).
- `markUnprocessed` and `markDuplicate` similarly refuse to demote `PROCESSED`/`PROCESSING`.
- Stale `PROCESSING` rows older than `processing.staleTimeoutHours` are reclaimed by
  `fetchReadyForDigestByCategory` for the next cycle — protects against crashes that left
  rows orphaned mid-flight.

---

## Telegram delivery & formatting

`TopicFormatter.applyStrictLayout` enforces a strict three-block topic shape:

```
{emoji} **{headline}.**

{body}

[{label}]({url})
```

When the LLM output doesn't match the canonical shape, a fallback regex isolates the
trailing `[label](url)` and bolts on the layout, so degenerate output is never made worse.
The `[джерело](...)` placeholder is replaced with the actual article title (truncated to 80
chars with ellipsis).

**Sending rules:**

- Per-topic: try Markdown parse mode first → on failure, strip markdown and try again.
- **Photo-and-caption mode** (`enableImages: true`): the first matched article's `imageUrl`
  is used as the photo; caption is the topic text truncated to Telegram's 1024-char
  hard limit. Bullets without a usable image fall back to plain text.
- 4096-char chunking for plain text; redirects are disabled to prevent bot-token leakage.

**Partial-send reconciliation:**

- All topics sent → mark all linked articles `PROCESSED`, persist the filtered summary +
  the covered events from the shortlist.
- Some topics sent, some failed → mark links from successful topics `PROCESSED`, links
  exclusively in failed topics `UNPROCESSED` (success wins on overlap). Articles not
  referenced by any topic are treated as orphans and also marked `PROCESSED` (otherwise
  they'd loop forever).
- All topics failed → revert everything to `UNPROCESSED` for retry next cycle.

---

## Cost telemetry & the `/status` snapshot

[`MeteredLlmClient`](src/main/kotlin/metifikys/ai/MeteredLlmClient.kt) wraps every sync and
batch LLM call. It:

1. Reads token counts from the provider's usage block (estimated when absent).
2. Looks up the per-million-token price in `AppConfig.pricing[]`.
3. **Halves the cost for Batch API calls** automatically (`LlmPricing.priceFor(... batch =
   true)`).
4. Writes one row to `llm_calls(provider, model, category, useCase, promptTokens,
   completionTokens, estCostUsd, ts)`.

The `/status` admin snapshot ([`StatusCommand`](src/main/kotlin/metifikys/telegram/StatusCommand.kt))
emits:

```
📊 Status — 2026-05-28 12:34

tech 💻
  last digest: 12:01 (33 min)
  ready for next batch: 14
  pending: batch_abc123 started 11:58

gaming 🎮
  last digest: 11:45 (49 min)
  ready for next batch: 0
  pending: msgbatch_zzz started 11:46

💰 LLM cost (24h / 7d)
  total: $0.42 / $3.61
  openai · tech: $0.10 / $0.71 (32.4k in / 3.2k out, 12 calls)
  anthropic · gaming: $0.08 / $0.55 (18.9k in / 2.1k out, 7 calls)

⚠️ Recent errors
  12:11 · politics · [Dedup] · timeout after 60s
```

When `admin.statusChatId` is set, [`StatusPoster`](src/main/kotlin/metifikys/telegram/StatusPoster.kt)
posts this snapshot at the end of every cycle, **deleting the previous status message
first** so the admin chat always shows exactly one current snapshot. The `errorCommitToken`
mechanism ensures errors are cleared from `CycleErrorLog` only after they've successfully
been posted.

---

## Security & hardening

| Hardening                       | Where                                                          |
|---------------------------------|----------------------------------------------------------------|
| SSRF guard on RSS URLs          | `RssFetcher.validateFeedUrl`                                   |
| No-redirect HTTP to Telegram    | `TelegramSender` OkHttpClient builder                          |
| Path-traversal block on DB path | `ConfigLoader.applyEnvOverrides` (`..` rejected)               |
| `isAllowDoctypes = false`       | `RssFetcher` SyndFeedInput — blocks XXE                        |
| URL whitelist on LLM output     | `DigestDeliverer` foreign-URL filter                           |
| URL hallucination guard         | `EventExtractor.postProcess` Step 1 URL filter                  |
| Stripped markdown fallback      | `TelegramSender.sendToChannel` — never leaks raw markdown      |
| Bot token never logged          | `Anthropic*`, `OpenAI*` clients log model+baseUrl, not auth    |
| Schema validation               | `ConfigLoader` rejects misconfigurations at startup            |
| Dependency lock                 | `gradle.lockfile` + Gradle dep verification                    |

---

## Setup

### 1. Clone

```bash
git clone https://github.com/Metifikys/RssNewsBot.git
cd RssNewsBot
```

### 2. Prerequisites

- JDK 17+
- Gradle 8.x (the wrapper is not committed — see [Build](#3-build) below)
- Telegram bot from [@BotFather](https://t.me/BotFather)
- OpenAI API key (required)
- Optional: Anthropic and/or OpenRouter API keys

### 3. Configure

```bash
cp config.example.yaml config.yaml
```

Edit `config.yaml` with your tokens and feeds, or set the sensitive values as environment
variables (they override the file):

| Env var               | Required | Purpose                              |
|-----------------------|----------|--------------------------------------|
| `TELEGRAM_BOT_TOKEN`  | yes      | Telegram bot token                   |
| `OPENAI_API_KEY`      | yes      | OpenAI API key                       |
| `ANTHROPIC_API_KEY`   | no       | Used only if `anthropic:` block set  |
| `OPENROUTER_API_KEY`  | no       | Used only if `openrouter:` block set |

`config.example.yaml` documents every option inline.

### 4. Build

The Gradle wrapper is not checked in. First time, generate it (one-off):

```bash
gradle wrapper --gradle-version 8.10
```

Thereafter:

```bash
./gradlew shadowJar
```

Produces `build/libs/RssNewsBot-1.0-SNAPSHOT.jar` (fat JAR with all dependencies).

### 5. Run

```bash
java -jar build/libs/RssNewsBot-1.0-SNAPSHOT.jar
# or with a custom config path:
java -jar build/libs/RssNewsBot-1.0-SNAPSHOT.jar /path/to/config.yaml
```

The first cycle runs immediately on startup; subsequent cycles fire every
`scheduler.intervalMinutes`. Pending batches from a previous run are resumed before the
first cycle.

---

## Configuration reference

### Top-level blocks

| Block             | Required | Notes                                                       |
|-------------------|----------|-------------------------------------------------------------|
| `telegram`        | yes      | Bot token                                                   |
| `openai`          | yes      | API key + default sync/batch model                          |
| `openrouter`      | no       | When present, enables OpenRouter as alt sync + Step 1 "B"   |
| `anthropic`       | no       | When present, enables Anthropic for any LLM use case        |
| `database`        | yes      | SQLite file path (no `..`, must be readable)                |
| `scheduler`       | yes      | `intervalMinutes`                                           |
| `processing`      | no       | Stale timeout, min articles, batch thresholds               |
| `summaryHistory`  | no       | How many previous digests to inject as context, retention   |
| `admin`           | no       | `statusChatId` enables the `/status` poster                 |
| `pricing`         | no       | USD/1M tokens per (provider, model)                          |
| `categories`      | yes      | Map of category name → `CategoryConfig`                     |

### `processing` knobs (all optional)

| Field                  | Default | Purpose                                          |
|------------------------|---------|--------------------------------------------------|
| `staleTimeoutHours`    | 3       | Reclaim `PROCESSING` rows older than this        |
| `minArticles`          | 8       | Skip a category with fewer than N new articles   |
| `primaryMaxPending`    | 2       | Promote to `batchFallback` at this many pending; `0` disables primary batch |
| `secondaryMaxPending`  | 1       | Drop to sync render at primary + secondary       |

### `CategoryConfig`

```yaml
emoji: "💻"
channelId: "@my_channel" # or "-1001234567890"
enableImages: false       # photo+caption mode
feeds:                    # plain string OR object form
  - https://example.com/feed
  - url: https://other.com/feed
    fetchFullContent: true
    summarize: openai     # openai | openrouter | anthropic

systemPrompt: …           # legacy single-step prompt
userPrompt: …
summarizePrompt: …        # for feeds with summarize: set

llm:                      # per-use-case provider+model overrides
  extract:          { provider: …, model: …, batch: true|false }
  extractAlternate: { provider: …, model: …, batch: true|false }
  render:           { provider: …, model: …, fallback: { provider: …, model: … } }  # nested on-failure fallback (sync slots)
  batch:            { provider: …, model: … }    # openai|anthropic only
  summarize:        { provider: …, model: … }
  batchFallback:    { provider: …, model: … }    # any provider

dedup:                    # two-step pipeline opt-in
  promptFile: prompts/X.yaml
  prompts:                # inline override of any prompt
    extractSystem: …
    extractUser: …
    renderSystem: …
    renderUser: …
  contextDays: 7
  maxContextEvents: 200
  digest:                 # editorial ranker
    ranker:
      enabled: true
      newsworthinessWeight: 0.6
    minStrongItems: 4
    maxDigestItems: 6
    maxWaitHours: 4
    minItemsOnForcePublish: 2
    maxPerFranchise: 2
    maxPerSubject: 1
    minDigestFit: 3
    newsworthinessOverride: 8
    meaningfulUpdateCooldownMinutes: 90

semanticDedup:            # embedding detector opt-in
  enabled: true
  model: text-embedding-3-small
  windowDays: 14
  threshold: 0.92         # cosine ≥ this → [HIT] log
  topK: 5
  maxRecent: 2000
  hardThreshold: 0.85     # ≥ this AND neighbour PROCESSED → mark DUPLICATE
  eventEnabled: true      # Layer 3.5 event-level analyzer (log-only)
  eventThreshold: 0.92    # cosine ≥ this → [EventSemanticDedup][HIT] log
```

---

## Build, run, tests

```bash
./gradlew shadowJar            # fat JAR
./gradlew test                 # JUnit 5 + MockK
./gradlew jacocoTestReport     # coverage at build/reports/jacoco/
./gradlew dependencies --write-locks   # refresh gradle.lockfile
```

The test suite covers AI clients (OpenAI/Anthropic + their Batch APIs), config parsing &
validation, the database layer, semantic dedup (`SemanticDedupDetectorTest.kt` exercises hit
/ reject thresholds, hard-filter status gating, candidate scoring; `EventSemanticAnalyzerTest.kt`
covers the log-only event-level pass), vector math, RSS
fetching with SSRF rejection, Telegram sender, and end-to-end cycle behaviour with mocked
providers.

---

## Database schema

| Table                | Purpose                                                                  |
|----------------------|---------------------------------------------------------------------------|
| `articles`           | Canonical article rows + status (`UNPROCESSED`/`PROCESSING`/`PROCESSED`/`DUPLICATE`) + `duplicate_of` pointer + image/summary cache |
| `pending_batches`    | In-flight batch jobs (`kind`, `category_names`, `article_links`, `shortlist_json`) for restart-safe resume |
| `summaries`          | Delivered digest text + article count; injected as context next cycle and used for previous-URL filtering |
| `covered_events`     | Step 1 dedup memory: one row per `(category, event_key)`; upsert keeps subject/franchise/eventType stable |
| `rejected_events`    | Step 1's rejected/duplicate extractions kept 30 days for prompt-tuning retrospectives |
| `llm_calls`          | Per-call token + USD ledger feeding the `/status` cost panel             |
| `article_embeddings` | One vector per article (Float32 little-endian blob, `dim * 4` bytes)     |
| `event_embeddings`   | One vector per `(category, event_key)` for the log-only event-level analyzer (Float32 LE blob) |

Pruning runs at the tail of every cycle:

- `deleteOlderThan(1500)` — articles older than 1500 days (long retention for dedup pointers).
- `deleteOldSummaries(retentionDays)` and `pruneOldCoveredEvents(retentionDays)` —
  `summaryHistory.retentionDays`.
- `deleteOldRejectedEvents(30)` — rejected events log.
- `deleteOldBatches(2)` — completed/failed batch records.

---

## Project layout

```
src/main/kotlin/metifikys/
├── ai/             OpenAI / Anthropic / OpenRouter clients, batch APIs,
│                   embedder, metered LLM wrapper, pricing, prompt builder
│   └── dedup/      Event extractor, shortlist ranker, prompt loader
├── config/         YAML loader (Jackson), schema validation, env overrides
├── db/             NewsDatabase (SQLite + Exposed): articles, batches,
│                   summaries, covered/rejected events, llm_calls, embeddings
├── digest/         CategoryProcessor, DigestCycle, SemanticDedupDetector,
│                   DigestDeliverer, VectorMath, CycleErrorLog
├── fetch/          RssFetcher (Rome + SSRF), ArticleFetcher (Jsoup),
│                   ArticleSummarizer (per-article LLM)
├── format/         TopicFormatter (Telegram message formatting)
├── model/          Article, ArticleStatus, CategoryInput, Dedup DTOs
├── telegram/       TelegramSender, StatusCommand, StatusPoster
├── Main.kt         Entry point (reads config path, builds NewsBot)
└── NewsBot.kt      Composition root + scheduler loop
```

---

## Dependencies

| Library                  | Version       | Purpose                                     |
|--------------------------|---------------|---------------------------------------------|
| Kotlin (JVM)             | 2.0.21        | Language + serialization plugin             |
| JVM toolchain            | 17            | `kotlin { jvmToolchain(17) }`               |
| OkHttp                   | 4.12.0        | HTTP client (Telegram, OpenAI, Anthropic)   |
| Jsoup                    | 1.18.1        | HTML parsing for full-article extraction    |
| Rome + rome-modules      | 1.15.0        | RSS/Atom feed parsing (+ Media RSS)         |
| Exposed                  | 0.56.0        | SQLite ORM (core, dao, jdbc, java-time)     |
| sqlite-jdbc              | 3.41.2.2      | SQLite driver                               |
| kotlinx-serialization    | 1.6.3         | JSON for LLM and Telegram request bodies    |
| Jackson YAML + Kotlin    | 2.16.1        | Config file parsing                         |
| Logback + kotlin-logging | 1.5.6 / 7.0.3 | Structured logging                          |
| JUnit Jupiter            | 5.10.2        | Test runner                                 |
| MockK                    | 1.13.10       | Kotlin-friendly mocking                     |
| Shadow plugin            | 8.3.6         | Fat-JAR packaging                           |
| JaCoCo                   | 0.8.11        | Coverage reports                            |
