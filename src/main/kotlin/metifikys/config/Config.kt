package metifikys.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.TextNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Parses [WeeklyConfig.dayOfWeek] into a [DayOfWeek] (case-insensitive). Throws on a bad value. */
fun WeeklyConfig.dayOfWeekParsed(): DayOfWeek = DayOfWeek.valueOf(dayOfWeek.trim().uppercase())

/** Parses [WeeklyConfig.time] ("HH:mm" or "HH:mm:ss") into a [LocalTime]. Throws on a bad value. */
fun WeeklyConfig.timeParsed(): LocalTime = LocalTime.parse(time.trim())

data class SummaryHistoryConfig(
    val maxCount: Int = 2,
    val retentionDays: Long = 14
)

data class ProcessingConfig(
    val staleTimeoutHours: Long = 3,
    val minArticles: Int = 8,
    /**
     * Maximum pending batches the primary batch provider tolerates before routing
     * the next cycle to `llm.batchFallback`. At pending >= this, the next cycle
     * promotes to the second tier (or directly to sync render when no fallback
     * is configured for the category).
     *
     * Set to `0` to disable the primary render Batch API entirely: every render goes
     * synchronously (via `llm.batchFallback` when the category configures one, otherwise
     * via the sync render client). The Step-1 extract batch is unaffected — disable that
     * by pointing the extract LLM at a non-batch endpoint.
     */
    val primaryMaxPending: Int = 2,
    /**
     * Additional pending tolerated on the second tier (the `llm.batchFallback`
     * sync call) before falling all the way through to the synchronous render
     * client. Effective sync cutoff is `primaryMaxPending + secondaryMaxPending`
     * when a category configures `batchFallback`, otherwise `primaryMaxPending`.
     */
    val secondaryMaxPending: Int = 1,
    /**
     * Cycle mode only (`scheduler.perCategory=false`); in per-category mode every category has
     * its own thread and this is ignored.
     *
     * Upper bound on how many categories are processed concurrently within a single digest
     * cycle. Each cycle the per-category worker pool is sized to `min(readyCategories, this)`,
     * so the default gives every ready category its own worker — full isolation, so one slow or
     * stuck category (e.g. a hanging sync LLM call) never blocks the others. Lower it to throttle
     * how many simultaneous LLM/provider requests a cycle may fan out. Must be >= 1.
     */
    val maxConcurrentCategories: Int = 12,
    /**
     * Cycle mode only (`scheduler.perCategory=false`); ignored in per-category mode, where each
     * stage is bounded on its own (fetch deadline, HTTP timeouts, CLI force-kill, fail-fast
     * on a CLI timeout) and a run that outlasts its interval just delays the next one.
     *
     * Optional hard ceiling (minutes) on one cycle's category fan-out. `0` (default) means NO
     * ceiling: the cycle simply ends when the slowest category finishes — categories still run
     * in parallel, and every stage is internally bounded anyway (fetch deadline, HTTP timeouts,
     * CLI force-kill, bounded retries), so nothing can hang forever. Digest work is never
     * cancelled mid-flight in this mode.
     *
     * A positive value restores the old barrier: pipelines still running that many minutes
     * after the fetch budget are interrupted and their articles revert / are reclaimed next
     * cycle. If set, it must comfortably exceed the worst-case chain of inner LLM timeouts
     * (e.g. several Step-1 + render calls of up to `claudeCli.timeoutSeconds` each, times
     * their retries) — otherwise the barrier repeatedly cancels healthy workers, no digest
     * ever completes, and the queue only grows. Load-time validation warns about that.
     */
    val categoryDeadlineMinutes: Long = 0,
    /**
     * How many days of article rows to keep. Each cycle deletes articles whose `pubDate` is older
     * than this. Replaces the old hard-coded `deleteOlderThan(1500)`. Validated at load time to be
     * >= max(weekly.lookbackDays, every category's semanticDedup.windowDays) so the retention
     * window never truncates data the weekly roundup or the dedup detector still needs.
     */
    val articleRetentionDays: Long = 1500,
    /**
     * How many days of `llm_calls` rows to keep (the `/status` cost/token ledger). Pruned each
     * cycle. These accumulate one row per LLM request and are the dominant DB-growth source, so
     * the default is deliberately short. Must be >= 1.
     */
    val llmCallRetentionDays: Long = 30,
    /**
     * How many days of article-embedding rows to keep. Pruned each cycle. Must be >= every
     * category's semanticDedup.windowDays (validated at load) — the detector scans back that far.
     */
    val embeddingRetentionDays: Long = 30
)

data class AdminConfig(
    /**
     * Chat where the rolling status snapshot is posted at the end of each digest cycle.
     * On every post the previous status message is deleted, so this chat shows only one
     * message at a time. Null disables status posting.
     */
    val statusChatId: String? = null
)

/**
 * Optional weekly "top story of the week" roundup. When `enabled`, a dedicated scheduler
 * thread fires once per week at [dayOfWeek] + [time] (the bot's local zone) and, for each
 * participating category, ranks the week's covered events by how many times each story
 * was seen (the canonical coverage plus every later duplicate detection — the "most
 * duplicates" signal), merges near-duplicate phrasings via embeddings, hands the top
 * candidates to the render LLM, and posts the resulting roundup to the category's own
 * channel. Independent of the per-cycle digest scheduler.
 */
data class WeeklyConfig(
    /** Master switch. When false the weekly scheduler is never started. */
    val enabled: Boolean = false,
    /** Day of week to post, e.g. "SUNDAY" / "monday". Case-insensitive; validated at load. */
    val dayOfWeek: String = "SUNDAY",
    /** Local time-of-day "HH:mm" (or "HH:mm:ss") in the bot's system zone. Validated at load. */
    val time: String = "18:00",
    /** Lookback window (days) over which the week's events are gathered. */
    val lookbackDays: Long = 7,
    /** How many top stories to feature in the roundup. */
    val topN: Int = 5,
    /** How many top-by-mention clusters are handed to the LLM as candidates. Must be >= [topN]. */
    val candidatePoolSize: Int = 15,
    /**
     * Candidate pool for the article-level fallback (categories with no `dedup:` block, ranked by
     * raw articles instead of events). Larger than [candidatePoolSize] on purpose: a low-duplication
     * (e.g. single-source) category has little repeat signal, so a wider pool lets the LLM pick the
     * most significant stories from a broad weekly sample rather than just the newest few. Must be >= [topN].
     */
    val articleCandidatePoolSize: Int = 40,
    /** Minimum mention count (canonical + duplicate detections) for a story to qualify as a candidate. */
    val minMentions: Int = 2,
    /** Cosine threshold for merging near-duplicate events (different phrasings) into one story cluster. */
    val clusterThreshold: Double = 0.83,
    /** Embedding model id used for the clustering pass. */
    val embeddingModel: String = "text-embedding-3-small",
    /** Cap on event OR article rows scanned per category per run (bounds the embed + brute-force scan). */
    val maxEvents: Int = 1000,
    /**
     * Optional hashtag appended on its own line at the very end of the weekly post, e.g. "#головне".
     * Null/blank = no hashtag. Written verbatim — include the leading `#` yourself.
     */
    val hashtag: String? = null,
    /**
     * When true, each bullet gets a compact "🔁 N" badge showing how many times that story was
     * seen this week (its mention count — the "most duplicates" signal). The count is matched to
     * the bullet deterministically by its source URL, so the LLM can't get it wrong.
     */
    val showMentions: Boolean = true,
    /**
     * Categories that participate. Empty = every category that has a `dedup:` block (only those
     * accumulate the `covered_events` / `rejected_events` the ranker needs). Names must exist.
     */
    val categories: List<String> = emptyList(),
    /** Path (relative to CWD) to a YAML file with `weekly.system` / `weekly.user` keys. */
    val promptFile: String? = null,
    /** Inline prompt overrides; any non-null field beats the file and the built-in default. */
    val prompts: WeeklyPromptsInline? = null
)

