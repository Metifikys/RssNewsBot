package metifikys.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
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
    val embeddingRetentionDays: Long = 30,
    /** How many days of `shadow_runs` rows (`llm.shadow`) to keep. Pruned each cycle. Must be >= 1. */
    val shadowRetentionDays: Long = 30
)

/**
 * Optional retention archive. When [enabled], the retention cleanup copies the rows it is about
 * to delete from the listed [tables] into per-period SQLite files (`<dir>/news-archive-2026-09.db`)
 * before deleting them from the main DB, so benchmarks and retrospectives can reach past the
 * 14–90-day retention windows. Absent / `enabled: false` = plain DELETE, exactly as before.
 * See [metifikys.db.RetentionArchiver].
 */
data class ArchiveConfig(
    val enabled: Boolean = false,
    /** Archive directory; a relative path resolves against the directory holding the main DB. Local disk only. */
    val dir: String = "archive",
    /** One file per `monthly` | `quarterly` | `yearly` period of the ROW's timestamp. */
    val rotation: String = "monthly",
    /**
     * `article_embeddings` policy: `all` archives every vector (~320 MB/month at 1536 dims),
     * `labelled` only those whose article reached Step 1 (its link is in covered/rejected
     * events — what the benches train on), `none` deletes them without archiving.
     */
    val embeddings: String = "labelled",
    /** Tables to archive; any retention table not listed is deleted as before. */
    val tables: List<String> = ARCHIVABLE_TABLES,
    /** Keep at most this many archive files (oldest deleted first); `0` = never delete archive files. */
    val keepFiles: Int = 0,
    /** Below this much free space in [dir] the cleanup warns and falls back to plain DELETE. */
    val minFreeDiskMb: Long = 2048
) {
    companion object {
        val ROTATIONS = setOf("monthly", "quarterly", "yearly")
        val EMBEDDING_MODES = setOf("all", "labelled", "none")
        val ARCHIVABLE_TABLES = listOf(
            "article_embeddings", "event_embeddings", "rejected_events", "covered_events",
            "llm_calls", "summaries", "digest_messages", "reaction_counts"
        )
    }
}

data class AdminConfig(
    /**
     * Chat where the rolling status snapshot is posted at the end of each digest cycle.
     * On every post the previous status message is deleted, so this chat shows only one
     * message at a time. Null disables status posting.
     *
     * With [commands] enabled this is also the admin chat: commands are accepted here and
     * notices go here. Prefer the private chat with the bot (its id is your user id) — in a
     * channel a command arrives as a `channel_post` without a sender and is never routed.
     */
    val statusChatId: String? = null,
    /**
     * Telegram user ids allowed to run admin commands and press admin buttons. A command is
     * accepted from one of these users in [statusChatId] or in a private chat with the bot;
     * everything else is ignored silently (the bot never answers strangers).
     */
    val userIds: List<Long> = emptyList(),
    /** Admin commands over the `getUpdates` poller; off by default. */
    val commands: AdminCommandsConfig = AdminCommandsConfig(),
    /** Periodic mute proposals with approve/reject buttons in the admin chat; off by default. */
    val muteSuggestions: MuteSuggestionsConfig = MuteSuggestionsConfig(),
    /** One moderation message per published post in the admin chat; off by default. */
    val moderation: ModerationConfig = ModerationConfig(),
    /** `/inspect`, pasted post links and forwarded posts → a card about the post; on with [commands]. */
    val inspect: AdminInspectConfig = AdminInspectConfig(),
    /** Threads (topics) of [statusChatId] that the bot's admin messages go to; none = the main thread. */
    val threads: AdminThreadsConfig = AdminThreadsConfig()
) {
    /**
     * Where an answer to a message from [chatId] / thread [origin] goes: the [configured] thread
     * when the message came from the admin chat (thread ids mean nothing in another chat), else
     * back to [origin].
     */
    fun threadFor(chatId: String, configured: Long?, origin: Long?): Long? =
        if (configured != null && chatId == statusChatId?.trim()) configured else origin
}

/**
 * Threads of the admin chat (`message_thread_id`): a private chat with the bot gets topics once
 * Threaded Mode is on in @BotFather, a group once Topics is on. `/thread` sent in a thread answers
 * with its id. Null = that output goes where it went before.
 *
 *  - [status]: the rolling status snapshot, operator alerts, mute proposals, moderation messages —
 *    everything the bot posts on its own;
 *  - [inspect]: the `/inspect` cards (links / forwards sent from any thread land here);
 *  - [raw]: the `/raw` browser and the article messages it sends.
 *
 * A command's own reply (usage, errors, `/mutes`, …) always goes to the thread it was sent from.
 */
data class AdminThreadsConfig(
    val status: Long? = null,
    val inspect: Long? = null,
    val raw: Long? = null
)

/**
 * Post inspector: the owner sends `/inspect <link>`, a message with up to 5 channel-post links
 * (`t.me/c/<id>/<msg>`, `t.me/<username>/<msg>`) or a forwarded channel post, and gets one card
 * per post — its event, scores, sources, story cluster, audience and the mutes that already hit
 * it — with 🔇 buttons for the franchise, a few keywords and the event type. Read-only except for
 * those buttons, which write the same DB mutes as `/mute`. Only does anything with
 * `admin.commands.enabled` (the same users and chats as every command), so it defaults to on:
 * turning the commands on is the opt-in. `false` drops the command, the link/forward handling
 * and the buttons.
 */
data class AdminInspectConfig(
    val enabled: Boolean = true
)

/**
 * Post moderation from the PRIVATE admin chat — never buttons under the channel post, which
 * every reader would see. After each digest, one silent compact message per published post
 * (title, category, link) with [🗑 delete] [🔇 mute the franchise] [👍 more] [👎 less]. Delete
 * asks for confirmation and removes every chunk of the post (Telegram allows it for 48 h);
 * more/less land in `admin_feedback` as an explicit affinity signal (`feedback.explicitWeight`).
 * Needs `admin.commands.enabled` (the buttons) and `statusChatId` (where the messages go).
 */
data class ModerationConfig(
    val enabled: Boolean = false,
    /** Categories whose posts get a moderation message; empty = all. */
    val categories: List<String> = emptyList()
)

/**
 * Mute proposals: every [everyDays] the maintenance tick looks for franchises the audience
 * dislikes (affinity `score <= maxScore` backed by `n_tone >= minTone` reacted posts) or that
 * never get through (>= [minRejected] Step-1 rejections and no coverage in 30 days), turns each
 * into a keyword that actually occurs in recent titles, and posts up to [maxPerRun] of them to
 * the admin chat, silently, with [🔇 in category] [🌐 global] [✖ no] buttons. Approving writes
 * the same DB mute as `/mute`. Needs `admin.commands.enabled` (the buttons) and `statusChatId`.
 */
data class MuteSuggestionsConfig(
    val enabled: Boolean = false,
    val everyDays: Long = 7,
    val maxScore: Double = -0.15,
    val minTone: Double = 2.0,
    val minRejected: Int = 20,
    val maxPerRun: Int = 5
)

/**
 * Admin commands (`/help`, …) routed through the same `getUpdates` long-poll that collects
 * reactions — Telegram allows one consumer per token, so there is no second poller. Needs
 * `telegram.updatesPolling: true` and a non-empty `admin.userIds`.
 */
