package metifikys.story

import java.time.Duration
import java.time.LocalDateTime

/**
 * "Who wrote first" (plan item 4) for one story: the outlet whose earliest member has the smallest
 * publish time ([EventCluster.timeOf] — the feed date unless it lies in the future of our own first
 * sighting), and its lead over the second outlet. Among our feeds only: the original is often
 * paywalled, on social media or in no feed at all. Pure.
 */
object Genealogy {

    data class Result(
        val firstDomain: String,
        val firstAt: LocalDateTime,
        /** Minutes between the first and the second outlet; null for a single-outlet story. */
        val leadMinutes: Long?,
        val outlets: Int
    )

    fun first(cluster: EventCluster): Result? {
        val arrivals = arrivals(cluster).entries.sortedBy { it.value }
        val first = arrivals.firstOrNull() ?: return null
        val lead = arrivals.getOrNull(1)?.let { Duration.between(first.value, it.value).toMinutes() }
        return Result(first.key, first.value, lead, arrivals.size)
    }

    /** Earliest publish time per outlet. */
    fun arrivals(cluster: EventCluster): Map<String, LocalDateTime> =
        cluster.byDomain.mapValues { (_, rows) -> rows.minOf { EventCluster.timeOf(it) } }
}
