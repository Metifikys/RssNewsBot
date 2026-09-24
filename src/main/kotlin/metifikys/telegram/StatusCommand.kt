package metifikys.telegram

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.AppConfig
import metifikys.db.CategoryArticleCounts
import metifikys.db.CategoryReactionSummary
import metifikys.db.NewsDatabase
import metifikys.db.ProviderLatency
import metifikys.digest.CycleErrorLog
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val logger = KotlinLogging.logger {}

/** Window of the `/status` arm comparison — about the length of one persona A/B round. */
private const val VARIANT_WINDOW_DAYS = 30L

/**
 * Builds a one-shot snapshot for the `/status` Telegram command:
 *  - per category: most recent digest's article count vs `dedup.digest.maxDigestItems` cap
 *  - per category: currently pending OpenAI batches with their start times
 *  - any cycle errors recorded since the last successful status post
 */
class StatusCommand(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val errorLog: CycleErrorLog = CycleErrorLog(),
    /**
     * Telegram 429s in the last 24 hours ([TelegramSender.rateLimitsLast24h]); null omits the line.
     * Anything above zero in prod is the trigger for a full per-chat / global token bucket.
     */
    private val telegramRateLimits: (() -> Int)? = null,
    /** Digest pauses (`/pause`); null omits the pause marker. */
    private val pauseStore: metifikys.digest.PauseStore? = null,
    /** `CLI: claude ✅ 2h · codex ⛔ auth з 03:10` (`health.enabled`); null omits the line. */
    private val cliHealthLine: (() -> String?)? = null
) {

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val stampFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val pauseFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    private val zoneId: ZoneId = ZoneId.systemDefault()

    /** Renders status text and a token identifying the highest error seq consumed. */
    data class BuiltStatus(val text: String, val errorCommitToken: Long)

    /** Read-only snapshot used by the `/status` command. Does not clear errors. */
    fun build(): String = buildSnapshot().text

    fun buildSnapshot(): BuiltStatus {
        val readyByCategory = db.fetchReadyForDigestByCategory(config.processing.staleTimeoutHours)
        val pendingByCategory = db.fetchPendingBatchesByCategory()
        val articleCounts = safeFetchArticleCounts(24)
        val publishedCounts = safeFetchPublishedCounts(24)
        val errors = errorLog.list()
        val sb = StringBuilder()
        sb.append("📊 *Status* — ${LocalDateTime.now().format(stampFmt)}\n")
        for ((name, category) in config.categories) {
            val latest = db.fetchLatestSummary(name)
            val whenCell = if (latest != null) {
                val age = formatAge(Duration.between(latest.createdAt, LocalDateTime.now()))
                "${latest.createdAt.format(timeFmt)} ($age)"
            } else {
                "never"
            }
            val readyCount = readyByCategory[name].orEmpty().size
            sb.append('\n')
            sb.append("*${escapeMarkdown(name)}* ${category.emoji}\n")
            pauseStore?.pausedUntil(name)?.let { p ->
                sb.append("  ⏸ paused until ${p.until?.format(pauseFmt) ?: "/resume"}\n")
            }
            sb.append("  last digest: $whenCell\n")
            sb.append("  ready for next batch: $readyCount\n")
            sb.append("  ").append(formatArticleCounts(articleCounts[name], publishedCounts[name] ?: 0L)).append('\n')
            val pending = pendingByCategory[name].orEmpty()
            for (batch in pending) {
                sb.append("  pending: `${escapeMarkdown(batch.batchId)}` started ${batch.createdAt.format(timeFmt)}\n")
            }
        }

        telegramRateLimits?.let { count ->
            sb.append('\n').append("📨 Telegram 429 (24h): ${count()}\n")
        }
        cliHealthLine?.let { line ->
            try {
                line()?.let { sb.append('\n').append(escapeMarkdown(it)).append('\n') }
            } catch (e: Exception) {
                logger.warn(e) { "[Status] CLI health line failed" }
            }
        }
        appendLatencySection(sb)
        appendReactionsSection(sb)
        appendVariantsSection(sb)
        appendShadowLine(sb)
        appendFactCheckSection(sb)
        appendAffinitySection(sb)
        appendFeedHealthSection(sb)
        appendFirstSourceSection(sb)

        val errorCommitToken = if (errors.isEmpty()) {
            -1L
        } else {
            sb.append('\n').append("⚠️ *Recent errors*\n")
            val visible = errors.takeLast(10)
            for (entry in visible) {
                val time = entry.at.atZone(zoneId).toLocalTime().format(timeFmt)
                val cat = entry.category?.let { escapeMarkdown(it) } ?: "-"
                val ctx = escapeMarkdown(entry.context)
                val msg = escapeMarkdown(entry.message)
                sb.append("  $time · $cat · $ctx · $msg\n")
            }
            errors.last().seq
        }

        return BuiltStatus(sb.toString().trimEnd(), errorCommitToken)
    }

    /**
     * Per-provider LLM latency, two windows side by side: `avg/p95 (calls)` for 24h
     * then 7d. Providers are ordered by 24h call volume (falling back to 7d). Batch
     * jobs are excluded upstream (they record no `duration_ms`).
     */
    private fun appendLatencySection(sb: StringBuilder) {
        val day = safeFetchLatency(24).associateBy { it.provider }
        val week = safeFetchLatency(7 * 24).associateBy { it.provider }
        sb.append('\n').append("⏱ *LLM latency* (avg / p95 · req · 24h / 7d)\n")
        if (day.isEmpty() && week.isEmpty()) {
            sb.append("  no timed calls recorded\n")
            return
        }
        val providers = (day.keys + week.keys).distinct()
            .sortedByDescending { day[it]?.callCount ?: week[it]?.callCount ?: 0L }
        for (p in providers) {
            sb.append(escapeMarkdown(p)).append(": ").append(latencyCell(day[p])).append('\n')
            sb.append("  ").append(latencyCell(week[p])).append('\n')
        }
    }

    private fun latencyCell(l: ProviderLatency?): String =
        if (l == null) "—" else "${formatMs(l.avgMs)} / ${formatMs(l.p95Ms.toDouble())} · ${l.callCount}"

    /**
     * 7-day reaction rollup, one line per category that has any reactions: total reactions,
     * distinct posts reacted on, and the top few emoji by count. Silently omitted when reaction
     * tracking is off or nothing has accumulated yet (no rows → empty map).
     */
    private fun appendReactionsSection(sb: StringBuilder) {
        val summary = safeFetchReactions(7 * 24)
        if (summary.isEmpty()) return
        sb.append('\n').append("👍 *Reactions* (7d)\n")
        for ((name, _) in config.categories) {
            val r = summary[name] ?: continue
            val top = r.byEmoji.take(3).joinToString(", ") { "${it.emoji} ${it.count}" }
            val topCell = if (top.isNotEmpty()) " · top: $top" else ""
            sb.append("  ${escapeMarkdown(name)}: ${r.totalReactions} across ${r.postCount} posts$topCell\n")
        }
    }

    /**
     * `🧪 *Variants*` (30d): reactions per experiment arm (`digest_messages.variant`) — the persona
     * A/B (`post.persona`) and the anti-bubble `explore` posts — one line per category that has
     * tagged posts, see [metifikys.feedback.VariantStats]. Omitted when there are none or the
     * query fails.
     */
    private fun appendVariantsSection(sb: StringBuilder) {
        val posts = try {
            db.fetchVariantPosts(LocalDateTime.now().minusDays(VARIANT_WINDOW_DAYS))
        } catch (e: Exception) {
            logger.warn(e) { "[/status] fetchVariantPosts failed — omitting the variants section" }
            return
        }
        if (posts.isEmpty()) return
        val byCategory = metifikys.feedback.VariantStats.aggregate(posts, metifikys.feedback.ReactionValence(config.feedback.valence))
        sb.append('\n').append("🧪 *Variants* (${VARIANT_WINDOW_DAYS}d)\n")
        for ((name, _) in config.categories) {
            val arms = byCategory[name] ?: continue
            sb.append("  ${escapeMarkdown(name)}: ${arms.joinToString(" | ") { metifikys.feedback.VariantStats.cell(it) }}\n")
        }
    }

    /**
     * `factCheck`: `🧐 Fact-check 7d: …` over the alert window, plus ⚠️ with links to the latest
     * unsupported posts above the threshold; hidden below `alert.minChecks` or on a query failure.
     */
    private fun appendFactCheckSection(sb: StringBuilder) {
        val fc = config.factCheck.takeIf { it.enabled } ?: return
        val rows = try {
            db.fetchFactChecks(LocalDateTime.now().minusDays(fc.alert.windowDays))
        } catch (e: Exception) {
            logger.warn(e) { "[/status] fetchFactChecks failed — omitting the fact-check section" }
            return
        }
        val lines = metifikys.digest.FactChecker.statusLines(rows, fc.alert) { config.categories[it]?.channelId } ?: return
        sb.append('\n')
        lines.forEach { sb.append(it).append('\n') }
    }

    /** `llm.shadow`: one line over the last 24h of `shadow_runs`; omitted without runs or on a query failure. */
    private fun appendShadowLine(sb: StringBuilder) {
        if (config.categories.values.none { it.llm?.shadow?.enabled == true }) return
        val runs = try {
            db.fetchShadowRuns(LocalDateTime.now().minusHours(24))
        } catch (e: Exception) {
            logger.warn(e) { "[/status] fetchShadowRuns failed — omitting the shadow line" }
            return
        }
        metifikys.digest.ShadowRunner.statusLine(runs)?.let { sb.append('\n').append(it).append('\n') }
    }

    private fun safeFetchReactions(sinceHours: Long): Map<String, CategoryReactionSummary> = try {
        db.fetchReactionSummary(sinceHours)
    } catch (e: Exception) {
        logger.warn(e) { "[/status] fetchReactionSummary failed — omitting the reactions section" }
        emptyMap()
    }

    /**
     * Materialized audience-affinity check, one line per category with any rows: the top and
     * bottom franchise/eventType keys by score with their (decay-weighted) sample sizes.
     * Silently omitted when `feedback.enabled=false` or nothing has been aggregated yet.
     * Phase-1 observability — lets the operator sanity-check the scores while nothing
     * consumes them.
     */
    private fun appendAffinitySection(sb: StringBuilder) {
        if (!config.feedback.enabled) return
        val rows = safeFetchAffinity()
            .filter { it.dimension == "franchise" || it.dimension == "event_type" }
        if (rows.isEmpty()) return
        sb.append('\n').append("🎯 *Audience affinity* (top/bottom by score)\n")
        for ((name, _) in config.categories) {
            val catRows = rows.filter { it.category == name }.sortedByDescending { it.score }
            if (catRows.isEmpty()) continue
            val top = catRows.take(2)
            val bottom = catRows.takeLast(2).filter { it !in top }
            // n reads `nTone/n` — reacted messages over delivered ones. Only the first number
            // is evidence; a key like `+0.30 (n=0.9/4.5)` rests on a single reaction.
            fun cell(r: metifikys.db.AudienceAffinityRow) =
                "${escapeMarkdown(r.key)} ${"%+.2f".format(Locale.ROOT, r.score)} " +
                    "(n=${"%.1f".format(Locale.ROOT, r.nTone)}/${"%.1f".format(Locale.ROOT, r.n)})"
            sb.append("  ${escapeMarkdown(name)}: ↑ ${top.joinToString(", ") { cell(it) }}")
            if (bottom.isNotEmpty()) sb.append(" · ↓ ${bottom.joinToString(", ") { cell(it) }}")
            sb.append('\n')
        }
    }

    /**
     * `📡 Feeds` (`feedHealth.enabled` + `statusSection`): a per-category count line and the worst
     * anomalies, [metifikys.feeds.FeedHealthSection.MAX_LINES] lines at most. A failing snapshot
     * query drops the section, never the status post.
     */
    private fun appendFeedHealthSection(sb: StringBuilder) {
        val fh = config.feedHealth
        if (!fh.enabled || !fh.statusSection) return
        val lines = try {
            val now = LocalDateTime.now()
            metifikys.feeds.FeedHealthSection.render(
                metifikys.feeds.FeedHealthSection.metrics(config, db, now),
                fh.windowDays,
                metifikys.feeds.FeedHealthSection.labels(config),
                config.categories.keys.toList(),
                now,
                ::escapeMarkdown
            )
        } catch (e: Exception) {
            logger.warn(e) { "[/status] feed health snapshot failed — omitting the feeds section" }
            return
        }
        if (lines.isEmpty()) return
        sb.append('\n')
        lines.forEach { sb.append(it).append('\n') }
    }

    /**
     * `🥇 Хто першим` (`storyIntel.enabled`): per category, the outlets most often first with a
     * story over `weekly.sourceLeaderboard.lookbackDays` (stories with 3+ outlets, outlets with
     * `minEvents`+ stories), `top` of them. Omitted while no outlet qualifies; a failing query
     * drops the section, never the status post.
     */
    private fun appendFirstSourceSection(sb: StringBuilder) {
        if (!config.storyIntel.enabled) return
        val cfg = config.weekly?.sourceLeaderboard ?: metifikys.config.SourceLeaderboardConfig()
        val loader = metifikys.story.StoryClusterLoader(config, db)
        val lines = mutableListOf<String>()
        for (name in config.categories.keys) {
            val rows = try {
                loader.leaderboard(name, cfg.lookbackDays, cfg.minEvents)
            } catch (e: Exception) {
                logger.warn(e) { "[/status] source leaderboard for $name failed — omitting it" }
                continue
            }
            val top = rows.filter { it.firsts > 0 }.take(cfg.top)
            if (top.isEmpty()) continue
            val cells = top.joinToString(" · ") { r ->
                "${escapeMarkdown(r.domain)} ${Math.round(r.firstRate * 100)}% (${r.firsts}/${r.events})"
            }
            lines += "  ${escapeMarkdown(name)}: $cells"
        }
        if (lines.isEmpty()) return
        sb.append('\n').append("🥇 *Хто першим* (${cfg.lookbackDays} дн.)\n")
        lines.forEach { sb.append(it).append('\n') }
    }

    private fun safeFetchAffinity(): List<metifikys.db.AudienceAffinityRow> = try {
        db.fetchAudienceAffinity()
    } catch (e: Exception) {
        logger.warn(e) { "[/status] fetchAudienceAffinity failed — omitting the affinity section" }
        emptyList()
    }

    /** `✅ P (x%) · 📰 A · ⛔ B (y%)` — percentages over (articles+blocked), omitted when that's 0. */
    private fun formatArticleCounts(c: CategoryArticleCounts?, published: Long): String {
        val articles = c?.articles ?: 0L
        val blocked = c?.blocked ?: 0L
        val denom = articles + blocked
        val pubPct = if (denom > 0L) " (${published * 100 / denom}%)" else ""
        val blkPct = if (denom > 0L) " (${blocked * 100 / denom}%)" else ""
        return "✅ $published$pubPct · 📰 $articles · ⛔ $blocked$blkPct"
    }

    private fun safeFetchLatency(sinceHours: Long): List<ProviderLatency> = try {
        db.fetchProviderLatency(sinceHours)
    } catch (e: Exception) {
        logger.warn(e) { "[/status] fetchProviderLatency failed — omitting the latency section" }
        emptyList()
    }

    private fun safeFetchArticleCounts(sinceHours: Long): Map<String, CategoryArticleCounts> = try {
        db.fetchArticleStatusCounts(sinceHours)
    } catch (e: Exception) {
        logger.warn(e) { "[/status] fetchArticleStatusCounts failed — article counts will show 0" }
        emptyMap()
    }

    private fun safeFetchPublishedCounts(sinceHours: Long): Map<String, Long> = try {
        db.fetchPublishedTopicCounts(sinceHours)
    } catch (e: Exception) {
        logger.warn(e) { "[/status] fetchPublishedTopicCounts failed — published counts will show 0" }
        emptyMap()
    }

    private fun formatMs(ms: Double): String =
        if (ms < 1000.0) "${ms.toLong()}ms" else String.format(Locale.ROOT, "%.1fs", ms / 1000.0)

    private fun escapeMarkdown(text: String): String =
        text.replace("*", "").replace("_", "").replace("`", "").replace("[", "").replace("]", "")

    internal fun formatAge(d: Duration): String {
        val totalMin = d.toMinutes().coerceAtLeast(0)
        return when {
            totalMin < 1L        -> "<1 min"
            totalMin < 60L       -> "$totalMin min"
            totalMin < 24L * 60L -> {
                val h = totalMin / 60
                val m = totalMin % 60
                if (m == 0L) "${h}h" else "${h}h ${m}m"
            }
            else                 -> "${totalMin / (24L * 60L)}d"
        }
    }
}