data class AdminCommandsConfig(
    /** Subscribes the poller to `message` + `callback_query` updates and routes them. */
    val enabled: Boolean = false
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
     * Replaces the "🔁 N" badge with the story's outlets from `event_articles`: `📢 N видань · M
     * першоджерел` (see `post.storyLines.hype`). By the weekly run the cluster is complete, late
     * reprints included. Needs `storyIntel.enabled`; a story with fewer than two outlets keeps "🔁 N".
     */
    val showOutlets: Boolean = false,
    /** "🥇 Хто пише першим" block at the end of the roundup; see [SourceLeaderboardConfig]. */
    val sourceLeaderboard: SourceLeaderboardConfig = SourceLeaderboardConfig(),
    /** Weekly industry barometer (chart of layoffs vs releases); see [BarometerConfig]. */
    val barometer: BarometerConfig = BarometerConfig(),
    /** An album of the stories' pictures before the roundup text; see [WeeklyAlbumConfig]. */
    val album: WeeklyAlbumConfig = WeeklyAlbumConfig(),
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

/**
 * The weekly album: before the roundup text, one `sendMediaGroup` of the pictures of the stories
 * its bullets link (the stored `articles.image_url` of each story's URL, in bullet order, one per
 * URL, judged by the category's `post.images` picker when it has one), with the roundup's header
 * line as the caption of the first photo. Each picture is checked first with a HEAD request
 * ([timeoutSeconds]; `image/…`, at most [maxBytes] when the size is given) — Telegram rejects the
 * whole album over one bad photo. Fewer than [minPhotos] usable pictures, or any album failure →
 * the text alone, as before. At most [maxPhotos] (Telegram allows 2–10).
 */
data class WeeklyAlbumConfig(
    val enabled: Boolean = false,
    val minPhotos: Int = 2,
    val maxPhotos: Int = 5,
    val timeoutSeconds: Long = 3,
    val maxBytes: Long = 5L * 1024 * 1024
)

/**
 * The per-outlet "who writes first" ranking over the story clusters of the last [lookbackDays]
 * (stories with 3+ outlets; needs `storyIntel.enabled`). With [enabled] the weekly roundup ends with
 * `🥇 Хто пише першим (30 дн.): a.com — 12 з 20 · …` (the [top] outlets by first rate among those
 * with [minEvents]+ stories); [showCopiers] adds the outlets most often behind — naming them in
 * public is a reputational call, hence off. `/status` shows the same ranking whenever storyIntel is
 * on, with these numbers.
 */
data class SourceLeaderboardConfig(
    val enabled: Boolean = false,
    val lookbackDays: Long = 30,
    val minEvents: Int = 10,
    val top: Int = 3,
    val showCopiers: Boolean = false
)

/**
 * The industry barometer: after the weekly roundup, a chart of how many distinct entities per ISO
 * week (Monday–Sunday; the last bar is the current week) fell into each of [series] over the last
 * [weeks] weeks, read from `event_history` (needs `storyIntel.enabled`). [format] `png` sends a
 * 1200×675 bar chart as its own photo post right after the roundup (bundled DejaVu font, rendered
 * headless); `text` — and `png` when the chart cannot be drawn — appends a sparkline block to the
 * roundup instead. Nothing is shown until the history spans [minWeeks] weeks. At most two series:
 * a phone shows the picture small.
 */
data class BarometerConfig(
    val enabled: Boolean = false,
    /** Categories that get it; empty = every category of the weekly roundup. */
    val categories: List<String> = emptyList(),
    val weeks: Int = 12,
    val minWeeks: Int = 4,
    val format: String = FORMAT_PNG,
    val series: List<BarometerSeriesConfig> = DEFAULT_SERIES
) {
    companion object {
        const val FORMAT_PNG = "png"
        const val FORMAT_TEXT = "text"

        val DEFAULT_SERIES = listOf(
            BarometerSeriesConfig(
                id = "layoffs", label = "Звільнення і закриття", eventTypes = listOf("layoffs", "studio_closure"),
                keywords = listOf(
                    "звільн", "скорочення штату", "скорочення співробітників", "закриття студії", "layoff", "laid off",
                    "job cuts", "studio closure"
                )
            ),
            // By the week the news was posted: counted by their own date (byEventDate) releases
            // stay near zero — covered_events has no dates to warm up from, and Step 1 gives a
            // day-precise eventDate for about one release story in five.
            BarometerSeriesConfig(id = "releases", label = "Новини про релізи", eventTypes = listOf("release_date", "delay"))
        )
    }
}

/**
 * One barometer series. An event counts when its type is one of [eventTypes], or — for layoffs,
 * which Step 1 files as `industry_news` unless the prompt knows a `layoffs` type — when its type
 * is one of [keywordEventTypes] and its subject contains one of [keywords] (case-insensitive
 * substrings; the core fact is not searched — it mentions layoffs and closures in passing). Counted by the week the event reached the channel, or with
 * [byEventDate] by the week of its own day-precise `eventDate` (the latest one known per entity, so
 * a delay moves the release). That is sparse: only events with a day date count, and Step 1 gives
 * one for a minority of release stories.
 */
data class BarometerSeriesConfig(
    val id: String,
    val label: String,
    val eventTypes: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val keywordEventTypes: List<String> = listOf("industry_news"),
    val byEventDate: Boolean = false
)

data class WeeklyPromptsInline(
    val system: String? = null,
    val user: String? = null
)

/**
 * Health of the subscription-logged-in `claude` / `codex` CLIs on the server. Their OAuth login
 * expires quietly, and with a `fallback` configured the other provider serves the calls, so
 * until now an expired login showed up only in the logs. With [enabled], every real CLI call
 * reports its outcome (ok / auth / limit / model / timeout / error); entering auth, limit or
 * model — and recovering from it — sends one message to `admin.statusChatId`, and `/status`
 * gets a `CLI:` line. Needs `admin.statusChatId`.
 */
data class HealthConfig(
    val enabled: Boolean = false,
    /** Active check from the maintenance tick for a CLI with no successful real call lately. */
    val cliProbe: CliProbeConfig = CliProbeConfig(),
    /**
     * Expiry date (`YYYY-MM-DD`) of a long-lived `claude setup-token` token, if that is how the
     * server logs in; warns once a day from [warnDaysBefore] days before. The short-lived
     * access token in `~/.claude/.credentials.json` refreshes itself and is not a signal.
     */
    val claudeTokenExpires: String? = null,
    val warnDaysBefore: Long = 14
)

/**
 * The probe: `ping` through `claude -p` / `codex exec` (for codex, `codex login status` first
 * when the installed version has it — a logged-out answer then needs no model call). Skipped
 * while a real call of that CLI succeeded within [intervalHours], so a busy bot never probes.
 * Uses subscription usage: at most one call per CLI per interval, only when idle.
 */
data class CliProbeConfig(
    val enabled: Boolean = true,
    val intervalHours: Long = 6,
    val timeoutSeconds: Long = 60,
    /** Model for the claude probe; blank = `claudeCli.model`. */
    val claudeModel: String = "",
    /** Model for the codex probe; blank = `codexCli.model`. */
    val codexModel: String = ""
)

/**
 * Per-feed quality metrics (`.claude/plans/2026-09-ideas-sources.md` §23): which configured feeds
 * actually reach the channel, which are noise, which bring stories first, and whether they fetch
 * at all. With [enabled] the fetcher keeps per-feed fetch outcomes (flushed to `feed_stats` on the
 * maintenance tick) and `/status` gets a `📡 Feeds` section listing only the anomalies. Suggestions
 * only — nothing is ever disabled automatically. The full per-feed table is the static report
 * (`bench/db-audit/report.mjs`), which reads the same columns.
 */
data class FeedHealthConfig(
    val enabled: Boolean = false,
    /** Metrics window over `articles.fetched_at`; capped at 30 (the `rejected_events` retention). */
    val windowDays: Long = 14,
    /** The `/status` section; off keeps recording without the section. */
    val statusSection: Boolean = true,
    val thresholds: FeedHealthThresholds = FeedHealthThresholds(),
    /** Candidate new feeds (log + report only); works without [enabled]. */
    val candidates: FeedCandidatesConfig = FeedCandidatesConfig()
)

/**
 * Plan §24 v1: domains the channel keeps publishing from (via reddit / hnrss / a converter) with no
 * configured feed on them, plus feed autodiscovery (`<link rel=alternate>`, `/feed`, …) for the
 * best few. Results go to `feed_candidates` and the log; the static report shows them. The bot
 * never adds a feed. Window = `feedHealth.windowDays`.
 */
data class FeedCandidatesConfig(
    val enabled: Boolean = false,
    /** Run on the maintenance tick at most this often. */
    val everyDays: Long = 7,
    /** Hosts probed per run. */
    val maxProbesPerRun: Int = 10,
    /** Hard cap on HTTP requests per run, redirects included. */
    val maxRequestsPerRun: Int = 40,
    /** A domain needs at least this many published links in the window. */
    val minPublished: Int = 3,
    /** A probed domain is probed again after this many days. */
    val recheckDays: Long = 30,
    /** Never candidates (a domain covers its subdomains): platforms, social networks, reference sites. */
    val ignoreDomains: List<String> = listOf(
        "youtube.com", "youtu.be", "x.com", "twitter.com", "reddit.com", "redd.it", "t.me", "wikipedia.org",
        "github.com", "bsky.app", "instagram.com", "facebook.com", "tiktok.com", "store.steampowered.com",
        "steamcommunity.com", "news.ycombinator.com"
    )
)

/** The hint rules (deterministic, text only): 💀 dead, 💤 stale, 🗑 noisy, 🪞 mirror, ⭐ first source. */
data class FeedHealthThresholds(
    /** 💀 no successful fetch for this many days ... */
    val deadDays: Long = 3,
    /** ... or this many failed passes in a row. */
    val deadFailStreak: Int = 10,
    /** 💤 fetches fine but brought nothing new for this many days. */
    val staleNewDays: Long = 14,
    /** 🗑 needs at least this many articles in the window ... */
    val noisyMinArticles: Int = 30,
    /** ... fewer than this share published ... */
    val noisyMaxPublishedPct: Double = 2.0,
    /** ... and more than this share junk (muted, Step 1 rejected, never selected). */
    val noisyMinJunkPct: Double = 80.0,
    /** 🪞 / ⭐ need at least this many published articles. */
    val minPublishedForFirst: Int = 5,
    /** 🪞 first in fewer than this share of its clusters: other feeds bring it all anyway. */
    val mirrorMaxFirstPct: Double = 10.0,
    /** ⭐ first in at least this share: keep. */
    val primaryMinFirstPct: Double = 50.0
)

/**
 * Story intelligence (`.claude/plans/2026-09-ideas-story-intelligence.md`): with [enabled] the
 * dedup layers record which articles they folded into which event (`event_articles`: Step 1
 * extractions, Layer 2 late reprints, Layer 3.5 merges). Recording only — nothing visible changes;
 * the footer lines that read the clusters (`categories.<name>.post.storyLines`), the weekly outlet
 * count and the "who writes first" leaderboard are switched on separately and need this.
 */
data class StoryIntelConfig(
    val enabled: Boolean = false,
    /** How long `event_articles` rows are kept; must cover `weekly.lookbackDays` and the leaderboard window. */
    val retentionDays: Long = 180,
    /**
     * How long `event_history` rows (one per delivered event, first appearance) are kept — years,
     * since the vaporware count and the weekly barometer read long histories.
     */
    val historyRetentionDays: Long = 1095,
    /**
     * Domain → the domain it counts as, for sister sites and mirrors that should be one outlet
     * (`eurogamer.de: eurogamer.net`). Applied when clusters are read, so it works on old rows too.
     */
    val domainAliases: Map<String, String> = emptyMap()
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
    /** Retention archive (copy-before-delete); null = rows are simply deleted. */
    val archive: ArchiveConfig? = null,
    val admin: AdminConfig = AdminConfig(),
    val weekly: WeeklyConfig? = null,
    val feedback: FeedbackConfig = FeedbackConfig(),
    val preferences: PreferencesConfig = PreferencesConfig(),
    /** Global knobs for the live-data footer badges; per-category on/off is `post.badges`. */
    val badges: BadgeSettings = BadgeSettings(),
    /** `claude` / `codex` login and usage-limit alerts; see [HealthConfig]. */
    val health: HealthConfig = HealthConfig(),
    /** Per-feed quality metrics and fetch health; see [FeedHealthConfig]. */
    val feedHealth: FeedHealthConfig = FeedHealthConfig(),
    /** Story clusters (`event_articles`) and the features that read them; see [StoryIntelConfig]. */
    val storyIntel: StoryIntelConfig = StoryIntelConfig(),
    /** Telegraph (telegra.ph) account for the long-form Instant View pages; see [TelegraphConfig]. */
    val telegraph: TelegraphConfig = TelegraphConfig(),
    /**
     * Per-(provider, model) prices used by `/status` cost stats. Costs are in USD per
     * 1,000,000 tokens (matches how OpenAI / Anthropic publish their pricing pages).
     * Entries not listed here are billed as $0 — calls are still recorded with token
     * counts, the cost column just shows zero. Empty by default so the bot runs
     * without any pricing knowledge. Batch-API calls are auto-halved by
     * [metifikys.ai.LlmPricing].
     */
    val pricing: List<LlmPriceEntry> = emptyList(),
    /** Sampled, log-only LLM judge of the delivered posts against their sources; see [FactCheckConfig]. */
    val factCheck: FactCheckConfig = FactCheckConfig()
)

/**
 * `factCheck`: after a digest went out, a share ([sampleRate]) of its posts is sent to a judge
 * model together with EXACTLY the source text the render saw (the long-form full text included),
 * which lists the post's claims the sources do not support. Log-only: the verdict lands in
 * `fact_checks` and `/status` (🧐 line, ⚠️ with links to the latest unsupported posts once the
 * rate over [alert] passes the threshold); nothing is edited, deleted or held back. Runs on its
 * own thread, never delays delivery; a judge failure is a warning and no row.
 *
 * The post is Ukrainian and the sources are mostly English, so the judge must be multilingual;
 * with a free OpenRouter model give it a `fallback`. Expect false alarms on general context the
 * render may add ("studio X, the makers of Y") — review the first weeks by hand and tune
 * [promptFile] (keys `factCheck.system` / `factCheck.user`, see `prompts/factcheck.example.yaml`).
 */
data class FactCheckConfig(
    val enabled: Boolean = false,
    /** Share of delivered posts that are checked, 0.0–1.0. */
    val sampleRate: Double = 0.2,
    /** Categories to check; empty = every category. */
    val categories: List<String> = emptyList(),
    /** The judge model (required when enabled); may carry an on-failure `fallback` chain. */
    val judge: LlmOverride? = null,
    /** Own prompts; null = the built-in Ukrainian prompt. */
    val promptFile: String? = null,
    val alert: FactCheckAlertConfig = FactCheckAlertConfig(),
    /** How long `fact_checks` rows are kept, days. */
    val retentionDays: Long = 90,
    /** Checks waiting for the judge beyond the one in flight; more are dropped. */
    val queueSize: Int = 32
)

/** When `/status` raises ⚠️: at least [minChecks] checks in [windowDays] and an unsupported share above [maxUnsupportedRate]. */
data class FactCheckAlertConfig(
    val windowDays: Long = 7,
    val minChecks: Int = 10,
    val maxUnsupportedRate: Double = 0.15
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
     * How many reactions one 👍/👎 moderation press (`admin.moderation`) counts as. It adds to the
     * post's reactions (volume `+w`, valence `±w`) and, unlike reactions, is used at once —
     * no `attributionHours` wait. Deleting a post counts as a double "less" (`2w`).
     */
    val explicitWeight: Double = 3.0,
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
 * (extract / render / summarize / batchFallback / …) via
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
 * case (extract / render / summarize / batchFallback / …) via
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
    val intervalMinutes: Long? = null,
    /**
     * Optional path to a YAML file of site-chrome scrub rules (`rules: [{name, regex, host?,
     * dotAll?}]`) applied to extracted article text before the length cut. Replaces the built-in
     * list shipped as `scrub-rules.yaml` inside the jar. Must exist at load time.
     */
    val scrubRulesFile: String? = null,
    /**
     * Hosts (`host:port` or `host`) whose throttle key includes the first two path segments, so
     * each route of a feed converter such as RSSHub (`/telegram/channel`, `/twitter/user`) is rate-
     * limited on its own: a 429 from the Twitter route no longer cools down every converted feed.
     * Empty (default) = one throttle per host, as before.
     */
    val pathScopedThrottleHosts: List<String> = emptyList(),
    /**
     * Extra hosts (`host`, `host:port` or a URL) the SSRF guard lets resolve to a private / loopback
     * address, on top of the hosts of every configured feed URL (trusted automatically, per
     * `host:port`). Every other fetch — article pages, repoint targets, redirects, discovery,
     * badges — is refused on a non-public address. Empty (default) = only the feed hosts.
     */
    val privateHostAllowlist: List<String> = emptyList(),
    /**
     * How much of an enriched article's page text the per-article summarizer reads. The stored
     * description (and so Step 1 for an unsummarized article) stays cut at 1500 characters; the
     * summarizer used to see only those, while the median page is ~9K. A free OpenRouter model
     * is limited by requests, not tokens, so a longer input costs nothing there.
     */
    val summarizeInputChars: Int = DEFAULT_SUMMARIZE_INPUT_CHARS
) {
    companion object {
        const val DEFAULT_SUMMARIZE_INPUT_CHARS = 12_000
    }
}

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
            // Strict like the data classes: a typo (`fetchFullcontent: true`) used to vanish without a trace.
            node.fieldNames().asSequence().firstOrNull { it !in FeedConfig.KEYS }?.let { name ->
                throw UnrecognizedPropertyException.from(p, FeedConfig::class.java, name, FeedConfig.KEYS.toList())
            }
            val summarize = node.get("summarize")?.asText()?.takeIf { it.isNotBlank() }
            require(summarize == null || summarize in FeedConfig.SUMMARIZE_PROVIDERS) {
                "Invalid 'summarize' value '$summarize' — must be one of ${FeedConfig.SUMMARIZE_PROVIDERS}"
            }
            val kind = node.get("kind")?.asText()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            require(kind == null || kind in FeedConfig.KINDS) {
                "Invalid feed 'kind' value '$kind' — must be one of ${FeedConfig.KINDS}"
            }
            val minTextChars = node.get("minTextChars")?.let { n ->
                require(n.canConvertToInt() && n.asInt() >= 0) { "Feed 'minTextChars' must be a non-negative integer (got $n)" }
                n.asInt()
            }
            val exclude = node.get("exclude")?.let { n ->
                when {
                    n.isArray -> n.map { it.asText() }
                    n.isTextual -> listOf(n.asText())
                    else -> throw IllegalArgumentException("Feed 'exclude' must be a list of strings (got $n)")
                }
            }.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            FeedConfig(
                url = node.get("url")?.asText()?.takeIf { it.isNotBlank() }
                    ?: error("FeedConfig missing 'url' field"),
                fetchFullContent = node.get("fetchFullContent")?.asBoolean() ?: false,
                summarize = summarize,
                label = node.get("label")?.asText()?.takeIf { it.isNotBlank() },
                kind = kind,
                minTextChars = minTextChars,
                titlePrefix = node.get("titlePrefix")?.asText()?.takeIf { it.isNotEmpty() },
                exclude = exclude,
                repointLinks = node.get("repointLinks")?.asBoolean() ?: false
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
    /**
     * When set, each article from this feed is summarized before DB insert: a provider name pins that
     * provider (it wins over the category's `llm.summarize`, which may only refine its model); `auto`
     * leaves the choice to `llm.summarize` — including a weighted `providers:` split.
     */
    val summarize: String? = null,
    /** Human name in the feed-health `/status` section and report, and in the Step 1 source hint. */
    val label: String? = null,
    /**
     * What the feed carries — for feeds a converter (RSSHub) or a platform produces: `article`,
     * `social` (Telegram / X / Mastodon / Bluesky posts), `release` (GitHub releases), `video`
     * (YouTube). Null (default) = an ordinary feed, handled exactly as before. Any kind derives a
     * title from the post's first line when the item has none, and reads a YouTube-style
     * `media:group` thumbnail; `social` also replaces a title that only repeats the post text;
     * `social` / `release` / `video` hand Step 1 a `source` hint (`official account: …`).
     */
    val kind: String? = null,
    /** Items whose text (description, else title) is shorter than this are not ingested ("🔥", a sticker). */
    val minTextChars: Int? = null,
    /** Prepended to every title that does not already start with it (`"Ollama "` + `"v0.12.3"`). */
    val titlePrefix: String? = null,
    /** Items whose title contains any of these (case-insensitive) are not ingested (`-rc`, `nightly`). */
    val exclude: List<String> = emptyList(),
    /**
     * A short post (< 280 chars) with exactly one outbound link to a non-social, non-store page is
     * ingested as that page — like a reddit link post — with the post URL kept in the article meta.
     */
    val repointLinks: Boolean = false
) {
    companion object {
        /** `summarize: auto` — the category's `llm.summarize` (plain or a `providers:` split) picks the model. */
        const val SUMMARIZE_AUTO = "auto"
        val SUMMARIZE_PROVIDERS = setOf("openai", "openrouter", "anthropic", "claudecli", "codexcli", SUMMARIZE_AUTO)

        const val KIND_ARTICLE = "article"
        const val KIND_SOCIAL = "social"
        const val KIND_RELEASE = "release"
        const val KIND_VIDEO = "video"
        val KINDS = setOf(KIND_ARTICLE, KIND_SOCIAL, KIND_RELEASE, KIND_VIDEO)

        /** The keys the object form accepts ([FeedConfigDeserializer] rejects any other). */
        val KEYS: Set<String> = linkedSetOf(
            "url", "fetchFullContent", "summarize", "label", "kind", "minTextChars", "titlePrefix", "exclude", "repointLinks"
        )
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
     * Per-category override of `processing.minArticles`: the digest run is skipped while fewer
     * ready articles than this are queued. The gate sits BEFORE Step 1, so it is the only lever
     * for a category that must react to a lone big story quickly (see
     * [DigestConfig.breakingNewsworthiness], which only kicks in after Step 1 has scored). Null
     * = the global value.
     */
    val minArticles: Int? = null,
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
     * (`llm.batch`, or `batch: true` on `extract`) and is rejected at load
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
    val mute: List<String> = emptyList(),
    /**
     * Presentation of this category's outgoing posts: threading of follow-ups and the
     * deterministic footer lines (badges, first-mention intro, hashtags) appended to each bullet
     * right before it is sent. Touches ONLY the text that goes to Telegram — the stored summary
     * and the reaction-attribution rows keep the clean bullet. Null = posts are unchanged.
     */
    val post: PostConfig? = null,
    /**
     * Living pinned messages the bot maintains from this category's events — e.g. an upcoming
     * releases calendar fed by `release_date` / `delay` events. See [TrackerConfig]. Requires a
     * `dedup:` block (event types and dates come from Step 1).
     */
    val trackers: List<TrackerConfig> = emptyList()
)

/**
 * One tracker: a message in the category's channel that the bot edits after every digest
 * (and pins) with the current state of a subset of events — one line per entity (the Step 1
 * subject, else franchise), dated by the item's `eventDate`, linked to the latest post about
 * it. Entries in the past move to a "released this week" section for [releasedRetentionDays]
 * days, then vanish; entries beyond [horizonDays] are kept but not shown. The message is
 * edited only when its body changed; a deleted message is simply posted (and pinned) again.
 */
data class TrackerConfig(
    /** Stable id, `[a-z0-9_-]+`, unique within the category; keys the DB rows and `bot_state`. */
    val id: String,
    /** Header line of the message, e.g. `📅 Найближчі релізи`. */
    val title: String,
    /** Step 1 event types that feed this tracker, e.g. `[release_date, delay]`. */
    val eventTypes: List<String>,
    /** Show entries dated up to this many days ahead. */
    val horizonDays: Long = 120,
    /** Cap on upcoming lines in the message. */
    val maxEntries: Int = 20,
    /** Pin the message (silently) when it is (re)posted; needs the bot's edit-messages right in the channel. */
    val pinned: Boolean = true,
    /** How long a released (past-date) entry stays in the "released" section. */
    val releasedRetentionDays: Long = 7,
    /**
     * Show entries without a date (`TBA`) at the bottom of the list. Off by default: until the
     * extraction prompt emits `eventDate`, every entry is undated and the calendar would be a
     * bare list of titles; with dates flowing, TBA rows are mostly noise.
     */
    val showUndated: Boolean = false
)

/**
 * Per-category post presentation. Every sub-block is optional; a missing block leaves that
 * aspect exactly as before, and so does a block with `enabled: false`. See `.claude/plans/2026-09-chains-hashtags-breaking.md`.
 */
data class PostConfig(
    /** Send a `meaningful_update` bullet as a Telegram reply to the previous post about the same event. */
    val threads: ThreadsConfig? = null,
    /** Hashtag line under each bullet (static category tags + tags from Step 1's franchise/subject/eventType). */
    val hashtags: HashtagsConfig? = null,
    /** Live-data badge lines (Steam / HN / GitHub) under each bullet; see [BadgesConfig]. */
    val badges: BadgesConfig? = null,
    /** One Wikipedia line the first time a franchise / subject appears in the category; see [IntroConfig]. */
    val intro: IntroConfig? = null,
    /** Longer posts for the top-ranked events of a digest; see [LongFormConfig]. */
    val longForm: LongFormConfig? = null,
    /** Footer lines read from the story clusters (needs `storyIntel.enabled`); see [StoryLinesConfig]. */
    val storyLines: StoryLinesConfig? = null,
    /** `📰 Також: IGN · PC Gamer` — the other outlets the dedup folded into the event; see [AlsoConfig]. */
    val also: AlsoConfig? = null,
    /** Link the post to the official source (press release, studio blog) when the cluster has it; see [OfficialConfig]. */
    val official: OfficialConfig? = null,
    /** Better post pictures: og:image first, logos / avatars / small thumbnails dropped; see [PostImagesConfig]. */
    val images: PostImagesConfig? = null,
    /** `🔗 У 🖥 Tech: …` — the related post another category's channel already carries; see [CrossLinksConfig]. */
    val crossLinks: CrossLinksConfig? = null,
    /** `💬 …` — the editor persona's one-line remark under the top event, as an A/B test; see [PersonaConfig]. */
    val persona: PersonaConfig? = null
)

/**
 * The editor persona: one characterful line `💬 <remark>` under the digest's top event (the first
 * ranked item), from a separate cheap LLM call (`llm.persona`, else the render route; metered as
 * `PERSONA`) so the bullet itself is the same in both arms. At most one call per digest.
 *
 * A/B: the top event's arm is fixed by its key — `floorMod(eventKey.hashCode(), 100) < share×100`
 * is `persona`, the rest `control` (no call, no line); both are written to
 * `digest_messages.variant`, and `/status` compares reactions per arm (🧪 *Variants*). A remark
 * that fails a guard — longer than [maxChars], a URL, `[`, `]`, `#`, `` ` ``, `**`, a number the
 * topic does not have, several lines, one of [bannedPhrases] — or a failed / slow call
 * ([timeoutSeconds]) sends the post without the line and `variant` null, so it stays out of the
 * comparison. The style is the owner's: [promptFile] (keys `persona.system` / `persona.user`, see
 * `prompts/persona.example.yaml`) is required; without a readable file neither arm is tagged.
 */
