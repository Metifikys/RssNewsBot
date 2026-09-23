package metifikys.feeds

import metifikys.config.AppConfig
import metifikys.db.NewsDatabase
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

/**
 * The `/status` `📡 Feeds` section: one summary line of per-category counts, then the worst
 * anomalies (💀 dead, 🗑 noisy, 💤 stale, 🪞 mirror), at most [MAX_LINES] lines in all. Suggestions
 * only — the bot never disables a feed.
 */
object FeedHealthSection {

    const val MAX_LINES = 8

    /** Loads the window's snapshot and computes the metrics of every configured feed. */
    fun metrics(config: AppConfig, db: NewsDatabase, now: LocalDateTime = LocalDateTime.now()): List<FeedQuality.FeedMetrics> {
        val since = now.minusDays(config.feedHealth.windowDays)
        val feeds = LinkedHashMap<String, String>()
        for ((name, cat) in config.categories) for (f in cat.feeds) feeds.putIfAbsent(f.url, name)
        val input = FeedQuality.Input(
            feeds = feeds,
            outcomes = db.fetchFeedArticleOutcomes(since),
            publishedGroups = db.fetchPublishedLinkGroupsSince(since),
            sightings = db.fetchFeedSightingsSince(since),
            stats = db.fetchFeedStats(),
            lastNew = db.fetchFeedLastNew(),
            trackingSince = db.fetchFeedTrackingSince(),
            now = now
        )
        return FeedQuality.compute(input, config.feedHealth.thresholds)
    }

    /** Labels by feed url (`feeds[].label`), for [FeedQuality.displayName]. */
    fun labels(config: AppConfig): Map<String, String> =
        config.categories.values.flatMap { it.feeds }.mapNotNull { f -> f.label?.let { f.url to it } }.toMap()

    /**
     * The section's lines (no trailing newline); empty when there are no feeds. [escape] is applied
     * to every category / feed name / error text (the status post is Markdown; t.me names carry `_`).
     */
    fun render(
        metrics: List<FeedQuality.FeedMetrics>,
        windowDays: Long,
        labels: Map<String, String>,
        categoryOrder: List<String>,
        now: LocalDateTime,
        escape: (String) -> String = { it }
    ): List<String> {
        if (metrics.isEmpty()) return emptyList()
        val byCat = metrics.groupBy { it.category }
        val summary = categoryOrder.filter { it in byCat }.joinToString(" | ") { cat ->
            val ms = byCat.getValue(cat)
            val ok = ms.count { m -> m.hints.none { it.anomaly } }
            val counts = FeedQuality.Hint.values().filter { it.anomaly }.mapNotNull { h ->
                ms.count { h in it.hints }.takeIf { it > 0 }?.let { "$it ${h.emoji}" }
            }
            (listOf("${escape(cat)} $ok ok") + counts).joinToString(" · ")
        }
        val lines = mutableListOf("📡 *Feeds* (${windowDays}d): $summary")
        val anomalies = metrics
            .flatMap { m -> m.hints.filter { it.anomaly }.take(1).map { h -> h to m } }
            .sortedWith(compareBy({ it.first.severity }, { -it.second.total }))
        for ((hint, m) in anomalies.take(MAX_LINES - 1)) {
            val name = FeedQuality.displayName(m.feedUrl, labels[m.feedUrl])
            lines += "  ${hint.emoji} ${escape(m.category)} ${escape(name)} — ${escape(reason(hint, m, now))}"
        }
        val hidden = anomalies.size - (MAX_LINES - 1)
        if (hidden > 0) lines[lines.lastIndex] = lines.last() + " (+$hidden more in the report)"
        return lines
    }

    private fun reason(hint: FeedQuality.Hint, m: FeedQuality.FeedMetrics, now: LocalDateTime): String = when (hint) {
        FeedQuality.Hint.DEAD -> {
            val since = m.lastSuccess?.let { "no success ${age(it, now)}" } ?: "no success yet"
            val err = m.lastError?.let { " (${it.take(40)})" } ?: ""
            "$since, ${m.failStreak} failed in a row$err"
        }
        FeedQuality.Hint.STALE -> "nothing new " + (m.lastNew?.let { age(it, now) } ?: "since tracking began")
        FeedQuality.Hint.NOISY -> "${m.total} art., ${pct(m.publishRate)} published, ${pct(m.junkRate)} junk"
        FeedQuality.Hint.MIRROR -> "first in ${m.firstSource}/${m.clusters} posts" +
            (m.medianLagMinutes?.let { ", lag ~${it}m" } ?: "")
        FeedQuality.Hint.PRIMARY -> "first in ${m.firstSource}/${m.clusters} posts"
    }

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v * 100)

    private fun age(t: LocalDateTime, now: LocalDateTime): String {
        val h = Duration.between(t, now).toHours().coerceAtLeast(0)
        return if (h < 48) "${h}h" else "${h / 24}d"
    }
}
