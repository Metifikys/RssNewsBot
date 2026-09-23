package metifikys.feeds

import metifikys.config.FeedHealthThresholds
import metifikys.db.DropReason
import metifikys.db.FeedArticleOutcome
import metifikys.db.FeedSighting
import metifikys.db.FeedStatsRow
import metifikys.model.ArticleStatus
import java.net.URI
import java.time.Duration
import java.time.LocalDateTime

/**
 * Per-feed quality metrics over one DB snapshot (plan §23c). Pure: no DB, no clock of its own.
 *
 * Outcome of every article a feed brought in the window (by `fetched_at`), in this order:
 * pending (still queued, not counted) → muted (Step 0) → published (its link is in a sent post) →
 * L2 duplicate → Step 1 duplicate → Step 1 rejected → unselected (PROCESSED otherwise: shortlist,
 * ranker, cooldown, Layer 3.5 — not persisted separately).
 *
 * "Who was first": every published post is a cluster — its links, the L2 duplicates pointing at
 * them, and the second-feed sightings of those links. The feed with the earliest `fetched_at` /
 * `seen_at` in a cluster is its first source; every other feed in it is an echo, with a lag.
 */
object FeedQuality {

    enum class Hint(val emoji: String, val severity: Int) {
        DEAD("💀", 0), NOISY("🗑", 1), STALE("💤", 2), MIRROR("🪞", 3), PRIMARY("⭐", 9);

        /** ⭐ is praise, not an anomaly. */
        val anomaly: Boolean get() = this != PRIMARY
    }

    data class Input(
        /** Configured feeds: `url → category`; metrics are reported for these only. */
        val feeds: Map<String, String>,
        val outcomes: List<FeedArticleOutcome>,
        val publishedGroups: List<Set<String>>,
        val sightings: List<FeedSighting>,
        val stats: List<FeedStatsRow>,
        /** Newest `fetched_at` per feed over the whole table. */
        val lastNew: Map<String, LocalDateTime>,
        /** Oldest `fetched_at` in the table; staleness is not judged before tracking is that old. */
        val trackingSince: LocalDateTime?,
        val now: LocalDateTime
    )

    data class FeedMetrics(
        val feedUrl: String,
        val category: String,
        val total: Int,
        val pending: Int,
        val published: Int,
        val muted: Int,
        val dupL2: Int,
        val step1Duplicate: Int,
        val step1Rejected: Int,
        val unselected: Int,
        /** Clusters (published posts) this feed took part in where it was first / not first. */
        val firstSource: Int,
        val echo: Int,
        val medianLagMinutes: Long?,
        val lastSuccess: LocalDateTime?,
        val lastNew: LocalDateTime?,
        val failStreak: Int,
        val lastError: String?,
        /** Mean junk-classifier score of the window's articles; null until that column exists. */
        val avgJunk: Double?,
        val hints: List<Hint>
    ) {
        val decided: Int get() = total - pending
        val publishRate: Double get() = if (decided > 0) published.toDouble() / decided else 0.0
        val junkRate: Double get() = if (decided > 0) (muted + step1Rejected + unselected).toDouble() / decided else 0.0
        val clusters: Int get() = firstSource + echo
        val firstShare: Double get() = if (clusters > 0) firstSource.toDouble() / clusters else 0.0
    }

    fun compute(input: Input, t: FeedHealthThresholds): List<FeedMetrics> {
        val published = input.publishedGroups.flatten().toSet()
        val byFeed = input.outcomes.groupBy { it.feedUrl }
        val stats = input.stats.associateBy { it.feedUrl }
        val clusterStats = clusterStats(input)
        return input.feeds.map { (url, category) ->
            val rows = byFeed[url].orEmpty()
            var pending = 0; var muted = 0; var pub = 0; var dup = 0; var s1dup = 0; var s1rej = 0; var unsel = 0
            for (r in rows) {
                when {
                    r.status == ArticleStatus.UNPROCESSED.name || r.status == ArticleStatus.PROCESSING.name -> pending++
                    r.dropReason == DropReason.MUTE -> muted++
                    r.link in published -> pub++
                    r.status == ArticleStatus.DUPLICATE.name -> dup++
                    r.step1 == "duplicate" -> s1dup++
                    r.step1 != null -> s1rej++
                    else -> unsel++
                }
            }
            val cs = clusterStats[url]
            val st = stats[url]
            val junk = rows.mapNotNull { it.junkScore }
            val m = FeedMetrics(
                feedUrl = url, category = category, total = rows.size, pending = pending, published = pub,
                muted = muted, dupL2 = dup, step1Duplicate = s1dup, step1Rejected = s1rej, unselected = unsel,
                firstSource = cs?.first ?: 0, echo = cs?.echo ?: 0, medianLagMinutes = cs?.medianLag(),
                lastSuccess = st?.lastSuccessAt, lastNew = input.lastNew[url], failStreak = st?.failStreak ?: 0,
                lastError = st?.lastError, avgJunk = if (junk.isEmpty()) null else junk.average(),
                hints = emptyList()
            )
            m.copy(hints = hints(m, st, input, t))
        }
    }