data class WeeklyPromptsInline(
    val system: String? = null,
    val user: String? = null
)

data class AppConfig(
    val telegram: TelegramConfig,
    val openai: OpenAIConfig,
    val openrouter: OpenRouterConfig? = null,
    val anthropic: AnthropicConfig? = null,
    val claudeCli: ClaudeCliConfig? = null,
    val codexCli: CodexCliConfig? = null,
    val database: DatabaseConfig,
    val scheduler: SchedulerConfig,
    val fetcher: FetcherConfig = FetcherConfig(),
    val categories: Map<String, CategoryConfig>,
    val summaryHistory: SummaryHistoryConfig = SummaryHistoryConfig(),
    val processing: ProcessingConfig = ProcessingConfig(),
    val admin: AdminConfig = AdminConfig(),
    val weekly: WeeklyConfig? = null,
    val feedback: FeedbackConfig = FeedbackConfig(),
    val preferences: PreferencesConfig = PreferencesConfig(),
    /**
     * Per-(provider, model) prices used by `/status` cost stats. Costs are in USD per
     * 1,000,000 tokens (matches how OpenAI / Anthropic publish their pricing pages).
     * Entries not listed here are billed as $0 — calls are still recorded with token
     * counts, the cost column just shows zero. Empty by default so the bot runs
     * without any pricing knowledge. Batch-API calls are auto-halved by
     * [metifikys.ai.LlmPricing].
     */
    val pricing: List<LlmPriceEntry> = emptyList()
)

/**
 * Manual, editor-controlled content preferences — the deterministic counterpart to the
 * learned reaction-affinity loop ([FeedbackConfig]). Where affinity is a soft, clamped
 * tiebreaker that never overrides newsworthiness, these are hard rules the operator sets by
 * hand in YAML.
 *
 * Currently only [mute]: a keyword blocklist applied BEFORE Step-1, so muted articles never
 * reach the LLM at all (saving tokens) and never appear in a digest. See
 * [metifikys.digest.TopicMuteFilter] for the matching semantics (whole-word, case-insensitive).
 */
data class PreferencesConfig(
    /**
     * Keywords/phrases whose articles are dropped outright. Matched whole-word and
     * case-insensitively against each article's title and description, e.g. `call of duty`,
     * `warzone`, `cod`. Empty (default) = nothing is muted.
     */
    val mute: List<String> = emptyList()
)

/**
 * Reaction-feedback loop (see `.claude/plans/reaction-feedback-design.md`). Phase 1 only
 * *aggregates* the reactions the updates poller already collects into per-dimension
 * audience-affinity scores (franchise / eventType / subject / source) — it changes nothing
 * about selection or rendering. Later phases read those scores from the ranker, the
 * Step-1 prompt, and the render-depth gate.
 *
 * Aggregation is a cheap in-process pass over `digest_messages` × `reaction_counts` ×
 * `covered_events`; it reruns at the start of a digest cycle whenever the previous run is
 * older than [recomputeHours] (state in `bot_state`).
 */
data class FeedbackConfig(
    /** Master switch for the whole feedback module. When false nothing is computed or stored. */
    val enabled: Boolean = false,
    /**
     * Posts younger than this never enter the aggregation — reactions accumulate for a day
     * or two, so learning from a fresh post would bake in an immature signal.
     */
    val attributionHours: Long = 36,
    /** Lookback window (days) over which posts are aggregated at all. */
    val lookbackDays: Long = 90,
    /** Exponential time-decay half-life (days) applied to each post's contribution. */
    val halflifeDays: Long = 45,
    /** Dimension keys with fewer (decay-weighted) samples than this get no stored row. */
    val minSamples: Double = 3.0,
    /** Empirical-Bayes pseudo-count `k`: scores shrink toward the category prior by `k/(n+k)`. */
    val shrinkageK: Double = 5.0,
    /**
     * A message's tone only counts once it carries at least this many total reactions.
     * Default 1: these channels have a single reader-operator whose lone 👍/👎 IS the
     * signal — production data showed 94% of reacted posts carry exactly one reaction,
     * so a higher floor silently zeroes every sentiment. Raise it for channels with a
     * real audience, where one stray reaction shouldn't set the tone.
     */
    val minVolume: Int = 1,
    /** Recompute when the last run is older than this many hours. */
    val recomputeHours: Long = 12,
    /**
     * Per-emoji valence overrides merged over the built-in defaults
     * (see `metifikys.feedback.ReactionValence`). Keys are the stored emoji strings
     * (`custom:<id>` and `paid` included); values must lie in [-1.0, 1.0].
     */
    val valence: Map<String, Double> = emptyMap()
)

/**
 * One row of the cost catalog. `provider` matches the tag emitted by
 * `MeteredLlmClient.providerKeyOf` ("openai" / "openrouter" / "anthropic").
 * `input` and `output` are USD per 1,000,000 tokens.
 */
data class LlmPriceEntry(
    val provider: String,
    val model: String,
    val input: Double,
    val output: Double
)

data class TelegramConfig(
    val botToken: String,
    /**
     * When true, start the `getUpdates` long-poll loop that records channel-post reactions
     * ([metifikys.telegram.TelegramUpdatesPoller]). Off by default: Telegram permits only one
     * `getUpdates` consumer per bot token, so a dev instance sharing the production token must
     * leave this false to avoid a 409 conflict with the running server.
     */
    val updatesPolling: Boolean = false
)

data class OpenAIConfig(
    val apiKey: String,
    val model: String = "gpt-5-mini-2025-08-07",
    val batchModel: String = "gpt-5-mini-2025-08-07"
)

/**
 * Optional OpenRouter provider.
 * When this block is present, generic sync summarize/render paths use OpenRouter.
 * Step 1 event extraction also uses it as the alternate endpoint for every second request,
 * while the Batch API path always stays on OpenAI (OpenRouter doesn't expose a compatible
 * Batch API).
 *
 * Model selection: either a single [model], or a priority-ordered [models] list. When
 * [models] is set it REPLACES [model]: every sync call tries the first entry and walks
 * down the list on failure (provider error, exhausted retries) via
 * [metifikys.ai.FallbackLlmClient]. Free-tier endpoints come and go by the hour, so a
 * dead first choice must not take summarize/render down with it. A per-category
 * `llm.*` override that pins provider+model bypasses the ladder deliberately.
 */
data class OpenRouterConfig(
    val apiKey: String,
    val model: String = "",
    val models: List<String> = emptyList(),
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val httpReferer: String? = null,
    val xTitle: String? = null
) {
    /** Priority-ordered candidate models: [models] when set, else the single [model]. */
    val modelPriority: List<String>
        get() = if (models.isNotEmpty()) models else listOf(model)

    companion object {
        /**
         * Cap on the [models] ladder — matches the explicit fallback-chain depth cap
         * (primary + 3): by the fourth dead endpoint the cycle is better off failing fast.
         */
        const val MAX_MODELS = 4
    }
}

/**
 * Optional Anthropic provider.
 * When this block is present, categories may opt into Anthropic for any LLM use case
 * (extract / render / batch / summarize) via [CategoryLlmOverrides], and feeds may
 * declare `summarize: anthropic`. The Batch API is the native Anthropic Message Batches
 * endpoint (`/v1/messages/batches`), so unlike OpenRouter, batch overrides ARE supported.
 */