data class PersonaConfig(
    val enabled: Boolean = true,
    val share: Double = 0.5,
    val maxChars: Int = 160,
    val promptFile: String = "",
    val timeoutSeconds: Long = 60,
    val bannedPhrases: List<String> = DEFAULT_BANNED_PHRASES
) {
    companion object {
        /** Meta framing the render prompt forbids too; matched case-insensitively. */
        val DEFAULT_BANNED_PHRASES: List<String> = listOf(
            "це важливо", "варто звернути увагу", "чому це цікаво", "як редактор", "як штучний інтелект"
        )
    }
}

/**
 * `🔗 У 🖥 Tech: [<subject>](https://t.me/…)`: the post about the same story that another
 * category's channel already carries. The event's Layer-3.5 vector (`event_embeddings`, stored
 * before delivery) is compared with the vectors of [categories] from the last [maxAgeDays] days,
 * same embedding model only; the best one at cosine >= [minSimilarity] whose event actually
 * reached its channel (`digest_messages`) is linked. No LLM, no new embedding call — this
 * category and every listed one need `semanticDedup.eventEnabled`. A listed category that shares
 * this category's channel is skipped. The link only points back (the newer post to the older).
 *
 * Every event with a candidate logs `[CrossLink] <key> ~ tech:<key2> sim=0.84`; [logOnly] keeps
 * the line out of the post, to calibrate the threshold first (wordings differ between channels).
 * `t.me/c/…` links (numeric channel ids) open only for members of that channel.
 */
data class CrossLinksConfig(
    val enabled: Boolean = true,
    val categories: List<String> = emptyList(),
    val minSimilarity: Double = 0.82,
    val maxAgeDays: Long = 3,
    val logOnly: Boolean = false
)

/**
 * Picture choice for a category with `enableImages` ([metifikys.fetch.ImagePicker] does the
 * judging: no logos, avatars, tracking pixels, `.svg` / `.ico`, widths under [minWidth] or aspect
 * ratios beyond 4:1 / 1:3 when the size is known; [ignore] adds URL substrings such as a site's
 * branded fallback image).
 *
 * At ingest every image the feed entry offers is collected (with its declared width / height)
 * and the first acceptable one is stored — not simply the first. At delivery the candidates are,
 * in order: with [ogFirst] the post article's `og:image` (one page fetch per post, under
 * [timeoutSeconds] each and [maxTotalSeconds] per digest, cached in `badge_cache` as `ogimage`),
 * the topic articles' stored images, then with [fromCluster] the images of the other articles of
 * the event's cluster. With [probe] a candidate of unknown size is sized from its first 64 KB
 * (a `Range` GET, header only) within the same budget. No acceptable picture → a text post.
 * [enabled] `false` = exactly as if the block were absent.
 */
data class PostImagesConfig(
    val enabled: Boolean = true,
    val ogFirst: Boolean = true,
    val fromCluster: Boolean = true,
    val minWidth: Int = 400,
    val probe: Boolean = false,
    val ignore: List<String> = emptyList(),
    val timeoutSeconds: Long = 5,
    val maxTotalSeconds: Long = 20
)

/**
 * Official sources first: when the event's batch cluster ([metifikys.model.ShortlistItem.sourceLinks])
 * holds an article on one of [domains] — a host equal to a listed domain or a subdomain of it, so
 * `blog.playstation.com` covers `eu.blog.playstation.com` — the post's link (and with it the image,
 * the long-form source and `covered_events.url`) points there instead of at the outlet Step 1
 * picked, which then shows up in the `📰 Також` line. The first listed domain some article
 * matches wins. [matchEntityDomain] also accepts a host with a label equal to the franchise /
 * subject (`hollowknight.com` for "Hollow Knight", keys of 5+ characters), after the listed ones.
 * Applied after the ranker; logs `[Official] <key>: ign.com → blog.playstation.com`.
 */
