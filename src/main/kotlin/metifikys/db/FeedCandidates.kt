package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Domains outside the configured feeds that published stories keep coming from (plan §24 v1,
 * `feedHealth.candidates`), with the feed autodiscovery found for them. Suggestions only: the bot
 * never adds a feed; the owner reads them in the static report. `status`: [FeedCandidateStatus].
 */
object FeedCandidatesTable : Table("feed_candidates") {
    val domain = varchar("domain", 255)
    /** The most common full host of the domain's published links; what discovery probes. */
    val host = varchar("host", 255)
    val status = varchar("status", 12).default(FeedCandidateStatus.UNCHECKED)
    val feedUrl = varchar("feed_url", 2000).nullable()
    /** Published links from the domain in the last run's window. */
    val published = integer("published").default(0)
    val sampleLink = varchar("sample_link", 2000).default("")
    val entries = integer("entries").nullable()
    val itemsPerDay = double("items_per_day").nullable()
    /** How many of the found feed's newest (≤ 20) links other feeds already brought. */
    val overlap = integer("overlap").nullable()
    val note = varchar("note", 300).nullable()
    val firstSeenAt = datetime("first_seen_at")
    val checkedAt = datetime("checked_at").nullable()
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(domain)
}

/** [FeedCandidatesTable.status] values. `dismissed` is set by hand and never re-probed. */
object FeedCandidateStatus {
    const val UNCHECKED = "unchecked"
    const val FOUND = "found"
    const val NONE = "none"
    const val ERROR = "error"
    const val DISMISSED = "dismissed"
}

data class FeedCandidateRow(
    val domain: String,
    val host: String,
    val status: String,
    val feedUrl: String?,
    val published: Int,
    val sampleLink: String,
    val entries: Int?,
    val itemsPerDay: Double?,
    val overlap: Int?,
    val note: String?,
    val firstSeenAt: LocalDateTime,
    val checkedAt: LocalDateTime?
)

/** A candidate domain as the current run ranked it (see [metifikys.feeds.FeedCandidates]). */
data class FeedCandidateSeen(val domain: String, val host: String, val published: Int, val sampleLink: String)

/** Result of one discovery probe, written by [FeedCandidateStore.recordFeedCandidateCheck]. */
data class FeedCandidateCheck(
    val status: String,
    val feedUrl: String? = null,
    val entries: Int? = null,
    val itemsPerDay: Double? = null,
    val overlap: Int? = null,
    val note: String? = null
)

interface FeedCandidateStore {
    fun fetchFeedCandidates(): List<FeedCandidateRow>

    /**
     * Inserts new candidate domains as `unchecked` and refreshes `published` / `host` /
     * `sample_link` of known ones; status and probe results are left alone.
     */
    fun upsertFeedCandidates(rows: List<FeedCandidateSeen>, now: LocalDateTime)

    fun recordFeedCandidateCheck(domain: String, check: FeedCandidateCheck, now: LocalDateTime)
}

internal class FeedCandidateRepository : FeedCandidateStore {

    override fun fetchFeedCandidates(): List<FeedCandidateRow> = transaction {
        FeedCandidatesTable.selectAll().map { r ->
            FeedCandidateRow(
                domain = r[FeedCandidatesTable.domain],
                host = r[FeedCandidatesTable.host],
                status = r[FeedCandidatesTable.status],
                feedUrl = r[FeedCandidatesTable.feedUrl],
                published = r[FeedCandidatesTable.published],
                sampleLink = r[FeedCandidatesTable.sampleLink],
                entries = r[FeedCandidatesTable.entries],
                itemsPerDay = r[FeedCandidatesTable.itemsPerDay],
                overlap = r[FeedCandidatesTable.overlap],
                note = r[FeedCandidatesTable.note],
                firstSeenAt = r[FeedCandidatesTable.firstSeenAt],
                checkedAt = r[FeedCandidatesTable.checkedAt]
            )
        }
    }

    override fun upsertFeedCandidates(rows: List<FeedCandidateSeen>, now: LocalDateTime) {
        if (rows.isEmpty()) return
        transaction {
            for ((domain, host, published, sample) in rows) {
                val updated = FeedCandidatesTable.update({ FeedCandidatesTable.domain eq domain }) {
                    it[FeedCandidatesTable.host] = host
                    it[FeedCandidatesTable.published] = published
                    it[sampleLink] = sample.take(2000)
                    it[updatedAt] = now
                }
                if (updated == 0) {
                    FeedCandidatesTable.insertIgnore {
                        it[FeedCandidatesTable.domain] = domain
                        it[FeedCandidatesTable.host] = host
                        it[FeedCandidatesTable.published] = published
                        it[sampleLink] = sample.take(2000)
                        it[firstSeenAt] = now
                        it[updatedAt] = now
                    }
                }
            }
        }
    }

    override fun recordFeedCandidateCheck(domain: String, check: FeedCandidateCheck, now: LocalDateTime) {
        transaction {
            FeedCandidatesTable.update({ FeedCandidatesTable.domain eq domain }) {
                it[status] = check.status
                it[feedUrl] = check.feedUrl?.take(2000)
                it[entries] = check.entries
                it[itemsPerDay] = check.itemsPerDay
                it[overlap] = check.overlap
                it[note] = check.note?.take(300)
                it[checkedAt] = now
                it[updatedAt] = now
            }
        }
    }
}