data class AnthropicConfig(
    val apiKey: String,
    val model: String,
    val batchModel: String = model,
    val baseUrl: String = "https://api.anthropic.com/v1",
    val anthropicVersion: String = "2023-06-01",
    val maxTokens: Int = 4096
)

/**
 * Optional Claude CLI provider. When this block is present, categories may opt into
 * the local `claude -p` CLI (Claude Code print mode) for any sync LLM use case
 * (extract / extractAlternate / render / summarize / batchFallback) via
 * [CategoryLlmOverrides], and feeds may declare `summarize: claudecli`. There is no
 * `apiKey` — the subprocess authenticates with the machine's existing `claude` login.
 * The Batch API is NOT supported (a CLI has no batch endpoint), so `llm.batch:
 * claudecli` is rejected at load time, exactly like OpenRouter.
 */
data class ClaudeCliConfig(
    /** Executable name or path; must be on PATH (or absolute). */
    val command: String = "claude",
    /** Model id passed via `--model`. Blank ⇒ let the CLI pick its default model. */
    val model: String = "",
    /** Hard ceiling for a single invocation before the process is force-killed. */
    val timeoutSeconds: Long = 300
)

/**
 * Optional Codex CLI provider. When this block is present, categories may opt into
 * the local `codex exec` CLI (OpenAI Codex non-interactive mode) for any sync LLM use
 * case (extract / extractAlternate / render / summarize / batchFallback) via
 * [CategoryLlmOverrides], and feeds may declare `summarize: codexcli`. There is no
 * `apiKey` — the subprocess authenticates with the machine's existing `codex` login.
 * The Batch API is NOT supported (a CLI has no batch endpoint), so `llm.batch:
 * codexcli` is rejected at load time, exactly like the Claude CLI and OpenRouter.
 */
data class CodexCliConfig(
    /** Executable name or path; must be on PATH (or absolute). */
    val command: String = "codex",
    /** Model id passed via `--model`. Blank ⇒ let the CLI pick its default model. */
    val model: String = "",
    /** Hard ceiling for a single invocation before the process is force-killed. */
    val timeoutSeconds: Long = 300
)

data class DatabaseConfig(
    val path: String
)

data class SchedulerConfig(
    /**
     * Default digest cadence. In per-category mode ([perCategory] = true) it is the interval of
     * every category that does not set its own [CategoryConfig.intervalMinutes], and also the
     * cadence of the shared maintenance tick (retention cleanup, affinity rebuild, status post).
     * In cycle mode it is the period of the single global cycle.
     */
    val intervalMinutes: Long,
    /**
     * `true` (default): every category runs on its own scheduler thread at its own interval —
     * a slow or stuck category never delays the others, and there is no cycle barrier to tune.
     * `false`: the legacy single global cycle that fans the categories out and waits for the
     * slowest one (`processing.categoryDeadlineMinutes` / `maxConcurrentCategories` apply only here).
     */
    val perCategory: Boolean = true
)

/**
 * RSS fetch-stage tuning. The fetcher runs feeds through a shared bounded pool: one HTTP
 * attempt per pass, retryable failures re-enter the queue after a delay instead of
 * sleeping in place, and the whole stage is capped by a wall-clock deadline. A feed that
 * still fails when the deadline hits is skipped for this cycle — link-dedup picks its
 * articles up next cycle, so the cost of a dead feed is delay, never data loss.
 */
data class FetcherConfig(
    /** Max simultaneous HTTP requests across ALL categories (feeds usually share one RSSHub host). */
    val maxConcurrentFetches: Int = 3,
    /** Total attempts per feed per cycle (first try + requeued retries). */
    val maxAttempts: Int = 3,
    /** Fallback delay before a retryable feed re-enters the queue; a server Retry-After wins. */
    val retryDelaySeconds: Long = 15,
    /** Wall-clock budget for one cycle's whole fetch stage; stragglers are deferred to the next cycle. */
    val fetchDeadlineSeconds: Long = 240,
    /**
     * Per-category mode only. When set, ingestion (RSS fetch → link-dedup → enrich → per-article
     * summarize → insert → embedding dedup) runs on its own timer at this cadence for every
     * category, and the category's digest run does only Step 1 + Step 2 on whatever is already
     * in the DB. Null (default) keeps ingestion inside the digest run, as before. Override per
     * category with `categories.<name>.fetchIntervalMinutes`. Decoupling lets an evening burst
     * of articles be enriched and summarized as it arrives instead of in front of the digest.
     */
    val intervalMinutes: Long? = null
)

/**
 * Deserializes a [FeedConfig] from either a plain string (URL only) or an object
 * with `url` and optional `fetchFullContent` / `summarize` fields.
 *
 * Plain string:   `- https://example.com/feed`          → FeedConfig(url=..., fetchFullContent=false)
 * Object form:    `- url: https://example.com/feed`     → FeedConfig(url=..., fetchFullContent=true)
 *                   `  fetchFullContent: true`
 *                   `  summarize: openai`
 */
class FeedConfigDeserializer : JsonDeserializer<FeedConfig>() {
    override fun deserialize(p: JsonParser, ctx: DeserializationContext): FeedConfig {
        val node = p.codec.readTree<com.fasterxml.jackson.databind.JsonNode>(p)
        if (node == null || node.isNull) {
            throw IllegalArgumentException("Empty feed entry in config (a list item with no value) — every feed must be a URL string or an object with a 'url' field")
        }
        return if (node is TextNode) {
            val url = node.textValue()
            require(!url.isNullOrBlank()) { "Empty feed URL in config" }
            FeedConfig(url = url)
        } else {
            val summarize = node.get("summarize")?.asText()?.takeIf { it.isNotBlank() }
            require(summarize == null || summarize in FeedConfig.SUMMARIZE_PROVIDERS) {
                "Invalid 'summarize' value '$summarize' — must be one of ${FeedConfig.SUMMARIZE_PROVIDERS}"
            }
            FeedConfig(
                url = node.get("url")?.asText()?.takeIf { it.isNotBlank() }
                    ?: error("FeedConfig missing 'url' field"),
                fetchFullContent = node.get("fetchFullContent")?.asBoolean() ?: false,
                summarize = summarize
            )
        }
    }

    override fun getNullValue(ctx: DeserializationContext): FeedConfig {
        throw IllegalArgumentException("Empty feed entry in config (a list item with no value) — every feed must be a URL string or an object with a 'url' field")
    }
}

@JsonDeserialize(using = FeedConfigDeserializer::class)
data class FeedConfig(
    val url: String,
    val fetchFullContent: Boolean = false,
    /** When set, each article from this feed is summarized via the named provider before DB insert. */
    val summarize: String? = null
) {
    companion object {
        val SUMMARIZE_PROVIDERS = setOf("openai", "openrouter", "anthropic", "claudecli", "codexcli")
    }
}