data class OfficialConfig(
    val enabled: Boolean = true,
    val domains: List<String> = emptyList(),
    val matchEntityDomain: Boolean = false
)

/**
 * `📰 Також: [IGN](…) · [PC Gamer](…)`: the other outlets of the digest batch whose articles Step 1
 * folded into the event (its `articleIndices` plus the in-batch `duplicate`s,
 * [metifikys.model.ShortlistItem.sourceLinks]). Those copies are never posted on their own; the
 * line only names them. The post's own host is left out, one link per host, at most [max].
 *
 * A source's name is [names] for its host (a key matches the host or a parent domain:
 * `ign.com` covers `uk.ign.com`), else the `label` of the feed it came from when the feed lives
 * on the same host, else the domain without its TLD, capitalised (`eurogamer.net` → `Eurogamer`).
 *
 * Every event logs `[Also] <eventKey>: N sources`; [logOnly] keeps the line out of the post, to
 * see first how often a batch cluster has more than one outlet. Late reprints (Layer 2, after the
 * post) are not added — that would need the post edited in place.
 */
data class AlsoConfig(
    val enabled: Boolean = true,
    val logOnly: Boolean = false,
    val max: Int = 3,
    val names: Map<String, String> = emptyMap()
)

/**
 * Deterministic footer facts about the story behind a bullet, read from `event_articles`
 * (`storyIntel.enabled` must be on — the clusters are what they count). Each line is optional.
 * They sit after the badges and the intro and before the hashtags, so a tight caption drops them
 * right after the hashtags.
 */
data class StoryLinesConfig(
    /** `📢 9 видань · 1 першоджерело`; see [HypeLineConfig]. */
    val hype: HypeLineConfig? = null,
    /** `🥇 першим був X (+2 год)`; see [FirstLineConfig]. */
    val first: FirstLineConfig? = null,
    /** `⚖️ Джерела розходяться: …`; see [ConflictLineConfig]. */
    val conflicts: ConflictLineConfig? = null,
    /** `🌀 анонсовано вчетверте, дати досі немає`; see [VaporwareLineConfig]. */
    val vaporware: VaporwareLineConfig? = null
)

/**
 * The vaporware counter, read from `event_history` (the delivered events, kept for years): for an
 * item of one of [announceTypes] without a date, the waves of undated announcements about the same
 * entity (the normalized subject) — a new wave after a gap of [minGapDays] — counting the current
 * one; from [minAnnouncements] the footer says `🌀 анонсовано вчетверте, дати досі немає (вперше:
 * 03.2025)`. A day or month date anywhere in the history means it is not vaporware; a quarter or
 * year date stops the count only with [softDatesStop].
 */
data class VaporwareLineConfig(
    val enabled: Boolean = true,
    val announceTypes: List<String> = listOf("major_announcement", "rumor", "leak", "dev_quote"),
    val minAnnouncements: Int = 3,
    val minGapDays: Long = 30,
    val softDatesStop: Boolean = false
)

/**
 * Sources disagree: the story cluster's articles state different dates, prices or head counts
 * (deterministic slot extraction from title + teaser, [metifikys.story.FactSlots]). Two values
 * conflict when each is stated by at least [minSupport] outlets and they are incompatible — dates
 * whose periods do not overlap even widened by [dateToleranceDays] (`Q4 2026` and 14.10.2026
 * agree), prices in one currency or counts more than 1% apart. An article with several disagreeing
 * values in one slot (deluxe editions, regional dates) is skipped for that slot.
 *
 * [mode] `log` (the default) only writes `[StoryConflict]` lines — and `[StoryConflict][late]` when a
 * Layer 2 reprint states a value the cluster does not — so the precision can be checked before
 * anything is posted; `post` also adds the footer line
 * `⚖️ Джерела розходяться: дата 14.10.2026 (ign.com) чи 21.10.2026 (pcgamer.com)`. For `post`,
 * [minSupport] 2 is recommended.
 */
data class ConflictLineConfig(
    val enabled: Boolean = true,
    val mode: String = MODE_LOG,
    val minSupport: Int = 1,
    val dateToleranceDays: Int = 1
) {
    companion object {
        const val MODE_LOG = "log"
        const val MODE_POST = "post"
    }
}

/**
 * Who of our sources had the story first: the outlet whose earliest article has the smallest
 * publish time (the feed date, or our `fetched_at` when the feed date lies more than 10 minutes in
 * its future), shown when the story has [minOutlets] outlets and the lead over the second outlet
 * is at least [minLeadMinutes] (anything closer is a tie). Together with `hype` both facts share
 * one line: `📢 9 видань · 1 першоджерело · 🥇 X (+2 год)`.
 */
data class FirstLineConfig(
    val enabled: Boolean = true,
    val minOutlets: Int = 3,
    val minLeadMinutes: Long = 30
)

/**
 * The hype detector: how many outlets (distinct domains) carried the story and how many of them
 * are original sources rather than retellings — a retelling credits another outlet in its title or
 * teaser (`via`, `according to`, `як повідомляє`, …) or is a near-copy (cosine >=
 * [reprintSimilarity], vectors from `semanticDedup`) of an earlier article of another outlet. When
 * every article credits the same outlet, the line says `усі з посиланням на X`.
 *
 * At delivery the cluster is mostly the digest's own batch (late reprints arrive after the post),
 * so the line shows only for stories that already filled the batch: [minOutlets].
 */
data class HypeLineConfig(
    val enabled: Boolean = true,
    val minOutlets: Int = 4,
    val reprintSimilarity: Double = 0.95
)

/**
 * Long-form posts: the top-ranked events of a digest get a two-paragraph body instead of the
 * usual 1–3 sentences. Which events is decided in code, not by the renderer: walking the
 * post-ranker shortlist in order, the first [maxPerDigest] items whose composite score
 * (newsworthiness/digestFit weighted as in `dedup.digest.ranker`) is >= [minComposite] are marked
 * `depth = "long"`. For those the article page is fetched again with a [sourceChars] budget, so
 * the renderer has real material for the extra text instead of padding a 1000-char RSS blurb.
 *
 * A long post goes out as a text message (4096-char budget) with the article image as a large
 * link preview above the text, instead of a photo whose caption is capped at 1024 chars.
 * The render prompt must handle the `depth` field (see `prompts/games-dedup.example.yaml`).
 */
data class LongFormConfig(
    val enabled: Boolean = true,
    /** Cap on long posts per delivered digest. */
    val maxPerDigest: Int = 1,
    /** Composite score (0–10) an event needs to qualify. */
    val minComposite: Double = 8.0,
    /** Characters of the refetched article text passed to the renderer for a long item. */
    val sourceChars: Int = 5000,
    /** Publish the long post as a telegra.ph page (Instant View) as well; see [LongFormTelegraphConfig]. */
    val telegraph: LongFormTelegraphConfig? = null
)

/**
 * `post.longForm.telegraph`: the long post is also published as a telegra.ph page (title, picture,
 * every paragraph, the sources, a link to the channel), which Telegram opens with ⚡ Instant View.
 * [mode] `teaser`: the channel post keeps the headline, the first paragraph, `⚡ [Читати
 * повністю](…)`, the source and the footer, with the page as its large link preview; `full`: the
 * post stays as it is and only gains the ⚡ line; `link`: the channel post is only the page itself —
 * its Instant View card, no text, no source link, no footer (the page carries the text and the
 * sources). Any Telegraph failure or timeout (the global
 * [TelegraphConfig.timeoutSeconds]) sends today's long post instead. Pages are public and can't be
 * deleted through the API, only edited.
 */
data class LongFormTelegraphConfig(
    val enabled: Boolean = true,
    val mode: String = MODE_TEASER
) {
    companion object {
        const val MODE_TEASER = "teaser"
        const val MODE_FULL = "full"
        const val MODE_LINK = "link"
    }
}

/**
 * The telegra.ph account the long-form pages are published under. The access token is
 * `TELEGRAPH_TOKEN` (env), else [accessToken], else the one stored in `bot_state`
 * (`telegraph:token`); with none of them the bot calls `createAccount` once ([shortName],
 * [authorName], [authorUrl]) and stores the token there. The token is never logged. [authorUrl]
 * blank = the category's channel link (`@username` channels). [timeoutSeconds] caps each API call.
 */
data class TelegraphConfig(
    val accessToken: String = "",
    val shortName: String = "RssNewsBot",
    val authorName: String = "RSS News",
    val authorUrl: String = "",
    val timeoutSeconds: Long = 10
)

/**
 * First-mention intro: `📚 Team Cherry: австралійська студія відеоігор · Вікіпедія`. The
 * entity is the Step 1 franchise (else the subject); its first appearance in the category is
 * remembered in `entity_intros`, so the line shows once per [repeatAfterDays]. Wikipedia's short
 * description is used when present, else the first sentence of the summary — never the LLM.
 */
data class IntroConfig(
    val enabled: Boolean = true,
    /** Wikipedia languages to try, in order; the text is printed as found. */
    val languages: List<String> = listOf("uk"),
    /** Cap on intro lines per delivered digest. */
    val maxPerDigest: Int = 2,
    /** A name introduced less than this many days ago is not introduced again. */
    val repeatAfterDays: Long = 365,
    /**
     * An article counts only when its title, description or opening sentence contains one of these
     * (case-insensitive substrings) — "Rust" the game is not Rust the language, "Entropy" the game is
     * not entropy. Off-topic hits trigger one more search with a "відеогра" / "video game" hint.
     * Empty = no check. The default fits a games category.
     */
    val topicWords: List<String> = DEFAULT_TOPIC_WORDS
) {
    companion object {
        val DEFAULT_TOPIC_WORDS = listOf(
            "відеогр", "комп'ютерн", "ігор", "гра ", "гру ", "ігрова", "ігрове", "розробник", "видав", "студі",
            "video game", "videogame", "game developer", "game publisher", "developer", "publisher", "studio",
            "franchise", "role-playing game", "mmorpg", "esports"
        )
    }
}

/**
 * Which footer badges a category shows. Each is a deterministic line built from a public API
 * (no key, cached, hard timeout — see `badges:` at the top level for the knobs):
 * - [steam]: `🎮 Steam: ₴899 (−20%) · 92% позитивних (12 345) · реліз …` for game events with a subject;
 * - [hn]: `🔥 HN: 512 · 340 коментарів` for articles that came through an hnrss feed;
 * - [github]: `⭐ GitHub: 12.3k · Rust · оновлено …` when the article link is a repository;
 * - [trailer]: `▶️ [Трейлер](https://youtu.be/…)` for the event types in `badges.trailerEventTypes` when
 *   the article embeds a YouTube video whose title (YouTube oEmbed) names the subject / franchise;
 * - [ukLocalization] (id `uk_lang`): `🇺🇦 Українська: інтерфейс, субтитри, озвучка` for a Steam game
 *   (Steam's GetItems; no line without Ukrainian unless `badges.ukLocalizationShowMissing`);
 * - [deck]: `🕹 Steam Deck: ✅ перевірено · ProtonDB: Platinum (354 звіти)` for a Steam game (also on `patch`);
 * - [critic]: `🏆 Metacritic: 90` on release / review events of a released Steam game (the Metascore in
 *   Steam's appdetails), plus OpenCritic with `badges.openCriticKey`;
 * - [cve]: `🛡 CVE-2021-44228: CVSS 10.0, критична · експлуатується (CISA KEV)` when the bullet names a CVE ID
 *   (NVD API 2.0; any category, meant for tech);
 * - [steamTrend] (id `steam_trend`): `📈 Steam: №4 у хітах продажу · №12 серед найочікуваніших` when a Steam
 *   game is in the top `badges.steamTrendTopN` of a store chart.
 */
data class BadgesConfig(
    val steam: Boolean = false,
    val hn: Boolean = false,
    val github: Boolean = false,
    val trailer: Boolean = false,
    val ukLocalization: Boolean = false,
    val deck: Boolean = false,
    val critic: Boolean = false,
    val cve: Boolean = false,
    val steamTrend: Boolean = false
) {
    /** Provider ids to run for this category, matching [metifikys.enrich.BadgeProvider.id]. */
    val enabledIds: Set<String>
        get() = buildSet {
            if (steam) add("steam")
            if (ukLocalization) add("uk_lang")
            if (deck) add("deck")
            if (steamTrend) add("steam_trend")
            if (critic) add("critic")
            if (hn) add("hn")
            if (github) add("github")
            if (trailer) add("trailer")
            if (cve) add("cve")
        }
}

