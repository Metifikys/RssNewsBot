package metifikys.story

import metifikys.db.EventArticleRow
import java.time.LocalDateTime

/**
 * One event's articles as the dedup folded them together (`event_articles`), with the derived
 * facts the story lines read. Pure: no DB, no network, no clock of its own.
 *
 * An outlet is a domain ([domainAliases] folded in); several articles of one domain are one outlet.
 */
class EventCluster(
    val eventKey: String,
    members: List<EventArticleRow>,
    private val domainAliases: Map<String, String> = emptyMap()
) {
    /** Members, one per article (the same article can come from several keys of a chain). */
    val members: List<EventArticleRow> = members.distinctBy { it.articleId }

    /** Members per outlet, in order of each outlet's earliest [timeOf]. Blank domains are no outlet. */
    val byDomain: Map<String, List<EventArticleRow>> = this.members
        .filter { domainOf(it).isNotEmpty() }
        .sortedBy { timeOf(it) }
        .groupBy { domainOf(it) }

    val outlets: Int get() = byDomain.size

    /** The member with the earliest [timeOf]; null for an empty cluster. */
    val earliest: EventArticleRow? get() = members.minByOrNull { timeOf(it) }

    fun domainOf(row: EventArticleRow): String = domainAliases[row.domain] ?: row.domain

    companion object {
        /** A feed date this far past our own first sighting is a future-dated (or "updated at") stamp, not a publish time. */
        const val FUTURE_SLACK_MINUTES = 10L

        /**
         * When a member was published, as best we know: the feed's `pub_date`, unless it lies more
         * than [FUTURE_SLACK_MINUTES] after we first saw the article (a date from the future, or a
         * feed that stamps the last edit) — then our `seen_at`. An undated feed entry already carries
         * its fetch time as `pub_date`.
         */
        fun timeOf(row: EventArticleRow): LocalDateTime {
            val seen = row.seenAt ?: return row.pubDate
            return if (row.pubDate > seen.plusMinutes(FUTURE_SLACK_MINUTES)) seen else row.pubDate
        }

        /**
         * The cluster of [eventKey] merged with the clusters of [relatedKeys] (an update's
         * `relatedPreviousEventKey`), from a [metifikys.db.EventArticleStore.fetchEventClusters] result.
         */
        fun of(
            eventKey: String,
            rowsByKey: Map<String, List<EventArticleRow>>,
            relatedKeys: Collection<String?> = emptyList(),
            domainAliases: Map<String, String> = emptyMap()
        ): EventCluster {
            val keys = (listOf(eventKey) + relatedKeys.filterNotNull()).distinct()
            return EventCluster(eventKey, keys.flatMap { rowsByKey[it].orEmpty() }, domainAliases)
        }
    }
}