data class CategoryConfig(
    val emoji: String,
    val feeds: List<FeedConfig>,
    val systemPrompt: String? = null,
    val userPrompt: String? = null,
    val channelId: String,
    /**
     * How often this category's fetch → digest pipeline runs, in minutes. Null falls back to
     * `scheduler.intervalMinutes`. Only honored in per-category scheduler mode (the default);
     * the legacy global cycle runs every category at the shared interval. A run that outlasts
     * its interval simply delays the next one — runs of the same category never overlap.
     */
    val intervalMinutes: Long? = null,
    /**
     * Ingestion cadence for this category (fetch → enrich → summarize → insert), in minutes.
     * Null falls back to `fetcher.intervalMinutes`; when that is null too, ingestion stays
     * coupled to the digest run. Per-category mode only.
     */
    val fetchIntervalMinutes: Long? = null,
    /**
     * When true, each digest bullet is sent as a Telegram photo+caption (single post)
     * if its first linked article has an `imageUrl` extracted from RSS. Otherwise the
     * bullet is sent as plain text. Defaults to false — opt-in per category.
     */
    val enableImages: Boolean = false,
    /**
     * When true, this category never touches any Batch API — Step 2 render (and the legacy
     * single-step path) always run synchronously via the `render` client, or via
     * `llm.batchFallback` when that override is configured. Use it for categories that must
     * publish immediately, or that route through a sync-only provider such as the Claude CLI
     * (`provider: claudecli`), which has no Batch API. Conflicts with any batch override
     * (`llm.batch`, or `batch: true` on `extract`/`extractAlternate`) and is rejected at load
     * time if combined with one. Defaults to false — categories batch as before.
     */
    val skipBatch: Boolean = false,
    /**
     * Opt-in two-step dedup pipeline for this category. When non-null and all four
     * prompts (extract.system/user + render.system/user) resolve, the bot runs a
     * sync JSON-mode extraction before the batch render. When null or unresolvable,
     * the category uses the legacy single-step flow.
     */
    val dedup: DedupConfig? = null,
    /**
     * System prompt for per-article summarization (used when any feed in this category
     * has `summarize:` set). Null falls back to a built-in default.
     */
    val summarizePrompt: String? = null,
    /**
     * Per-category overrides for which provider+model handles each LLM use case.
     * Credentials/baseUrl come from the named top-level provider block (`openai:` or
     * `openrouter:`). Null leaves all use cases on their global defaults.
     */
    val llm: CategoryLlmOverrides? = null,
    /**
     * Opt-in semantic-dedup detector. When non-null and `enabled=true`, each cycle
     * embeds new articles in this category and logs near-duplicate candidates above
     * `threshold`. The detector only writes to the log — it never mutates article
     * state. Designed as a stat-collection pass before committing to a hard filter.
     */
    val semanticDedup: SemanticDedupConfig? = null,
    /**
     * Category-scoped keyword mute, merged with the global [PreferencesConfig.mute]: an article
     * in this category is dropped before Step-1 if it matches EITHER list. Same whole-word,
     * case-insensitive matching as the global list (see [metifikys.digest.TopicMuteFilter]). Use
     * it for keys you only want gone from one channel — e.g. mute "cod" here without risking the
     * fish in a science category. Empty (default) = only the global list applies.
     */
    val mute: List<String> = emptyList()
)

/**
 * Lightweight reference to a provider+model: `provider` names a top-level block
 * (`openai` or `openrouter`); `model` is the model id passed to that endpoint.
 * `batch=true` opts the `extract`/`extractAlternate` use cases into the Batch API
 * (50% cost, async). Silently ignored on `render`/`batch`/`summarize`/`batchFallback`
 * — those fields encode batch-ness via their field name. Default `false` ⇒ zero
 * migration; all existing YAMLs are unchanged.
 *
 * [fallback] is the on-actual-failure secondary for this slot: when the primary call
 * fails (usage/credit limit, expired login, bad model, exhausted-retry timeout), the
 * SAME call is retried against the fallback provider+model. Honored on the sync use
 * cases only (`render` / `extract` / `extractAlternate` / `summarize` / `batchFallback`)
 * and wired in by [metifikys.ai.LlmClientsFactory] via [metifikys.ai.FallbackLlmClient].
 * Distinct from the queue-backpressure `batchFallback` slot and the A/B `extractAlternate`
 * slot — neither of those reacts to a provider actually failing. May chain (a fallback may
 * itself have a fallback, up to a small depth); each link must differ from the others and
 * may not set `batch=true` (the fallback always runs on the sync path).
 */
data class LlmOverride(
    val provider: String,
    val model: String,
    val batch: Boolean = false,
    val fallback: LlmOverride? = null
)

/**
 * Per-use-case overrides inside a category. Any null field falls back to the
 * default resolution for that use case (see LlmClients).
 */
data class CategoryLlmOverrides(
    val extract: LlmOverride? = null,
    /**
     * Explicit "B" side of the Step 1 A/B pair. When both `extract` and `extractAlternate`
     * are set, every second extract request is routed here (full alternation). When only
     * `extract` is set, alternation is suppressed (legacy behavior). Setting this without
     * `extract` is a misconfiguration and is rejected at load time.
     */
    val extractAlternate: LlmOverride? = null,
    val render: LlmOverride? = null,
    val batch: LlmOverride? = null,
    val summarize: LlmOverride? = null,
    /**
     * Sync fallback used when the category already has a batch in flight (pending == 1)
     * but is not yet fully stuck (>= 2). When set, the next cycle bypasses batch submission
     * and runs a synchronous summarize call against this provider/model instead — relieving
     * the primary batch endpoint and producing the digest immediately. All three providers
     * are valid here (sync path, no Batch API constraint).
     */
    val batchFallback: LlmOverride? = null
)

data class DedupPromptsInline(
    val extractSystem: String? = null,
    val extractUser: String? = null,
    val renderSystem: String? = null,
    val renderUser: String? = null
)

data class DedupConfig(
    /** Path (relative to CWD) to a YAML file with keys `extract.system/user` and `render.system/user`. */
    val promptFile: String? = null,
    /** Inline prompt overrides. Any non-null field beats the file's corresponding field. */
    val prompts: DedupPromptsInline? = null,
    /** How many days of covered events to include as Step 1 context. */
    val contextDays: Long = 7,
    /** Maximum number of covered events embedded in the Step 1 prompt. */
    val maxContextEvents: Int = 200,
    /**
     * Cap on articles per Step-1 extract call, newest by pubDate. Articles beyond the cap
     * are left UNPROCESSED and picked up next cycle. Also bounded by [extractMaxPromptChars].
     */
    val extractMaxArticles: Int = 100,
    /**
     * Approximate char budget for the serialized article batch embedded in the Step-1 prompt
     * (`{{CURRENT_BATCH_JSON}}`). Bounds the prompt so a sync CLI extract call both finishes
     * inside its timeout AND keeps the model on the required JSON envelope — on a ~141KB
     * prompt (100 backlog articles) claude-sonnet reproducibly drifted into fragment /
     * comma-separated output that failed parsing on every ~14-minute retry (2026-07-16).
     * At the default per-article promptText cap (1000 chars) this admits roughly 60–70
     * articles. The covered-events context is bounded separately by [maxContextEvents].
     */
    val extractMaxPromptChars: Int = 80_000,
    /**
     * Optional digest-quality gate. When non-null and `ranker.enabled` is true, Step 1's
     * shortlist is re-ranked and clamped in Kotlin before reaching Step 2. Safe to leave
     * null — falls back to the LLM's raw shortlist with a small default cap.
     */
    val digest: DigestConfig? = null
)

/**
 * Per-category digest-quality gate. See [telegram_posting_improvement_plan.md §4] for rationale.
 * All weights are 0..10 integer scales to match the LLM's scoring surface.
 */
data class DigestConfig(
    val ranker: RankerConfig = RankerConfig(),
    /** Require at least this many post-ranker items to publish a digest. */
    val minStrongItems: Int = 4,
    /** Hard cap on shortlist size after ranking. */
    val maxDigestItems: Int = 6,
    /** If no digest has gone out for this long, relax [minStrongItems] down to [minItemsOnForcePublish]. */
    val maxWaitHours: Long = 4,
    /** Floor used only when [maxWaitHours] has elapsed since the last digest. */
    val minItemsOnForcePublish: Int = 2,
    /** Max items per franchise in the final shortlist. */
    val maxPerFranchise: Int = 2,
    /** Max items per subject (typically one game title) in the final shortlist. */
    val maxPerSubject: Int = 1,
    /** Drop items with `digestFit` below this threshold, unless [newsworthinessOverride] applies. */
    val minDigestFit: Int = 3,
    /** Items with `newsworthiness` ≥ this value bypass the `minDigestFit` floor. */
    val newsworthinessOverride: Int = 8,
    /** Suppress low/medium-weight follow-up posts if the related event was just covered. */
    val meaningfulUpdateCooldownMinutes: Long = 90
)

