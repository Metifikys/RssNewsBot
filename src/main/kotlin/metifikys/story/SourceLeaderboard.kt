package metifikys.story

import java.time.Duration

/**
 * The per-outlet "who writes first" ranking over many stories (plan item 4). Pure.
 *
 * Only stories with at least `minOutlets` outlets count (a two-outlet story says little about
 * speed). Per outlet: `events` it took part in, `firsts` (its earliest article is the story's
 * earliest — a tie within [TIE_MINUTES] counts for both), `firstRate`, the median lag behind the
 * first outlet over the stories it was not first in, and — when a reprint set is given — the share
 * of its articles that were retellings ([Hype]). Outlets with fewer than `minEvents` stories are
 * left out.
 */
object SourceLeaderboard {

    /** Two outlets within this many minutes of each other both count as first (fetch-pass noise). */
    const val TIE_MINUTES = 1L

    data class Row(
        val domain: String,
        val events: Int,
        val firsts: Int,
        val medianLagMinutes: Long?,
        /** Share of this outlet's articles classified as retellings; null when not computed. */
        val reprintShare: Double?
    ) {
        val firstRate: Double get() = if (events > 0) firsts.toDouble() / events else 0.0
    }

    /** Most-often-first first, then more stories, then name. */
    fun compute(
        clusters: List<EventCluster>,
        minOutlets: Int = 3,
        minEvents: Int = 1,
        reprints: Set<Int>? = null
    ): List<Row> {
        class Acc {
            var events = 0
            var firsts = 0
            val lags = mutableListOf<Long>()
            var articles = 0
            var reprinted = 0
        }
        val acc = LinkedHashMap<String, Acc>()
        for (c in clusters) {
            if (c.outlets < minOutlets) continue
            val arrivals = Genealogy.arrivals(c)
            val firstAt = arrivals.values.min()
            for ((domain, at) in arrivals) {
                val a = acc.getOrPut(domain) { Acc() }
                a.events++
                val lag = Duration.between(firstAt, at).toMinutes()
                if (lag < TIE_MINUTES) a.firsts++ else a.lags += lag
            }
            if (reprints != null) {
                for ((domain, rows) in c.byDomain) {
                    val a = acc.getValue(domain)
                    a.articles += rows.size
                    a.reprinted += rows.count { it.articleId in reprints }
                }
            }
        }
        return acc.filter { it.value.events >= minEvents }.map { (domain, a) ->
            Row(
                domain = domain,
                events = a.events,
                firsts = a.firsts,
                medianLagMinutes = a.lags.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] },
                reprintShare = if (reprints != null && a.articles > 0) a.reprinted.toDouble() / a.articles else null
            )
        }.sortedWith(compareByDescending<Row> { it.firstRate }.thenByDescending { it.events }.thenBy { it.domain })
    }
}