    private class ClusterStat {
        var first = 0
        var echo = 0
        val lags = mutableListOf<Long>()
        fun medianLag(): Long? = if (lags.isEmpty()) null else lags.sorted()[lags.size / 2]
    }

    private fun clusterStats(input: Input): Map<String, ClusterStat> {
        val byLink = input.outcomes.associateBy { it.link }
        val dupsOf = input.outcomes.filter { it.duplicateOf != null }.groupBy { it.duplicateOf!! }
        val sightingsByLink = input.sightings.groupBy { it.link }
        val out = HashMap<String, ClusterStat>()
        for (group in input.publishedGroups) {
            // Earliest arrival per feed within this cluster.
            val arrival = HashMap<String, LocalDateTime>()
            fun seen(feed: String, at: LocalDateTime) {
                arrival.merge(feed, at) { a, b -> if (b < a) b else a }
            }
            for (link in group) {
                val a = byLink[link]
                if (a != null) {
                    seen(a.feedUrl, a.fetchedAt)
                    dupsOf[a.id].orEmpty().forEach { d -> seen(d.feedUrl, d.fetchedAt) }
                }
                sightingsByLink[link].orEmpty().forEach { s -> seen(s.feedUrl, s.seenAt) }
            }
            if (arrival.isEmpty()) continue
            val firstAt = arrival.values.min()
            for ((feed, at) in arrival) {
                val s = out.getOrPut(feed) { ClusterStat() }
                // Same fetch pass = same minute: both count as first (the order inside a pass is noise).
                val lag = Duration.between(firstAt, at).toMinutes()
                if (lag <= 0) s.first++ else { s.echo++; s.lags += lag }
            }
        }
        return out
    }

    private fun hints(m: FeedMetrics, st: FeedStatsRow?, input: Input, t: FeedHealthThresholds): List<Hint> {
        val now = input.now
        val out = mutableListOf<Hint>()
        val deadSince = now.minusDays(t.deadDays)
        val dead = st != null && (st.failStreak >= t.deadFailStreak || (st.lastSuccessAt != null && st.lastSuccessAt < deadSince))
        if (dead) out += Hint.DEAD
        val staleBefore = now.minusDays(t.staleNewDays)
        val tracked = input.trackingSince != null && input.trackingSince < staleBefore
        val fetching = st?.lastSuccessAt?.let { it >= deadSince } == true
        if (!dead && fetching && tracked && (m.lastNew == null || m.lastNew < staleBefore)) out += Hint.STALE
        if (m.total >= t.noisyMinArticles && m.publishRate * 100 < t.noisyMaxPublishedPct &&
            m.junkRate * 100 > t.noisyMinJunkPct
        ) out += Hint.NOISY
        if (m.clusters >= t.minPublishedForFirst) {
            if (m.firstShare * 100 < t.mirrorMaxFirstPct) out += Hint.MIRROR
            if (m.firstShare * 100 >= t.primaryMinFirstPct) out += Hint.PRIMARY
        }
        return out
    }

    /**
     * Short human name of a feed: its `label`, else host + path (reddit `r/<sub>`, an RSSHub-style
     * `/telegram/channel/<name>` route keeps its last segments), capped at [max] chars.
     */
    fun displayName(url: String, label: String? = null, max: Int = 48): String {
        label?.takeIf { it.isNotBlank() }?.let { return it.take(max) }
        val name = try {
            val u = URI(url.trim())
            val host = (u.host ?: return url.take(max)).lowercase().removePrefix("www.").removePrefix("old.")
            val segs = (u.path ?: "").split('/').filter { it.isNotEmpty() }
            when {
                host.endsWith("reddit.com") && segs.getOrNull(0) == "r" && segs.size > 1 -> "reddit r/${segs[1]}"
                segs.isEmpty() -> host
                else -> "$host/${segs.joinToString("/")}"
            }
        } catch (e: Exception) {
            url
        }
        return if (name.length <= max) name else name.take(max - 1) + "…"
    }
}