data class RankerConfig(
    /** Master switch. When false, the ranker is skipped and Step 1's shortlist is used as-is. */
    val enabled: Boolean = false,
    /** Weight on `newsworthiness` in the composite score; `digestFit` weight is (1 - this). */
    val newsworthinessWeight: Double = 0.6,
    /**
     * Weight of the audience-affinity term (reaction feedback, phase 2). An item's raw
     * affinity (roughly ±0.5, see `audience_affinity.score`) is scaled by ×10 onto the
     * 0–10 composite scale, multiplied by this weight, and clamped to ±[maxAffinityBoost].
     * 0 (default) disables the term entirely — no lookups, no logs. Requires
     * `feedback.enabled=true` to have data to read. Suggested live value: 0.1–0.15.
     */
    val reactionWeight: Double = 0.0,
    /**
     * When true (default), the affinity term is computed and its would-be effect on the
     * shortlist is logged (`[AffinityRank][LOG-ONLY]`), but the published digest still uses
     * the baseline ranking. Flip to false after a few observed cycles to actually apply it.
     */
    val reactionLogOnly: Boolean = true,
    /**
     * Hard cap (composite points, 0–10 scale) on how far affinity may push a single item
     * up or down. Two items can therefore swing at most 2×this relative to each other
     * (one clamped up, the other clamped down) — with the default 1.0, any composite gap
     * above 2.0 points (≥ ~3.3 newsworthiness points at wNews=0.6) is untouchable by
     * audience taste.
     */
    val maxAffinityBoost: Double = 1.0
)

/**
 * Per-category configuration for the embedding-based semantic-dedup detector.
 * The detector runs after `db.insertArticles(...)` in each cycle, embeds new
 * articles via OpenAI `text-embedding-3-small` by default, and logs candidates
 * above [threshold] without mutating article state.
 *
 * Thresholds and windows are per-category because (e.g.) a tech feed with heavy
 * paraphrasing may want a lower threshold than a breaking-news feed where
 * legitimately repeated wording is common.
 */
data class SemanticDedupConfig(
    /** Master switch. When false, the detector is a no-op for this category. */
    val enabled: Boolean = false,
    /** Embedding model id passed to OpenAI `/v1/embeddings`. */
    val model: String = "text-embedding-3-small",
    /** How many days back to scan for candidate near-duplicates. */
    val windowDays: Long = 14,
    /** Cosine threshold above which a match is logged with the `[HIT]` marker. */
    val threshold: Double = 0.92,
    /** How many nearest neighbours to log per new article (top-1 + ranked debug lines). */
    val topK: Int = 5,
    /** Cap on recent vectors loaded into memory per category per cycle (bounds the brute-force scan). */
    val maxRecent: Int = 2000,
    /**
     * Master switch for the *event-level* embedding analyzer (Layer 3.5). When true, after
     * Step 1 (event extraction) produces a shortlist, each shortlisted event is embedded by
     * `event_key` and brute-force cosine-scanned against recently-covered events in the same
     * category. Log-only: surfaces `[EventSemanticDedup][HIT]` lines, NEVER removes or
     * marks anything. Independent of [enabled] (the article-level Layer-2 pass). Reuses
     * [model], [windowDays], [topK], and [maxRecent].
     */
    val eventEnabled: Boolean = false,
    /** Cosine threshold above which an event-level match is logged with the `[HIT]` marker. */
    val eventThreshold: Double = 0.92,
    /**
     * Cosine threshold for the *hard* reject at the event level. When non-null AND a shortlist
     * event with `status == "new"` has a top covered-event neighbour whose cosine meets this
     * threshold, the event is dropped from the shortlist before Step 2 renders — logged as
     * `[EventSemanticDedup][REJECT]` — and never reaches the digest. `meaningful_update` items
     * are never dropped (they are intentional follow-ups). Cosine-only: there is deliberately no
     * subject gate — tech subjects are free-text and vary every cycle, so subject equality would
     * gut recall (a week of logs showed gaming 65% / tech 7% same-subject among hits).
     *
     * Null disables the hard filter — the analyzer keeps logging but mutates nothing. Should be
     * set >= `eventThreshold` (validated at load time): the hard filter is the strict superset of
     * the logging threshold. Suggested production value ~0.80 per category (precision ~100% there).
     */
    val eventHardThreshold: Double? = null,
    /**
     * Cosine threshold for the *hard* reject. When non-null AND a new article's top-1
     * neighbour is already in status PROCESSED (sent to Telegram) AND their cosine
     * meets this threshold, the new article is marked [metifikys.model.ArticleStatus.DUPLICATE]
     * with `duplicate_of` pointing at the canonical row — and the LLM pipeline never
     * sees it. Matches against UNPROCESSED / PROCESSING / DUPLICATE neighbours are NOT
     * hard-rejected (the LLM dedupes those within-batch anyway).
     *
     * Null disables the hard filter — the detector keeps logging but mutates nothing.
     * Should be set ≥ `threshold` (validated at load time): the hard filter is the
     * strict superset of the logging threshold.
     */
    val hardThreshold: Double? = null
)

private val logger = KotlinLogging.logger {}

object ConfigLoader {
    /** Max links in an `llm.<slot>.fallback` chain (primary + up to this many fallbacks). */
    private const val MAX_FALLBACK_DEPTH = 3
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    fun load(path: String): AppConfig {
        val file = File(path)
        require(file.exists() && file.isFile && file.canRead()) { "Config file not found or not readable: $path" }
        val config = mapper.readValue(file, AppConfig::class.java)
        return applyEnvOverrides(config)
    }

    /**
     * Overrides sensitive config values from environment variables when present.
     * TELEGRAM_BOT_TOKEN, OPENAI_API_KEY, OPENROUTER_API_KEY, and ANTHROPIC_API_KEY
     * take precedence over config.yaml.
     */
    private fun applyEnvOverrides(config: AppConfig): AppConfig {
        val botToken = System.getenv("TELEGRAM_BOT_TOKEN")?.takeIf { it.isNotBlank() }
            ?: config.telegram.botToken
        val apiKey = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: config.openai.apiKey
        val openRouterKeyEnv = System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }
        val openRouter = when {
            config.openrouter != null -> config.openrouter.copy(
                apiKey = openRouterKeyEnv ?: config.openrouter.apiKey
            )
            openRouterKeyEnv != null -> null  // env alone doesn't enable OpenRouter; require config block
            else -> null
        }
        val anthropicKeyEnv = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
        val anthropic = when {
            config.anthropic != null -> config.anthropic.copy(
                apiKey = anthropicKeyEnv ?: config.anthropic.apiKey
            )
            anthropicKeyEnv != null -> null  // env alone doesn't enable Anthropic; require config block
            else -> null
        }