/**
 * Global knobs for the badge providers (per-category on/off lives in `post.badges`).
 * Lookups run in the background under a hard budget, so a slow API delays nothing.
 */
data class BadgeSettings(
    /** Per-provider wait per bullet, seconds. */
    val timeoutSeconds: Long = 5,
    /** Total badge time per delivered digest, seconds; once spent, remaining bullets go out without badges. */
    val maxTotalSeconds: Long = 15,
    /** Optional GitHub token (env `GITHUB_TOKEN` wins): 5000 requests/hour instead of 60. */
    val githubToken: String? = null,
    /** Steam store country code — prices in that currency (UA → hryvnia). */
    val steamCountry: String = "UA",
    /** Steam store language for localized release dates. */
    val steamLanguage: String = "ukrainian",
    /** Step 1 event types that get the ▶️ trailer badge (`post.badges.trailer`). */
    val trailerEventTypes: List<String> = listOf("major_announcement", "release_date", "major_dlc_expansion", "delay"),
    /**
     * When the topic's articles carry no video from ingest, fetch the post article's page at
     * delivery (within the badge budget; the result is cached 7 days) and look for one there.
     */
    val trailerFetchOnDelivery: Boolean = true,
    /** `post.badges.ukLocalization`: also say `🇺🇦 Без української` when a Steam game has none (noise for most indies). */
    val ukLocalizationShowMissing: Boolean = false,
    /**
     * Optional RapidAPI key for OpenCritic (env `OPENCRITIC_RAPIDAPI_KEY` wins): adds the OpenCritic
     * score to `post.badges.critic`. Empty (default) = Metacritic from Steam only, no OpenCritic request.
     */
    val openCriticKey: String? = null,
    /** Optional NVD API key (env `NVD_API_KEY` wins) for `post.badges.cve`: 50 requests / 30 s instead of 5. */
    val nvdApiKey: String? = null,
    /** `post.badges.steamTrend`: a chart rank counts up to this place (1..100). */
    val steamTrendTopN: Int = 25,
    /**
     * `post.badges.hn`: no badge below this many points — a fresh or ignored submission (hnrss
     * `newest?q=…` feeds bring those) would show "🔥 HN: 1 · 0 коментарів". Re-checked hourly.
     */
    val hnMinPoints: Int = 10,
    /**
     * `post.badges.github`: no badge below this many stars — a fresh "Show HN" repo would show
     * "⭐ 1". Re-checked daily, so a repo that takes off gets it later.
     */
    val githubMinStars: Int = 50
)

data class ThreadsConfig(
    val enabled: Boolean = true,
    /**
     * A previous post older than this is not replied to — a "continuation" of a month-old post
     * reads oddly, and `digest_messages` keeps 90 days anyway.
     */
    val maxAgeDays: Long = 30
)

data class HashtagsConfig(
    /**
     * Static tags appended to every post of the category, space-separated, e.g. `"#gaming"` or
     * `"#tech #ai"`. Each must be a well-formed hashtag (`#` + letters/digits/underscore).
     * The only tag source for categories without a `dedup:` block (no Step 1 fields).
     */
    val category: String? = null,
    /** Tag from the Step 1 `franchise` field (`#silksong`). */
    val fromFranchise: Boolean = true,
    /** Tag from the Step 1 `event_type` enum (`#release_date`) — the most stable of the three. */
    val fromEventType: Boolean = false,
    /** Tag from the Step 1 `subject` field. Off by default: tech subjects are free-text prose that changes every cycle. */
    val fromSubject: Boolean = false,
    /** Cap on generated (non-static) tags per post. */
    val max: Int = 3
)

/**
 * Lightweight reference to a provider+model: `provider` names a top-level block
 * (`openai` or `openrouter`); `model` is the model id passed to that endpoint. A blank
 * `model` is allowed for `openrouter` (= the `openrouter.models` failover ladder) and the
 * CLI providers (= the CLI's default model).
 * `batch=true` opts the `extract` use case into the Batch API
 * (50% cost, async). Silently ignored on `render`/`batch`/`summarize`/`batchFallback`
 * — those fields encode batch-ness via their field name. Default `false` ⇒ zero
 * migration; all existing YAMLs are unchanged.
 *
 * [fallback] is the on-actual-failure secondary for this slot: when the primary call
 * fails (usage/credit limit, expired login, bad model, exhausted-retry timeout), the
 * SAME call is retried against the fallback provider+model. Honored on the sync use
 * cases only (`render` / `extract` / `summarize` / `batchFallback` / …)
 * and wired in by [metifikys.ai.LlmClientsFactory] via [metifikys.ai.FallbackLlmClient].
 * Distinct from the queue-backpressure `batchFallback` slot and the weighted [providers]
 * split — neither of those reacts to a provider actually failing. May chain (a fallback may
 * itself have a fallback, up to a small depth); each link must differ from the others and
 * may not set `batch=true` (the fallback always runs on the sync path).
 *
 * [providers] turns the slot into a weighted split instead of one provider: every call of
 * the slot goes to one entry, chosen so that the day's calls follow the entries' [percent]
 * shares (20/10/70 → of 10 calls exactly 2/1/7). The tallies live in `bot_state` per
 * category + slot, survive a restart and start over at local midnight — see
 * [metifikys.ai.LlmSplitter]. An entry is a full override of its own (`batch`, `fallback`,
 * blank `model` for the OpenRouter ladder); a split itself carries only `providers`.
 * On `summarize` the feed decides first: `summarize: auto` routes through this override (split
 * included), a provider name pins that provider and uses only the entry on it.
 */
data class LlmOverride(
    val provider: String = "",
    val model: String = "",
    val batch: Boolean = false,
    val fallback: LlmOverride? = null,
    /** This entry's share of the slot's calls, 1..100 — only on an entry of [providers]. */
    val percent: Int? = null,
    /** Weighted split across several overrides (percents sum to 100); null = a single provider. */
    val providers: List<LlmOverride>? = null
) {
    /** The concrete overrides this slot may route to: the split's entries, or this override itself. */
    fun legs(): List<LlmOverride> = providers ?: listOf(this)
}

/**
 * Per-use-case overrides inside a category. Any null field falls back to the
 * default resolution for that use case (see LlmClients).
 */
data class CategoryLlmOverrides(
    val extract: LlmOverride? = null,
    /**
     * Removed: the Step 1 A/B pair is now `extract: { providers: [...] }` with two 50% entries.
     * Kept only so a config still carrying it fails at load with that hint instead of an
     * "unrecognized field".
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
    val batchFallback: LlmOverride? = null,
    /**
     * The editor-persona remark (`post.persona`), one short sync call per digest. Null falls back
     * to the [render] override, then to the global sync default; metered as `PERSONA` either way.
     */
    val persona: LlmOverride? = null,
    /**
     * The update diff (`dedup.digest.updateDiff`), one short sync call per `meaningful_update` in
     * the render shortlist. Null falls back to the [render] override, then to the global sync
     * default; metered as `UPDATE_DIFF` either way.
     */
    val updateDiff: LlmOverride? = null,
    /** Shadow run of a second model / prompt next to the live one, stored for comparison only; see [ShadowConfig]. */
    val shadow: ShadowConfig? = null
)

/**
 * `llm.shadow`: a "B" model and/or prompt runs on exactly the input the live "A" gets, in parallel,
 * and only A reaches the channel; both outputs land in `shadow_runs` for a side-by-side look
 * (`bench/shadow-dump.mjs`, `/status` 🌓 line). Unlike a `providers:` split (each call
 * goes to one side, and that one is published), both sides see the same batch and nothing of B is published.
 *
 * [extract] shadows Step 1 (same articles and covered context, B's own prompt); [render] shadows
 * Step 2 on A's shortlist, so a render prompt change is judged on the same events. B's output goes
 * through the same post-processing as A's (Step 1: URL filter, cooldown, cap, ranker without the
 * audience term; render: the delivery's URL whitelist), nothing is written besides the row. B runs
 * on its own single thread per category with a queue of [queueSize]; when that is full the run is
 * dropped (`[Shadow] dropped (busy)`) — B never delays A. A CLI shadow doubles that quota's use:
 * lower [sampleRate] for an expensive B. Needs `dedup:`; `batch: true` is not allowed here.
 * [enabled] `false` = exactly as if the block were absent (nothing runs, nothing is validated).
 */
data class ShadowConfig(
    val enabled: Boolean = true,
    /** B for Step 1; null = no Step 1 shadow. */
    val extract: LlmOverride? = null,
    /** B for the render; null = no render shadow. */
    val render: LlmOverride? = null,
    /** B's prompt file (same keys as `dedup.promptFile`); null = A's prompts, only the model differs. */
    val promptFile: String? = null,
    /** Share of A's calls that also get a B run, 0.0–1.0. */
    val sampleRate: Double = 1.0,
    /** B runs waiting per category beyond the one in flight; more are dropped. */
    val queueSize: Int = 4
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
    val meaningfulUpdateCooldownMinutes: Long = 90,
    /**
     * Breaking lane. When non-null and the post-ranker shortlist holds at least one item whose
     * newsworthiness (raw Step 1 score, not the affinity-adjusted composite) is >= this, the
     * weak-shortlist hold-back ([minStrongItems] / [maxWaitHours] / [minItemsOnForcePublish])
     * is skipped and the whole shortlist is rendered now. Without it a single top story on a
     * quiet day waits for company — the most important news of the day is the one that waits
     * longest. Null (default) = off. Suggested 9: [newsworthinessOverride] (8) already lets an
     * item past the digestFit floor and the cooldown; 9–10 is what Step 1 reserves for the
     * genuinely big announcements.
     */
    val breakingNewsworthiness: Int? = null,
    /**
     * Hard cap on posts about one story: a `meaningful_update` is dropped when the channel
     * already carries this many posts (original + updates, counted in `digest_messages` by the
     * item's own AND related event key) within [updateWindowDays]. Deliberately NOT subject to
     * [newsworthinessOverride] — the repeats this exists for (four DOOM crunch posts, tech
     * follow-ups scored 8–9) all cleared the override, so the cooldown never held them. Null
     * (default) = no cap.
     */
    val maxPostsPerEvent: Int? = null,
    /** Window for [maxPostsPerEvent], days. */
    val updateWindowDays: Long = 7,
    /** Once a week one important event the audience's taste would have left out; see [AntiBubbleConfig]. */
    val antiBubble: AntiBubbleConfig? = null,
    /** A `meaningful_update` is posted only with facts the channel has not posted yet; see [UpdateDiffConfig]. */
    val updateDiff: UpdateDiffConfig? = null
)

/**
 * Update diff (`dedup.digest.updateDiff`): every `meaningful_update` in the final render shortlist
 * is compared, in one LLM call (`llm.updateDiff`, else the render route; metered as `UPDATE_DIFF`),
 * with what the channel already posted about the story — the last [maxPreviousPosts] posts of the
 * story chain within [windowDays] (`digest_messages.text`; older posts without text fall back to
 * the covered event's subject + coreFact) — against the item's source articles (at most
 * [maxSourceChars]). The model lists only the concrete facts absent from every previous post:
 *
 *  - none → the update is dropped (`[UpdateDiff] skip <key>: <reason>`), its articles reconciled
 *    like any other event that left the shortlist;
 *  - some → the item carries them as `newFacts`, the render writes only those as a reply to the
 *    earlier post (the render prompt has to say so — PromptValidator warns), never long-form.
 *
 * Step 1 alone labels retellings as updates (27.09: an itc.ua copy of a story posted the day
 * before, "up to 12 companies via the Avengers Labs contest", came out as a third post), and
 * the cooldown lets anything with newsworthiness >= `newsworthinessOverride` through. Fail-open:
 * an LLM failure, a timeout ([timeoutSeconds]) or an unparseable answer keeps the item as it was.
 * [logOnly] (default) makes the call and logs `[UpdateDiff][LOG-ONLY]` but changes nothing.
 * Prompts: built-in, or `updateDiff.system` / `updateDiff.user` in the category's dedup promptFile.
 */
data class UpdateDiffConfig(
    val enabled: Boolean = true,
    val logOnly: Boolean = true,
    val maxPreviousPosts: Int = 3,
    val windowDays: Long = 14,
    val timeoutSeconds: Long = 60,
    val maxSourceChars: Int = 4000
)

/**
 * Anti-bubble (`dedup.digest.antiBubble`): at most once per [intervalDays], one event the ranker
 * left out of the digest (the `maxDigestItems` / `maxPerFranchise` caps, or the affinity term) is
 * added past `maxDigestItems` when its newsworthiness is at least [minNewsworthiness] and its
 * audience affinity (`audience_affinity`, the ranker's item score) at most [maxAffinity] — a story
 * that matters but sits outside what the channel usually picks. Events under the digestFit floor
 * and events whose subject is already in the digest are never picked. The post carries [label]
 * and `digest_messages.variant = explore`, so its reactions can be read apart; the interval clock
 * (`bot_state`, `antibubble:<category>`) starts only when the post actually went out.
 *
 * Needs `ranker.enabled` and affinity data (`feedback.enabled`); without rows for the category
 * nothing is picked. Step 1 itself also sees `{{AUDIENCE_SIGNALS}}` and may score such stories
 * lower — that part of the bubble is not visible here. Logs `[AntiBubble]`.
 */
