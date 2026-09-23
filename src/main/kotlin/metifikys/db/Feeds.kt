package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * A link that a feed brought AFTER another feed had already brought it (`articles.feed_url` keeps
 * only the first). Layer 1's unique-link insert otherwise hides that the story arrived twice — the
 * "echo" half of the feed-quality "who was first" metric. A feed re-reads its own old links every
 * tick; those are never recorded, so the table stays small.
 */
object FeedSightingsTable : Table("feed_sightings") {
    val link = varchar("link", 2000)
    val feedUrl = varchar("feed_url", 2000)
    val category = varchar("category", 100)
    val seenAt = datetime("seen_at").index()

    override val primaryKey = PrimaryKey(link, feedUrl)
}

/** One `feed_sightings` row. */
data class FeedSighting(
    val link: String,
    val feedUrl: String,
    val category: String,
    val seenAt: LocalDateTime
)

/** Per-feed bookkeeping for the feed-quality metrics (`feed_sightings`). */
interface FeedStore {
    /** Records second-feed sightings; the first sighting of a `(link, feed)` pair wins. */
    fun insertFeedSightings(rows: List<FeedSighting>)

    /** Sightings seen at or after [since]. */
    fun fetchFeedSightingsSince(since: LocalDateTime): List<FeedSighting>

    /** Retention: drops sightings older than [days] (the same window as the articles). */
    fun deleteOldFeedSightings(days: Long)
}

internal class FeedRepository : FeedStore {

    override fun insertFeedSightings(rows: List<FeedSighting>) {
        if (rows.isEmpty()) return
        transaction {
            for (r in rows) {
                FeedSightingsTable.insertIgnore {
                    it[link] = r.link
                    it[feedUrl] = r.feedUrl
                    it[category] = r.category
                    it[seenAt] = r.seenAt
                }
            }
        }
    }

    override fun fetchFeedSightingsSince(since: LocalDateTime): List<FeedSighting> = transaction {
        FeedSightingsTable.selectAll()
            .where { FeedSightingsTable.seenAt greaterEq since }
            .map {
                FeedSighting(
                    link = it[FeedSightingsTable.link],
                    feedUrl = it[FeedSightingsTable.feedUrl],
                    category = it[FeedSightingsTable.category],
                    seenAt = it[FeedSightingsTable.seenAt]
                )
            }
    }

    override fun deleteOldFeedSightings(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            FeedSightingsTable.deleteWhere { seenAt less cutoff }
        }
    }
}