        require(botToken.isNotBlank()) { "Telegram botToken must not be empty (set TELEGRAM_BOT_TOKEN env var or config.yaml)" }
        require(apiKey.isNotBlank()) { "OpenAI apiKey must not be empty (set OPENAI_API_KEY env var or config.yaml)" }
        if (openRouter != null) {
            require(openRouter.apiKey.isNotBlank()) {
                "OpenRouter apiKey must not be empty when openrouter block is present (set OPENROUTER_API_KEY env var or config.yaml)"
            }
            require(openRouter.model.isNotBlank() || openRouter.models.isNotEmpty()) {
                "OpenRouter needs `model` or a non-empty `models` priority list"
            }
            require(openRouter.models.none { it.isBlank() }) { "OpenRouter models list must not contain blank entries" }
            require(openRouter.models.size == openRouter.models.distinct().size) {
                "OpenRouter models list must not contain duplicates"
            }
            require(openRouter.models.size <= OpenRouterConfig.MAX_MODELS) {
                "OpenRouter models list supports at most ${OpenRouterConfig.MAX_MODELS} entries"
            }
            require(openRouter.baseUrl.isNotBlank()) { "OpenRouter baseUrl must not be empty" }
        }
        if (anthropic != null) {
            require(anthropic.apiKey.isNotBlank()) {
                "Anthropic apiKey must not be empty when anthropic block is present (set ANTHROPIC_API_KEY env var or config.yaml)"
            }
            require(anthropic.model.isNotBlank()) { "Anthropic model must not be empty" }
            require(anthropic.batchModel.isNotBlank()) { "Anthropic batchModel must not be empty" }
            require(anthropic.baseUrl.isNotBlank()) { "Anthropic baseUrl must not be empty" }
            require(anthropic.anthropicVersion.isNotBlank()) { "Anthropic anthropicVersion must not be empty" }
            require(anthropic.maxTokens > 0) { "Anthropic maxTokens must be positive" }
        }
        config.claudeCli?.let { cli ->
            require(cli.command.isNotBlank()) { "claudeCli.command must not be blank" }
            require(cli.timeoutSeconds > 0) { "claudeCli.timeoutSeconds must be positive (got ${cli.timeoutSeconds})" }
        }
        config.codexCli?.let { cli ->
            require(cli.command.isNotBlank()) { "codexCli.command must not be blank" }
            require(cli.timeoutSeconds > 0) { "codexCli.timeoutSeconds must be positive (got ${cli.timeoutSeconds})" }
        }

        require(config.processing.primaryMaxPending >= 0) {
            "processing.primaryMaxPending must be >= 0 (got ${config.processing.primaryMaxPending}); 0 disables the primary render Batch API (always sync / batchFallback)"
        }
        require(config.processing.secondaryMaxPending >= 0) {
            "processing.secondaryMaxPending must be >= 0 (got ${config.processing.secondaryMaxPending})"
        }
        require(config.processing.maxConcurrentCategories >= 1) {
            "processing.maxConcurrentCategories must be >= 1 (got ${config.processing.maxConcurrentCategories})"
        }
        require(config.processing.categoryDeadlineMinutes >= 0) {
            "processing.categoryDeadlineMinutes must be >= 0 (got ${config.processing.categoryDeadlineMinutes}); " +
                "0 disables the barrier — the cycle waits for every category to finish"
        }
        if (config.processing.categoryDeadlineMinutes > 0) {
            val maxCliTimeout = maxOf(
                config.claudeCli?.timeoutSeconds ?: 0,
                config.codexCli?.timeoutSeconds ?: 0
            )
            if (maxCliTimeout > config.processing.categoryDeadlineMinutes * 60) {
                logger.warn {
                    "processing.categoryDeadlineMinutes (${config.processing.categoryDeadlineMinutes}m) is below a " +
                        "single CLI call's timeout (${maxCliTimeout}s) — the cycle barrier will repeatedly interrupt " +
                        "healthy LLM calls and no digest will complete; raise it above the worst-case per-category " +
                        "LLM chain, or set 0 to wait for every category (default)"
                }
            }
        }
        require(config.processing.llmCallRetentionDays >= 1) {
            "processing.llmCallRetentionDays must be >= 1 (got ${config.processing.llmCallRetentionDays})"
        }

        config.fetcher.let { f ->
            require(f.maxConcurrentFetches >= 1) {
                "fetcher.maxConcurrentFetches must be >= 1 (got ${f.maxConcurrentFetches})"
            }
            require(f.maxAttempts >= 1) { "fetcher.maxAttempts must be >= 1 (got ${f.maxAttempts})" }
            require(f.retryDelaySeconds >= 0) {
                "fetcher.retryDelaySeconds must be >= 0 (got ${f.retryDelaySeconds})"
            }
            require(f.fetchDeadlineSeconds >= 10) {
                "fetcher.fetchDeadlineSeconds must be >= 10 (got ${f.fetchDeadlineSeconds})"
            }
            if (f.fetchDeadlineSeconds > config.scheduler.intervalMinutes * 60) {
                logger.warn {
                    "fetcher.fetchDeadlineSeconds (${f.fetchDeadlineSeconds}s) exceeds scheduler.intervalMinutes " +
                        "(${config.scheduler.intervalMinutes}m) — cycles will run back-to-back (never overlapping, " +
                        "the scheduler is single-threaded), but the effective interval stretches"
                }
            }
        }
        // Per-category cadences: positive, and long enough for the fetch stage to fit.
        config.fetcher.intervalMinutes?.let { interval ->
            require(interval >= 1) { "fetcher.intervalMinutes must be >= 1 (got $interval)" }
        }
        for ((name, cat) in config.categories) {
            cat.intervalMinutes?.let { interval ->
                require(interval >= 1) { "categories.$name.intervalMinutes must be >= 1 (got $interval)" }
            }
            cat.fetchIntervalMinutes?.let { interval ->
                require(interval >= 1) { "categories.$name.fetchIntervalMinutes must be >= 1 (got $interval)" }
            }
            if (!config.scheduler.perCategory) continue
            val fetchInterval = cat.fetchIntervalMinutes ?: config.fetcher.intervalMinutes ?: cat.intervalMinutes
            if (fetchInterval != null && config.fetcher.fetchDeadlineSeconds > fetchInterval * 60) {
                logger.warn {
                    "categories.$name: fetch cadence (${fetchInterval}m) is below fetcher.fetchDeadlineSeconds " +
                        "(${config.fetcher.fetchDeadlineSeconds}s) — runs will go back-to-back and the " +
                        "effective interval stretches"
                }
            }
        }
        // Retention windows must not truncate data the weekly roundup or the dedup detector needs.
        run {
            val maxDedupWindow = config.categories.values
                .mapNotNull { it.semanticDedup?.takeIf { sd -> sd.enabled || sd.eventEnabled }?.windowDays }
                .maxOrNull() ?: 0L
            val weeklyLookback = config.weekly?.takeIf { it.enabled }?.lookbackDays ?: 0L
            val minArticleRetention = maxOf(weeklyLookback, maxDedupWindow)
            require(config.processing.articleRetentionDays >= minArticleRetention) {
                "processing.articleRetentionDays (${config.processing.articleRetentionDays}) must be >= " +
                    "max(weekly.lookbackDays=$weeklyLookback, max semanticDedup.windowDays=$maxDedupWindow) = $minArticleRetention"
            }
            require(config.processing.embeddingRetentionDays >= maxDedupWindow) {
                "processing.embeddingRetentionDays (${config.processing.embeddingRetentionDays}) must be >= " +
                    "max semanticDedup.windowDays ($maxDedupWindow) — the detector scans back that far"
            }
        }