data class AntiBubbleConfig(
    val enabled: Boolean = true,
    val intervalDays: Long = 7,
    val minNewsworthiness: Int = 8,
    val maxAffinity: Double = -0.05,
    val label: String = "🧭 Поза звичним вибором"
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
    private val MISSING_PARAMETER = Regex("""missing \(therefore NULL\) value for creator parameter (\w+)""")

    // A single value where a list is expected (`userIds: 296937693`) reads as a one-element list.
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)

    fun load(path: String): AppConfig {
        val file = File(path)
        require(file.exists() && file.isFile && file.canRead()) { "Config file not found or not readable: $path" }
        val config = try {
            mapper.readValue(file, AppConfig::class.java)
        } catch (e: UnrecognizedPropertyException) {
            throw IllegalArgumentException(unknownKeyMessage(e), e)
        } catch (e: MismatchedInputException) {
            throw IllegalArgumentException(wrongTypeMessage(e), e)
        }
        return applyEnvOverrides(config)
    }

    /** `admin.userIds`, `categories.gaming.feeds[2].kind` — the key path of a Jackson error. */
    private fun keyPath(e: JsonMappingException): String = e.path.joinToString("") { ref ->
        when {
            ref.fieldName != null -> ".${ref.fieldName}"
            ref.index >= 0 -> "[${ref.index}]"
            else -> ".?"
        }
    }.removePrefix(".")

    /**
     * Human form of Jackson's type-mismatch error: `Config key 'admin.userIds' (line 6, column 12):
     * expected a list of whole numbers, e.g. [123456789]` instead of the raw deserializer message.
     */
    internal fun wrongTypeMessage(e: MismatchedInputException): String {
        val where = e.location?.let { " (line ${it.lineNr}, column ${it.columnNr})" } ?: ""
        // jackson-module-kotlin reports a missing non-null parameter this way (its dedicated
        // exception type is deprecated), and the path stops at the enclosing block.
        MISSING_PARAMETER.find(e.originalMessage.orEmpty())?.let { m ->
            val name = m.groupValues[1]
            val path = keyPath(e).let { if (it.isEmpty()) name else if (it.endsWith(".$name")) it else "$it.$name" }
            return "Config key '$path'$where: required, but missing or null"
        }
        val type = e.targetType
        val expected = when {
            type == null -> "a different value"
            Collection::class.java.isAssignableFrom(type) -> "a list, e.g. [a, b]"
            type == java.lang.Long::class.java || type == java.lang.Long.TYPE ||
                type == java.lang.Integer::class.java || type == Integer.TYPE -> "a whole number"
            type == java.lang.Double::class.java || type == java.lang.Double.TYPE -> "a number"
            type == java.lang.Boolean::class.java || type == java.lang.Boolean.TYPE -> "true or false"
            type == String::class.java -> "a text value"
            type.isEnum -> "one of ${type.enumConstants.joinToString(" | ")}"
            Map::class.java.isAssignableFrom(type) -> "a key: value block"
            else -> "a `${type.simpleName}` block"
        }
        val value = (e as? InvalidFormatException)?.value?.let { " — got '$it'" } ?: ""
        return "Config key '${keyPath(e)}'$where: expected $expected$value"
    }

    /**
     * Human form of Jackson's unknown-property error: the full key path and the keys that ARE
     * accepted there, e.g. `unknown config key categories.gaming.post.hashtag — keys here: badges,
     * hashtags, intro, longForm, threads (did you mean hashtags?)`.
     */
    internal fun unknownKeyMessage(e: UnrecognizedPropertyException): String {
        val path = keyPath(e)
            .let { if (it.endsWith(e.propertyName)) it else if (it.isEmpty()) e.propertyName else "$it.${e.propertyName}" }
        val known = e.knownPropertyIds.orEmpty().map { it.toString() }.sorted()
        val name = e.propertyName
        val guess = known.minByOrNull { editDistance(it.lowercase(), name.lowercase()) }
            ?.takeIf { editDistance(it.lowercase(), name.lowercase()) <= maxOf(2, name.length / 3) }
        return "Unknown config key '$path'" +
            (if (known.isNotEmpty()) " — keys accepted here: ${known.joinToString()}" else "") +
            (guess?.let { " (did you mean '$it'?)" } ?: "")
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
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
        require(config.processing.shadowRetentionDays >= 1) {
            "processing.shadowRetentionDays must be >= 1 (got ${config.processing.shadowRetentionDays})"
        }
        config.factCheck.takeIf { it.enabled }?.let { fc ->
            val judge = requireNotNull(fc.judge) { "factCheck.judge is required when factCheck.enabled (provider + model of the judge)" }
            validateOverride("factCheck", "judge", judge, config.openrouter, config.anthropic, config.claudeCli, config.codexCli)
            require(judge.legs().none { it.batch }) { "factCheck.judge: batch=true is not supported — the judge runs synchronously" }
            require(fc.sampleRate in 0.0..1.0) { "factCheck.sampleRate must be in [0.0, 1.0] (got ${fc.sampleRate})" }
            require(fc.retentionDays >= 1) { "factCheck.retentionDays must be >= 1 (got ${fc.retentionDays})" }
            require(fc.queueSize >= 1) { "factCheck.queueSize must be >= 1 (got ${fc.queueSize})" }
            require(fc.alert.windowDays >= 1) { "factCheck.alert.windowDays must be >= 1 (got ${fc.alert.windowDays})" }
            require(fc.alert.minChecks >= 1) { "factCheck.alert.minChecks must be >= 1 (got ${fc.alert.minChecks})" }
            require(fc.alert.maxUnsupportedRate in 0.0..1.0) {
                "factCheck.alert.maxUnsupportedRate must be in [0.0, 1.0] (got ${fc.alert.maxUnsupportedRate})"
            }
            for (c in fc.categories) {
                require(c in config.categories) { "factCheck.categories references unknown category '$c' — must match a key under categories:" }
            }
        }
        config.archive?.let { a ->
            require(a.rotation in ArchiveConfig.ROTATIONS) {
                "archive.rotation must be one of ${ArchiveConfig.ROTATIONS} (got '${a.rotation}')"
            }
            require(a.embeddings in ArchiveConfig.EMBEDDING_MODES) {
                "archive.embeddings must be one of ${ArchiveConfig.EMBEDDING_MODES} (got '${a.embeddings}')"
            }
            require(a.dir.isNotBlank()) { "archive.dir must not be blank" }
            require(!a.dir.contains("..")) { "archive.dir must not contain '..' (path traversal): ${a.dir}" }
            require(a.keepFiles >= 0) { "archive.keepFiles must be >= 0 (got ${a.keepFiles}); 0 keeps every file" }
            require(a.minFreeDiskMb >= 0) { "archive.minFreeDiskMb must be >= 0 (got ${a.minFreeDiskMb})" }
            for (t in a.tables) {
                require(t in ArchiveConfig.ARCHIVABLE_TABLES) {
                    "archive.tables: '$t' is not archivable — allowed: ${ArchiveConfig.ARCHIVABLE_TABLES}"
                }
            }
        }

        config.fetcher.let { f ->
            f.scrubRulesFile?.let { path ->
                require(java.io.File(path).isFile) { "fetcher.scrubRulesFile '$path' does not exist or is not a file" }
            }
            require(f.maxConcurrentFetches >= 1) {
                "fetcher.maxConcurrentFetches must be >= 1 (got ${f.maxConcurrentFetches})"
            }
            require(f.maxAttempts >= 1) { "fetcher.maxAttempts must be >= 1 (got ${f.maxAttempts})" }
            require(f.summarizeInputChars >= 1500) {
                "fetcher.summarizeInputChars must be >= 1500, the stored description length (got ${f.summarizeInputChars})"
            }
            for (entry in f.privateHostAllowlist) {
                try {
                    metifikys.fetch.UrlGuard.parseTrustEntry(entry)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("fetcher.privateHostAllowlist: ${e.message}")
                }
            }
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
            require(fb.explicitWeight > 0.0) { "feedback.explicitWeight must be > 0 (got ${fb.explicitWeight})" }
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

        validateAdmin(config)
        validateHealth(config)
        validateFeedHealth(config)
        validateStoryIntel(config)

        val badges = config.badges.copy(
            githubToken = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() } ?: config.badges.githubToken,
            openCriticKey = System.getenv("OPENCRITIC_RAPIDAPI_KEY")?.takeIf { it.isNotBlank() } ?: config.badges.openCriticKey,
            nvdApiKey = System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() } ?: config.badges.nvdApiKey
        )
        require(badges.timeoutSeconds >= 1) { "badges.timeoutSeconds must be >= 1 (got ${badges.timeoutSeconds})" }
        require(badges.maxTotalSeconds >= badges.timeoutSeconds) {
            "badges.maxTotalSeconds (${badges.maxTotalSeconds}) must be >= timeoutSeconds (${badges.timeoutSeconds})"
        }
        require(badges.steamCountry.isNotBlank()) { "badges.steamCountry must not be blank" }
        require(badges.steamLanguage.isNotBlank()) { "badges.steamLanguage must not be blank" }
        require(badges.trailerEventTypes.none { it.isBlank() }) { "badges.trailerEventTypes must not contain blank entries" }
        require(badges.steamTrendTopN in 1..100) { "badges.steamTrendTopN must be in 1..100 (got ${badges.steamTrendTopN})" }
        require(badges.hnMinPoints >= 0) { "badges.hnMinPoints must be >= 0 (got ${badges.hnMinPoints})" }
        require(badges.githubMinStars >= 0) { "badges.githubMinStars must be >= 0 (got ${badges.githubMinStars})" }

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
            if (category.trackers.isNotEmpty()) {
                require(category.dedup != null) {
                    "Category '$name' configures trackers but has no dedup: block — trackers are fed by Step 1 event types and dates"
                }
                val ids = mutableSetOf<String>()
                for (t in category.trackers) {
                    require(Regex("""[a-z0-9_-]+""").matches(t.id)) {
                        "Category '$name' trackers[].id '${t.id}' must match [a-z0-9_-]+"
                    }
                    require(ids.add(t.id)) { "Category '$name' trackers: duplicate id '${t.id}'" }
                    require(t.title.isNotBlank()) { "Category '$name' tracker '${t.id}': title must not be blank" }
                    require(t.eventTypes.isNotEmpty() && t.eventTypes.none { it.isBlank() }) {
                        "Category '$name' tracker '${t.id}': eventTypes must list at least one non-blank event type"
                    }
                    require(t.horizonDays >= 1) { "Category '$name' tracker '${t.id}': horizonDays must be >= 1 (got ${t.horizonDays})" }
                    require(t.maxEntries >= 1) { "Category '$name' tracker '${t.id}': maxEntries must be >= 1 (got ${t.maxEntries})" }
                    require(t.releasedRetentionDays >= 0) {
                        "Category '$name' tracker '${t.id}': releasedRetentionDays must be >= 0 (got ${t.releasedRetentionDays})"
                    }
                }
            }
            category.post?.let { post ->
                post.threads?.let { t ->
                    require(t.maxAgeDays >= 1) { "Category '$name' post.threads.maxAgeDays must be >= 1 (got ${t.maxAgeDays})" }
                }
                post.storyLines?.let { s ->
                    val anyOn = s.hype?.enabled == true || s.first?.enabled == true || s.conflicts?.enabled == true ||
                        s.vaporware?.enabled == true
                    require(!anyOn || config.storyIntel.enabled) {
                        "Category '$name' post.storyLines needs storyIntel.enabled: true — the lines read the story clusters it records"
                    }
                    require(!anyOn || category.dedup != null) {
                        "Category '$name' post.storyLines needs a dedup: block — story clusters come from Step 1"
                    }
                    s.hype?.let { h ->
                        require(h.minOutlets >= 2) { "Category '$name' post.storyLines.hype.minOutlets must be >= 2 (got ${h.minOutlets})" }
                        require(h.reprintSimilarity in 0.0..1.0) {
                            "Category '$name' post.storyLines.hype.reprintSimilarity must be in [0.0, 1.0] (got ${h.reprintSimilarity})"
                        }
                    }
                    s.first?.let { f ->
                        require(f.minOutlets >= 2) { "Category '$name' post.storyLines.first.minOutlets must be >= 2 (got ${f.minOutlets})" }
                        require(f.minLeadMinutes >= 0) { "Category '$name' post.storyLines.first.minLeadMinutes must be >= 0 (got ${f.minLeadMinutes})" }
                    }
                    s.vaporware?.let { v ->
                        require(v.announceTypes.isNotEmpty()) { "Category '$name' post.storyLines.vaporware.announceTypes must not be empty" }
                        require(v.minAnnouncements >= 2) {
                            "Category '$name' post.storyLines.vaporware.minAnnouncements must be >= 2 (got ${v.minAnnouncements})"
                        }
                        require(v.minGapDays >= 1) { "Category '$name' post.storyLines.vaporware.minGapDays must be >= 1 (got ${v.minGapDays})" }
                    }
                    s.conflicts?.let { c ->
                        require(c.mode in setOf(ConflictLineConfig.MODE_LOG, ConflictLineConfig.MODE_POST)) {
                            "Category '$name' post.storyLines.conflicts.mode must be log or post (got '${c.mode}')"
                        }
                        require(c.minSupport >= 1) { "Category '$name' post.storyLines.conflicts.minSupport must be >= 1 (got ${c.minSupport})" }
                        require(c.dateToleranceDays >= 0) {
                            "Category '$name' post.storyLines.conflicts.dateToleranceDays must be >= 0 (got ${c.dateToleranceDays})"
                        }
                    }
                }
                post.longForm?.let { l ->
                    require(l.maxPerDigest >= 1) { "Category '$name' post.longForm.maxPerDigest must be >= 1 (got ${l.maxPerDigest})" }
                    require(l.minComposite in 0.0..10.0) { "Category '$name' post.longForm.minComposite must be within 0..10 (got ${l.minComposite})" }
                    require(l.sourceChars >= 500) { "Category '$name' post.longForm.sourceChars must be >= 500 (got ${l.sourceChars})" }
                    l.telegraph?.let { t ->
                        require(t.mode in setOf(LongFormTelegraphConfig.MODE_TEASER, LongFormTelegraphConfig.MODE_FULL, LongFormTelegraphConfig.MODE_LINK)) {
                            "Category '$name' post.longForm.telegraph.mode must be teaser, full or link (got '${t.mode}')"
                        }
                    }
                }
                post.intro?.let { i ->
                    require(i.languages.isNotEmpty()) { "Category '$name' post.intro.languages must not be empty" }
                    for (lang in i.languages) {
                        require(Regex("""[a-z]{2,3}(-[a-z]+)?""").matches(lang)) {
                            "Category '$name' post.intro.languages: '$lang' is not a Wikipedia language code (e.g. uk, en)"
                        }
                    }
                    require(i.maxPerDigest >= 1) { "Category '$name' post.intro.maxPerDigest must be >= 1 (got ${i.maxPerDigest})" }
                    require(i.repeatAfterDays >= 1) { "Category '$name' post.intro.repeatAfterDays must be >= 1 (got ${i.repeatAfterDays})" }
                }
                post.hashtags?.let { h ->
                    require(h.max >= 0) { "Category '$name' post.hashtags.max must be >= 0 (got ${h.max})" }
                    for (tag in metifikys.format.Hashtags.splitStatic(h.category)) {
                        require(metifikys.format.Hashtags.isWellFormed(tag)) {
                            "Category '$name' post.hashtags.category: '$tag' is not a valid hashtag — use '#' followed by letters, digits or '_' (at least one letter)"
                        }
                    }
                }
                post.persona?.takeIf { it.enabled }?.let { p ->
                    require(p.share in 0.0..1.0) { "Category '$name' post.persona.share must be in [0.0, 1.0] (got ${p.share})" }
                    require(p.maxChars in 20..400) { "Category '$name' post.persona.maxChars must be in 20..400 (got ${p.maxChars})" }
                    require(p.promptFile.isNotBlank()) {
                        "Category '$name' post.persona.promptFile is required — the persona's style is yours (see prompts/persona.example.yaml)"
                    }
                    require(p.timeoutSeconds >= 1) { "Category '$name' post.persona.timeoutSeconds must be >= 1 (got ${p.timeoutSeconds})" }
                    require(category.dedup != null) {
                        "Category '$name' post.persona needs a dedup: block — the remark goes under Step 1's top-ranked event"
                    }
                }
                post.also?.let { a ->
                    require(a.max >= 1) { "Category '$name' post.also.max must be >= 1 (got ${a.max})" }
                    for ((host, label) in a.names) {
                        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' }) {
                            "Category '$name' post.also.names: '$host' is not a host name (e.g. ign.com)"
                        }
                        require(label.isNotBlank()) { "Category '$name' post.also.names.$host must not be blank" }
                    }
                }
                post.official?.let { o ->
                    require(!o.enabled || o.domains.isNotEmpty() || o.matchEntityDomain) {
                        "Category '$name' post.official needs domains (or matchEntityDomain: true)"
                    }
                    for (d in o.domains) {
                        require(Regex("""[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+""").matches(d.trim())) {
                            "Category '$name' post.official.domains: '$d' is not a domain (e.g. blog.playstation.com)"
                        }
                    }
                    require(!o.enabled || category.dedup != null) {
                        "Category '$name' post.official needs a dedup: block — the candidates are Step 1's event clusters"
                    }
                }
                post.crossLinks?.takeIf { it.enabled }?.let { c ->
                    require(c.categories.isNotEmpty()) { "Category '$name' post.crossLinks.categories must not be empty" }
                    require(name !in c.categories) { "Category '$name' post.crossLinks.categories must not list the category itself" }
                    require(c.minSimilarity in 0.0..1.0) {
                        "Category '$name' post.crossLinks.minSimilarity must be in [0.0, 1.0] (got ${c.minSimilarity})"
                    }
                    require(c.maxAgeDays >= 1) { "Category '$name' post.crossLinks.maxAgeDays must be >= 1 (got ${c.maxAgeDays})" }
                    for (other in listOf(name) + c.categories) {
                        val otherCfg = config.categories[other]
                        require(otherCfg != null) { "Category '$name' post.crossLinks.categories: unknown category '$other'" }
                        require(otherCfg.semanticDedup?.eventEnabled == true) {
                            "Category '$name' post.crossLinks needs semanticDedup.eventEnabled on '$other' — the links compare the stored event vectors"
                        }
                    }
                }
                post.images?.takeIf { it.enabled }?.let { i ->
                    require(category.enableImages) { "Category '$name' post.images needs enableImages: true" }
                    require(i.minWidth >= 0) { "Category '$name' post.images.minWidth must be >= 0 (got ${i.minWidth})" }
                    require(i.timeoutSeconds >= 1) { "Category '$name' post.images.timeoutSeconds must be >= 1 (got ${i.timeoutSeconds})" }
                    require(i.maxTotalSeconds >= i.timeoutSeconds) {
                        "Category '$name' post.images.maxTotalSeconds must be >= timeoutSeconds (got ${i.maxTotalSeconds})"
                    }
                    require(i.ignore.none { it.isBlank() }) { "Category '$name' post.images.ignore must not contain blank entries" }
                }
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
            category.dedup?.digest?.breakingNewsworthiness?.let { b ->
                require(b in 1..10) {
                    "Category '$name' digest.breakingNewsworthiness must be in 1..10 (got $b); null disables the breaking lane"
                }
            }
            category.dedup?.digest?.let { d ->
                d.maxPostsPerEvent?.let { m ->
                    require(m >= 1) { "Category '$name' digest.maxPostsPerEvent must be >= 1 (got $m); null disables the cap" }
                }
                require(d.updateWindowDays >= 1) { "Category '$name' digest.updateWindowDays must be >= 1 (got ${d.updateWindowDays})" }
                d.antiBubble?.takeIf { it.enabled }?.let { a ->
                    require(a.intervalDays >= 1) { "Category '$name' digest.antiBubble.intervalDays must be >= 1 (got ${a.intervalDays})" }
                    require(a.minNewsworthiness in 1..10) {
                        "Category '$name' digest.antiBubble.minNewsworthiness must be in 1..10 (got ${a.minNewsworthiness})"
                    }
                    require(a.label.isNotBlank()) { "Category '$name' digest.antiBubble.label must not be blank" }
                    require(d.ranker.enabled) {
                        "Category '$name' digest.antiBubble needs digest.ranker.enabled — it picks from the events the ranker left out"
                    }
                    require(config.feedback.enabled) {
                        "Category '$name' digest.antiBubble needs feedback.enabled — without the affinity table nothing is ever picked"
                    }
                }
                d.updateDiff?.takeIf { it.enabled }?.let { u ->
                    require(u.maxPreviousPosts in 1..10) {
                        "Category '$name' digest.updateDiff.maxPreviousPosts must be in 1..10 (got ${u.maxPreviousPosts})"
                    }
                    require(u.windowDays >= 1) { "Category '$name' digest.updateDiff.windowDays must be >= 1 (got ${u.windowDays})" }
                    require(u.timeoutSeconds >= 1) { "Category '$name' digest.updateDiff.timeoutSeconds must be >= 1 (got ${u.timeoutSeconds})" }
                    require(u.maxSourceChars >= 500) {
                        "Category '$name' digest.updateDiff.maxSourceChars must be >= 500 (got ${u.maxSourceChars})"
                    }
                }
            }
            category.minArticles?.let { m ->
                require(m >= 1) { "Category '$name' minArticles must be >= 1 (got $m)" }
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
                require(ovr.extractAlternate == null) {
                    "Category '$name' llm.extractAlternate was replaced by a weighted split — write " +
                        "llm.extract: { providers: [ { percent: 50, provider: …, model: … }, { percent: 50, provider: …, model: … } ] }"
                }
                validateOverride(name, "extract", ovr.extract, openRouter, anthropic, config.claudeCli, config.codexCli)
                ovr.extract?.legs()?.forEach { e ->
                    require(!(e.batch && e.provider in setOf("openrouter", "claudecli", "codexcli"))) {
                        "Category '$name' llm.extract: batch=true is not supported for provider '${e.provider}' (no Batch API)"
                    }
                    require(!(e.batch && e.fallback != null)) {
                        "Category '$name' llm.extract: a fallback together with batch=true is inert — the batch leg runs async and never uses the sync fallback. Drop one."
                    }
                }
                validateOverride(name, "render", ovr.render, openRouter, anthropic, config.claudeCli, config.codexCli)
                validateOverride(name, "batch", ovr.batch, openRouter, anthropic, config.claudeCli, config.codexCli)
                validateOverride(name, "summarize", ovr.summarize, openRouter, anthropic, config.claudeCli, config.codexCli)
                validateOverride(name, "batchFallback", ovr.batchFallback, openRouter, anthropic, config.claudeCli, config.codexCli)
                validateOverride(name, "persona", ovr.persona, openRouter, anthropic, config.claudeCli, config.codexCli)
                validateOverride(name, "updateDiff", ovr.updateDiff, openRouter, anthropic, config.claudeCli, config.codexCli)
                ovr.shadow?.takeIf { it.enabled }?.let { s ->
                    require(s.extract != null || s.render != null) {
                        "Category '$name' llm.shadow needs extract and/or render — without either nothing runs in the shadow"
                    }
                    require(category.dedup != null) {
                        "Category '$name' llm.shadow needs a dedup: block — the shadow compares the two-step pipeline"
                    }
                    validateOverride(name, "shadow.extract", s.extract, openRouter, anthropic, config.claudeCli, config.codexCli)
                    validateOverride(name, "shadow.render", s.render, openRouter, anthropic, config.claudeCli, config.codexCli)
                    require(listOfNotNull(s.extract, s.render).flatMap { it.legs() }.none { it.batch }) {
                        "Category '$name' llm.shadow: batch=true is not supported — the shadow runs synchronously next to the live call"
                    }
                    require(s.sampleRate in 0.0..1.0) { "Category '$name' llm.shadow.sampleRate must be in [0.0, 1.0] (got ${s.sampleRate})" }
                    require(s.queueSize >= 1) { "Category '$name' llm.shadow.queueSize must be >= 1 (got ${s.queueSize})" }
                    require(s.promptFile == null || s.promptFile.isNotBlank()) { "Category '$name' llm.shadow.promptFile must not be blank" }
                }
                ovr.batch?.legs()?.forEach { b ->
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
                    require(ovr.extract?.legs()?.none { it.batch } != false) {
                        "Category '$name' sets skipBatch: true but also llm.extract.batch: true — skipBatch means no Batch API at all"
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
                w.album.takeIf { it.enabled }?.let { a ->
                    require(a.minPhotos in 2..10) { "weekly.album.minPhotos must be in 2..10 (got ${a.minPhotos})" }
                    require(a.maxPhotos in a.minPhotos..10) { "weekly.album.maxPhotos must be in minPhotos..10 (got ${a.maxPhotos})" }
                    require(a.timeoutSeconds >= 1) { "weekly.album.timeoutSeconds must be >= 1 (got ${a.timeoutSeconds})" }
                    require(a.maxBytes > 0) { "weekly.album.maxBytes must be > 0 (got ${a.maxBytes})" }
                }
                for (cat in w.categories) {
                    require(cat in config.categories) {
                        "weekly.categories references unknown category '$cat' — must match a key under categories:"
                    }
                }
            }
        }

        val telegraph = config.telegraph.copy(
            accessToken = System.getenv("TELEGRAPH_TOKEN")?.takeIf { it.isNotBlank() } ?: config.telegraph.accessToken
        )
        require(telegraph.shortName.length in 1..32) { "telegraph.shortName must be 1..32 characters" }
        require(telegraph.authorName.length <= 128) { "telegraph.authorName must be at most 128 characters" }
        require(telegraph.authorUrl.isBlank() || Regex("""https?://\S+""").matches(telegraph.authorUrl)) {
            "telegraph.authorUrl must be an http(s) URL (or blank)"
        }
        require(telegraph.timeoutSeconds >= 1) { "telegraph.timeoutSeconds must be >= 1 (got ${telegraph.timeoutSeconds})" }

        return config.copy(
            telegram = config.telegram.copy(botToken = botToken),
            openai = config.openai.copy(apiKey = apiKey),
            openrouter = openRouter,
            anthropic = anthropic,
            badges = badges,
            telegraph = telegraph
        )
    }

    /** `health:` block — alerts go to the admin chat, so it must exist; dates and intervals sane. */
    private fun validateHealth(config: AppConfig) {
        val h = config.health
        h.claudeTokenExpires?.let { d ->
            try {
                java.time.LocalDate.parse(d.trim())
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("health.claudeTokenExpires must be YYYY-MM-DD (got '$d')")
            }
        }
        require(h.warnDaysBefore >= 0) { "health.warnDaysBefore must be >= 0 (got ${h.warnDaysBefore})" }
        require(h.cliProbe.intervalHours >= 1) { "health.cliProbe.intervalHours must be >= 1 (got ${h.cliProbe.intervalHours})" }
        require(h.cliProbe.timeoutSeconds in 5..600) {
            "health.cliProbe.timeoutSeconds must be within 5..600 (got ${h.cliProbe.timeoutSeconds})"
        }
        if (h.enabled) {
            require(!config.admin.statusChatId.isNullOrBlank()) {
                "health.enabled needs admin.statusChatId — the CLI alerts are sent there"
            }
        }
    }

    /** `feedHealth:` block — a window the `rejected_events` retention can still answer, sane thresholds. */
    private fun validateFeedHealth(config: AppConfig) {
        val f = config.feedHealth
        require(f.windowDays in 1..30) {
            "feedHealth.windowDays must be within 1..30 (got ${f.windowDays}); rejected_events is kept 30 days"
        }
        val t = f.thresholds
        require(t.deadDays >= 1 && t.staleNewDays >= 1) { "feedHealth.thresholds.deadDays / staleNewDays must be >= 1" }
        require(t.deadFailStreak >= 1) { "feedHealth.thresholds.deadFailStreak must be >= 1 (got ${t.deadFailStreak})" }
        require(t.noisyMinArticles >= 1 && t.minPublishedForFirst >= 1) {
            "feedHealth.thresholds.noisyMinArticles / minPublishedForFirst must be >= 1"
        }
        for ((name, v) in listOf(
            "noisyMaxPublishedPct" to t.noisyMaxPublishedPct, "noisyMinJunkPct" to t.noisyMinJunkPct,
            "mirrorMaxFirstPct" to t.mirrorMaxFirstPct, "primaryMinFirstPct" to t.primaryMinFirstPct
        )) {
            require(v in 0.0..100.0) { "feedHealth.thresholds.$name must be within 0..100 (got $v)" }
        }
        val c = f.candidates
        require(c.everyDays >= 1 && c.recheckDays >= 1) { "feedHealth.candidates.everyDays / recheckDays must be >= 1" }
        require(c.maxProbesPerRun in 1..50) { "feedHealth.candidates.maxProbesPerRun must be within 1..50 (got ${c.maxProbesPerRun})" }
        require(c.maxRequestsPerRun in 1..200) {
            "feedHealth.candidates.maxRequestsPerRun must be within 1..200 (got ${c.maxRequestsPerRun})"
        }
        require(c.minPublished >= 1) { "feedHealth.candidates.minPublished must be >= 1 (got ${c.minPublished})" }
    }

    /** `storyIntel:` block — a retention that still covers every window reading the clusters. */
    private fun validateStoryIntel(config: AppConfig) {
        val s = config.storyIntel
        require(s.retentionDays >= 1) { "storyIntel.retentionDays must be >= 1 (got ${s.retentionDays})" }
        require(s.historyRetentionDays >= 1) { "storyIntel.historyRetentionDays must be >= 1 (got ${s.historyRetentionDays})" }
        config.weekly?.takeIf { it.enabled && s.enabled }?.let { w ->
            require(s.retentionDays >= w.lookbackDays) {
                "storyIntel.retentionDays (${s.retentionDays}) must be >= weekly.lookbackDays (${w.lookbackDays})"
            }
        }
        config.weekly?.takeIf { it.enabled && it.showOutlets }?.let {
            require(s.enabled) { "weekly.showOutlets needs storyIntel.enabled: true — the outlets come from its story clusters" }
        }
        config.weekly?.sourceLeaderboard?.let { l ->
            require(l.lookbackDays >= 1) { "weekly.sourceLeaderboard.lookbackDays must be >= 1 (got ${l.lookbackDays})" }
            require(l.minEvents >= 1) { "weekly.sourceLeaderboard.minEvents must be >= 1 (got ${l.minEvents})" }
            require(l.top >= 1) { "weekly.sourceLeaderboard.top must be >= 1 (got ${l.top})" }
            if (l.enabled && config.weekly.enabled) {
                require(s.enabled) { "weekly.sourceLeaderboard needs storyIntel.enabled: true — the ranking reads its story clusters" }
                require(l.lookbackDays <= s.retentionDays) {
                    "weekly.sourceLeaderboard.lookbackDays (${l.lookbackDays}) must be <= storyIntel.retentionDays (${s.retentionDays})"
                }
            }
        }
        for ((from, to) in s.domainAliases) {
            require(from.isNotBlank() && to.isNotBlank()) { "storyIntel.domainAliases: blank domain in '$from: $to'" }
        }
        config.weekly?.barometer?.let { b -> validateBarometer(config, b) }
    }

    /** `weekly.barometer` — sane window and series; it reads `event_history`, so storyIntel must be on. */
    private fun validateBarometer(config: AppConfig, b: BarometerConfig) {
        require(b.weeks in 2..52) { "weekly.barometer.weeks must be within 2..52 (got ${b.weeks})" }
        require(b.minWeeks in 1..b.weeks) { "weekly.barometer.minWeeks must be within 1..weeks (got ${b.minWeeks})" }
        require(b.format in setOf(BarometerConfig.FORMAT_PNG, BarometerConfig.FORMAT_TEXT)) {
            "weekly.barometer.format must be png or text (got '${b.format}')"
        }
        require(b.series.size in 1..2) { "weekly.barometer.series must list one or two series (got ${b.series.size})" }
        require(b.series.map { it.id }.distinct().size == b.series.size) { "weekly.barometer.series ids must be unique" }
        for (s in b.series) {
            require(s.id.isNotBlank() && s.label.isNotBlank()) { "weekly.barometer.series: id and label must not be blank" }
            require(s.eventTypes.isNotEmpty() || s.keywords.isNotEmpty()) {
                "weekly.barometer.series '${s.id}' needs eventTypes or keywords"
            }
        }
        for (cat in b.categories) {
            require(cat in config.categories) { "weekly.barometer.categories references unknown category '$cat'" }
        }
        if (b.enabled && config.weekly?.enabled == true) {
            require(config.storyIntel.enabled) { "weekly.barometer needs storyIntel.enabled: true — it counts the events in event_history" }
            require(b.weeks * 7L <= config.storyIntel.historyRetentionDays) {
                "weekly.barometer.weeks (${b.weeks}) must fit storyIntel.historyRetentionDays (${config.storyIntel.historyRetentionDays})"
            }
        }
    }

    /** `admin:` block — the admin-command features need the poller and at least one admin. */
    private fun validateAdmin(config: AppConfig) {
        val admin = config.admin
        require(admin.userIds.none { it == 0L }) { "admin.userIds must not contain 0" }
        if (admin.commands.enabled) {
            require(config.telegram.updatesPolling) {
                "admin.commands.enabled needs telegram.updatesPolling: true — commands arrive through the same " +
                    "getUpdates poller as reactions (one consumer per bot token)"
            }
            require(admin.userIds.isNotEmpty()) {
                "admin.commands.enabled needs a non-empty admin.userIds — the Telegram user ids allowed to run commands"
            }
        }
        admin.muteSuggestions.takeIf { it.enabled }?.let { s ->
            require(admin.commands.enabled && admin.statusChatId != null) {
                "admin.muteSuggestions.enabled needs admin.commands.enabled (for the buttons) and admin.statusChatId (where they go)"
            }
            require(s.everyDays >= 1) { "admin.muteSuggestions.everyDays must be >= 1 (got ${s.everyDays})" }
            require(s.maxScore in -1.0..0.0) { "admin.muteSuggestions.maxScore must be within -1..0 (got ${s.maxScore})" }
            require(s.minTone >= 0.0) { "admin.muteSuggestions.minTone must be >= 0 (got ${s.minTone})" }
            require(s.minRejected >= 1) { "admin.muteSuggestions.minRejected must be >= 1 (got ${s.minRejected})" }
            require(s.maxPerRun in 1..20) { "admin.muteSuggestions.maxPerRun must be within 1..20 (got ${s.maxPerRun})" }
        }
        admin.moderation.takeIf { it.enabled }?.let { m ->
            require(admin.commands.enabled && admin.statusChatId != null) {
                "admin.moderation.enabled needs admin.commands.enabled (for the buttons) and admin.statusChatId (where the messages go)"
            }
            for (cat in m.categories) {
                require(cat in config.categories) { "admin.moderation.categories references unknown category '$cat'" }
            }
        }
    }

    private fun validateOverride(
        category: String,
        useCase: String,
        ovr: LlmOverride?,
        openRouter: OpenRouterConfig?,
        anthropic: AnthropicConfig?,
        claudeCli: ClaudeCliConfig?,
        codexCli: CodexCliConfig?,
        /** True for an entry of a `providers:` split — the one place a `percent` belongs. */
        splitEntry: Boolean = false
    ) {
        if (ovr == null) return
        ovr.providers?.let { entries ->
            validateSplit(category, useCase, ovr, entries)
            entries.forEachIndexed { i, e ->
                validateOverride(category, "$useCase.providers[$i]", e, openRouter, anthropic, claudeCli, codexCli, splitEntry = true)
            }
            return
        }
        require(splitEntry || ovr.percent == null) {
            "Category '$category' llm.$useCase: percent is only meaningful on an entry of a providers: split"
        }
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

    /**
     * The shape of a `providers:` split: no provider of its own, entries that are plain overrides
     * with a 1..100 percent summing to exactly 100, and no two entries on the same provider+model
     * (they would share one tally). The entries themselves are validated by the caller.
     */
    private fun validateSplit(category: String, useCase: String, split: LlmOverride, entries: List<LlmOverride>) {
        require(split.provider.isBlank() && split.model.isBlank() && !split.batch && split.fallback == null && split.percent == null) {
            "Category '$category' llm.$useCase: a providers: split takes no provider/model/batch/fallback/percent of its own — put them on the entries"
        }
        require(entries.isNotEmpty()) { "Category '$category' llm.$useCase: providers: must list at least one entry" }
        entries.forEachIndexed { i, e ->
            require(e.providers == null) { "Category '$category' llm.$useCase.providers[$i]: a split cannot nest another providers: split" }
            val p = requireNotNull(e.percent) { "Category '$category' llm.$useCase.providers[$i]: percent is required" }
            require(p in 1..100) { "Category '$category' llm.$useCase.providers[$i]: percent must be in 1..100 (got $p)" }
        }
        val sum = entries.sumOf { it.percent ?: 0 }
        require(sum == 100) { "Category '$category' llm.$useCase: providers percents must sum to 100 (got $sum)" }
        val dup = entries.groupBy { splitKeyOf(it) }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "Category '$category' llm.$useCase: providers lists ${dup.joinToString()} more than once — merge their percents" }
    }

    /** Same identity as [metifikys.ai.LlmSplitter.legKey]: provider/model, plus the batch flag. */
    private fun splitKeyOf(ovr: LlmOverride): String = keyOf(ovr) + if (ovr.batch) "+batch" else ""

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
        // A blank model is valid for the CLI providers ("CLI default") and OpenRouter (the `models:` ladder).
        require(ovr.provider in setOf("claudecli", "codexcli", "openrouter") || ovr.model.isNotBlank()) {
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