        config.feedback.let { fb ->
            require(fb.attributionHours >= 0) { "feedback.attributionHours must be >= 0 (got ${fb.attributionHours})" }
            require(fb.lookbackDays > 0) { "feedback.lookbackDays must be > 0 (got ${fb.lookbackDays})" }
            require(fb.halflifeDays > 0) { "feedback.halflifeDays must be > 0 (got ${fb.halflifeDays})" }
            require(fb.minSamples > 0.0) { "feedback.minSamples must be > 0 (got ${fb.minSamples})" }
            require(fb.shrinkageK > 0.0) { "feedback.shrinkageK must be > 0 (got ${fb.shrinkageK})" }
            require(fb.minVolume >= 1) { "feedback.minVolume must be >= 1 (got ${fb.minVolume})" }
            require(fb.recomputeHours >= 1) { "feedback.recomputeHours must be >= 1 (got ${fb.recomputeHours})" }
            require(fb.lookbackDays * 24 > fb.attributionHours) {
                "feedback.lookbackDays (${fb.lookbackDays}) must reach further back than attributionHours (${fb.attributionHours}) — otherwise the aggregation window is empty"
            }
            for ((emoji, v) in fb.valence) {
                require(emoji.isNotBlank()) { "feedback.valence: emoji key must not be blank" }
                require(v in -1.0..1.0) { "feedback.valence['$emoji'] must be in [-1.0, 1.0] (got $v)" }
            }
        }

        for (entry in config.pricing) {
            require(entry.provider.isNotBlank()) { "pricing[]: provider must not be blank" }
            require(entry.model.isNotBlank()) { "pricing[]: model must not be blank for provider '${entry.provider}'" }
            require(entry.input >= 0.0) {
                "pricing[]: input price must be >= 0 for ${entry.provider}/${entry.model} (got ${entry.input})"
            }
            require(entry.output >= 0.0) {
                "pricing[]: output price must be >= 0 for ${entry.provider}/${entry.model} (got ${entry.output})"
            }
        }

        config.preferences.mute.forEachIndexed { i, kw ->
            require(kw.isNotBlank()) { "preferences.mute[$i] must not be blank" }
        }

        // Validate database path — no traversal, must not be blank
        val dbPath = config.database.path
        require(dbPath.isNotBlank()) { "Database path must not be blank" }
        require(!dbPath.contains("..")) { "Database path must not contain '..' (path traversal): $dbPath" }
        // Resolve to canonical path to eliminate any remaining ambiguity
        File(dbPath).canonicalPath  // will throw IOException if OS rejects it

        for ((name, category) in config.categories) {
            require(category.channelId.isNotBlank()) {
                "Category '$name' has blank channelId — provide @channel or numeric ID"
            }
            for (feed in category.feeds) {
                if (feed.summarize == "openrouter") {
                    require(openRouter != null) {
                        "Category '$name' feed '${feed.url}' requested OpenRouter summarization but no openrouter: block is configured"
                    }
                }
                if (feed.summarize == "anthropic") {
                    require(anthropic != null) {
                        "Category '$name' feed '${feed.url}' requested Anthropic summarization but no anthropic: block is configured"
                    }
                }
                if (feed.summarize == "claudecli") {
                    require(config.claudeCli != null) {
                        "Category '$name' feed '${feed.url}' requested Claude CLI summarization but no claudeCli: block is configured"
                    }
                }
                if (feed.summarize == "codexcli") {
                    require(config.codexCli != null) {
                        "Category '$name' feed '${feed.url}' requested Codex CLI summarization but no codexCli: block is configured"
                    }
                }
            }
            category.mute.forEachIndexed { i, kw ->
                require(kw.isNotBlank()) { "Category '$name' mute[$i] must not be blank" }
            }
            category.dedup?.digest?.ranker?.let { r ->
                require(r.reactionWeight in 0.0..1.0) {
                    "Category '$name' ranker.reactionWeight must be in [0.0, 1.0] (got ${r.reactionWeight})"
                }
                require(r.maxAffinityBoost >= 0.0) {
                    "Category '$name' ranker.maxAffinityBoost must be >= 0 (got ${r.maxAffinityBoost})"
                }
                require(r.reactionWeight == 0.0 || config.feedback.enabled) {
                    "Category '$name' sets ranker.reactionWeight=${r.reactionWeight} but feedback.enabled=false — " +
                        "the affinity table is never populated, so the term would silently read zeros. Enable feedback: or drop the weight."
                }
            }
            category.dedup?.let { d ->
                require(d.extractMaxArticles > 0) {
                    "Category '$name' dedup.extractMaxArticles must be > 0 (got ${d.extractMaxArticles})"
                }
                require(d.extractMaxPromptChars > 0) {
                    "Category '$name' dedup.extractMaxPromptChars must be > 0 (got ${d.extractMaxPromptChars})"
                }
            }
            category.semanticDedup?.let { sd ->
                require(sd.threshold in 0.0..1.0) {
                    "Category '$name' semanticDedup.threshold must be in [0.0, 1.0] (got ${sd.threshold})"
                }
                require(sd.windowDays > 0) {
                    "Category '$name' semanticDedup.windowDays must be > 0 (got ${sd.windowDays})"
                }
                require(sd.topK > 0) {
                    "Category '$name' semanticDedup.topK must be > 0 (got ${sd.topK})"
                }
                require(sd.maxRecent > 0) {
                    "Category '$name' semanticDedup.maxRecent must be > 0 (got ${sd.maxRecent})"
                }
                require(sd.model.isNotBlank()) {
                    "Category '$name' semanticDedup.model must not be blank"
                }
                require(sd.eventThreshold in 0.0..1.0) {
                    "Category '$name' semanticDedup.eventThreshold must be in [0.0, 1.0] (got ${sd.eventThreshold})"
                }
                sd.eventHardThreshold?.let { ht ->
                    require(ht in 0.0..1.0) {
                        "Category '$name' semanticDedup.eventHardThreshold must be in [0.0, 1.0] (got $ht)"
                    }
                    require(ht >= sd.eventThreshold) {
                        "Category '$name' semanticDedup.eventHardThreshold ($ht) must be >= eventThreshold (${sd.eventThreshold}) — " +
                            "the hard filter is a strict subset of what gets logged"
                    }
                }
                sd.hardThreshold?.let { ht ->
                    require(ht in 0.0..1.0) {
                        "Category '$name' semanticDedup.hardThreshold must be in [0.0, 1.0] (got $ht)"
                    }
                    require(ht >= sd.threshold) {
                        "Category '$name' semanticDedup.hardThreshold ($ht) must be >= threshold (${sd.threshold}) — " +
                            "the hard filter is a strict subset of what gets logged"
                    }
                }
            }
            category.llm?.let { ovr ->
                validateOverride(name, "extract", ovr.extract, openRouter, anthropic, config.claudeCli, config.codexCli)
                validateOverride(name, "extractAlternate", ovr.extractAlternate, openRouter, anthropic, config.claudeCli, config.codexCli)
                require(ovr.extractAlternate == null || ovr.extract != null) {
                    "Category '$name' llm.extractAlternate is set but llm.extract is not — extractAlternate is the 'B' side of an A/B pair and requires the 'A' side"
                }
                ovr.extract?.let { e ->
                    require(!(e.batch && e.provider in setOf("openrouter", "claudecli", "codexcli"))) {
                        "Category '$name' llm.extract: batch=true is not supported for provider '${e.provider}' (no Batch API)"
                    }
                    require(!(e.batch && e.fallback != null)) {
                        "Category '$name' llm.extract: a fallback together with batch=true is inert — the batch leg runs async and never uses the sync fallback. Drop one."
                    }
                }
                ovr.extractAlternate?.let { e ->
                    require(!(e.batch && e.provider in setOf("openrouter", "claudecli", "codexcli"))) {
                        "Category '$name' llm.extractAlternate: batch=true is not supported for provider '${e.provider}' (no Batch API)"
                    }
                    require(!(e.batch && e.fallback != null)) {
                        "Category '$name' llm.extractAlternate: a fallback together with batch=true is inert — the batch leg runs async and never uses the sync fallback. Drop one."
                    }
                }
                validateOverride(name, "render", ovr.render, openRouter, anthropic, config.claudeCli, config.codexCli)
                validateOverride(name, "batch", ovr.batch, openRouter, anthropic, config.claudeCli, config.codexCli)
                validateOverride(name, "summarize", ovr.summarize, openRouter, anthropic, config.claudeCli, config.codexCli)
                validateOverride(name, "batchFallback", ovr.batchFallback, openRouter, anthropic, config.claudeCli, config.codexCli)
                ovr.batch?.let { b ->
                    require(b.provider in setOf("openai", "anthropic")) {
                        "Category '$name' llm.batch: batch override only supports provider: openai or anthropic (OpenRouter, Claude CLI, and Codex CLI have no Batch API)"
                    }
                    require(b.fallback == null) {
                        "Category '$name' llm.batch: fallback is not supported on the batch slot — the Batch API path has no sync fallback (configure llm.batchFallback for backpressure, or put fallback on llm.render)"
                    }
                }
                if (category.skipBatch) {
                    require(ovr.batch == null) {
                        "Category '$name' sets skipBatch: true but also configures llm.batch — remove one (skipBatch means the category never uses the Batch API)"
                    }
                    require(ovr.extract?.batch != true) {
                        "Category '$name' sets skipBatch: true but also llm.extract.batch: true — skipBatch means no Batch API at all"
                    }
                    require(ovr.extractAlternate?.batch != true) {
                        "Category '$name' sets skipBatch: true but also llm.extractAlternate.batch: true — skipBatch means no Batch API at all"
                    }
                }
            }
        }

        config.weekly?.let { w ->
            if (w.enabled) {
                try {
                    w.dayOfWeekParsed()
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "weekly.dayOfWeek '${w.dayOfWeek}' is invalid — use a day name like MONDAY..SUNDAY (case-insensitive)"
                    )
                }
                try {
                    w.timeParsed()
                } catch (e: DateTimeParseException) {
                    throw IllegalArgumentException("weekly.time '${w.time}' is invalid — use 24h \"HH:mm\" (e.g. \"18:00\")")
                }
                require(w.lookbackDays > 0) { "weekly.lookbackDays must be > 0 (got ${w.lookbackDays})" }
                require(w.topN >= 1) { "weekly.topN must be >= 1 (got ${w.topN})" }
                require(w.candidatePoolSize >= w.topN) {
                    "weekly.candidatePoolSize (${w.candidatePoolSize}) must be >= topN (${w.topN})"
                }
                require(w.articleCandidatePoolSize >= w.topN) {
                    "weekly.articleCandidatePoolSize (${w.articleCandidatePoolSize}) must be >= topN (${w.topN})"
                }
                require(w.minMentions >= 1) { "weekly.minMentions must be >= 1 (got ${w.minMentions})" }
                require(w.clusterThreshold in 0.0..1.0) {
                    "weekly.clusterThreshold must be in [0.0, 1.0] (got ${w.clusterThreshold})"
                }
                require(w.embeddingModel.isNotBlank()) { "weekly.embeddingModel must not be blank" }
                require(w.maxEvents > 0) { "weekly.maxEvents must be > 0 (got ${w.maxEvents})" }
                for (cat in w.categories) {
                    require(cat in config.categories) {
                        "weekly.categories references unknown category '$cat' — must match a key under categories:"
                    }
                }
            }
        }

        return config.copy(
            telegram = config.telegram.copy(botToken = botToken),
            openai = config.openai.copy(apiKey = apiKey),
            openrouter = openRouter,
            anthropic = anthropic
        )
    }

    private fun validateOverride(
        category: String,
        useCase: String,
        ovr: LlmOverride?,
        openRouter: OpenRouterConfig?,
        anthropic: AnthropicConfig?,
        claudeCli: ClaudeCliConfig?,
        codexCli: CodexCliConfig?
    ) {
        if (ovr == null) return
        validateOverrideNode(category, useCase, ovr, openRouter, anthropic, claudeCli, codexCli)
        // On-failure fallback chain: each link must be a valid sync override, must not repeat
        // a provider+model already in the chain (cycle), and must not run deeper than
        // MAX_FALLBACK_DEPTH. The primary's own (provider, model) seeds the cycle guard so a
        // fallback pointing back at the primary is rejected too.
        ovr.fallback?.let { fb ->
            validateFallbackChain(
                category, useCase, fb,
                seen = linkedSetOf(keyOf(ovr)),
                depth = 1,
                openRouter, anthropic, claudeCli, codexCli
            )
        }
    }

    /** Validates a single override in isolation (provider name, required block, model). */
    private fun validateOverrideNode(
        category: String,
        useCase: String,
        ovr: LlmOverride,
        openRouter: OpenRouterConfig?,
        anthropic: AnthropicConfig?,
        claudeCli: ClaudeCliConfig?,
        codexCli: CodexCliConfig?
    ) {
        require(ovr.provider in setOf("openai", "openrouter", "anthropic", "claudecli", "codexcli")) {
            "Category '$category' llm.$useCase: provider must be 'openai', 'openrouter', 'anthropic', 'claudecli', or 'codexcli', got '${ovr.provider}'"
        }
        // The CLI providers are the ones where a blank model is valid (it means "CLI default").
        require(ovr.provider == "claudecli" || ovr.provider == "codexcli" || ovr.model.isNotBlank()) {
            "Category '$category' llm.$useCase: model must not be blank"
        }
        if (ovr.provider == "openrouter") {
            require(openRouter != null) {
                "Category '$category' llm.$useCase: provider 'openrouter' requires top-level openrouter: block"
            }
        }
        if (ovr.provider == "anthropic") {
            require(anthropic != null) {
                "Category '$category' llm.$useCase: provider 'anthropic' requires top-level anthropic: block"
            }
        }
        if (ovr.provider == "claudecli") {
            require(claudeCli != null) {
                "Category '$category' llm.$useCase: provider 'claudecli' requires top-level claudeCli: block"
            }
        }
        if (ovr.provider == "codexcli") {
            require(codexCli != null) {
                "Category '$category' llm.$useCase: provider 'codexcli' requires top-level codexCli: block"
            }
        }
    }

    /**
     * Walks the `fallback` links of a sync override: validates each link, forbids `batch=true`
     * on a link (a fallback always runs synchronously), bounds the chain depth, and rejects a
     * cycle. [seen] is pre-seeded with the primary's key so a fallback that points back at the
     * primary is caught as well.
     */
    private fun validateFallbackChain(
        category: String,
        useCase: String,
        node: LlmOverride,
        seen: MutableSet<String>,
        depth: Int,
        openRouter: OpenRouterConfig?,
        anthropic: AnthropicConfig?,
        claudeCli: ClaudeCliConfig?,
        codexCli: CodexCliConfig?
    ) {
        require(depth <= MAX_FALLBACK_DEPTH) {
            "Category '$category' llm.$useCase: fallback chain is deeper than $MAX_FALLBACK_DEPTH — shorten it"
        }
        validateOverrideNode(category, "$useCase.fallback", node, openRouter, anthropic, claudeCli, codexCli)
        require(!node.batch) {
            "Category '$category' llm.$useCase.fallback: batch=true is not allowed — a fallback always runs on the sync path"
        }
        require(seen.add(keyOf(node))) {
            "Category '$category' llm.$useCase: fallback cycle detected — '${keyOf(node)}' appears more than once in the chain"
        }
        node.fallback?.let { next ->
            validateFallbackChain(category, useCase, next, seen, depth + 1, openRouter, anthropic, claudeCli, codexCli)
        }
    }

    /** Stable identity of an override for cycle detection. CLI blank model ⇒ "<default>" so a self-cycle is caught. */
    private fun keyOf(ovr: LlmOverride): String = "${ovr.provider}/${ovr.model.ifBlank { "<default>" }}"
}
