package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

object RejectedEventsTable : Table("rejected_events") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100).index()
    val eventKey = varchar("event_key", 3000)
    val subject = varchar("subject", 500).default("")
    val franchise = varchar("franchise", 200).default("")
    val eventType = varchar("event_type", 100).default("")
    val coreFact = text("core_fact")
    val importance = integer("importance").default(0)
    val newsworthiness = integer("newsworthiness").default(0)
    val digestFit = integer("digest_fit").default(0)
    // Indexed: the archive's `embeddings: labelled` check looks articles up here by link.
    val url = varchar("url", 2000).default("").index()
    val status = varchar("status", 50)
    val relatedPreviousEventKey = varchar("related_previous_event_key", 3000).nullable()
    val articleIndex = integer("article_index").default(-1)
    val extractedAt = datetime("extracted_at").index()

    override val primaryKey = PrimaryKey(id)
}

data class RejectedEventRow(
    val category: String,
    val eventKey: String,
    val subject: String = "",
    val franchise: String = "",
    val eventType: String = "",
    val coreFact: String,
    val importance: Int = 0,
    val newsworthiness: Int = 0,
    val digestFit: Int = 0,
    val url: String = "",
    val status: String,
    val relatedPreviousEventKey: String? = null,
    val articleIndex: Int = -1,
    val extractedAt: LocalDateTime
)

interface RejectedEventStore {
    /** Appends a batch of rejected/duplicate Step 1 extractions for prompt tuning. */
    fun saveRejectedEvents(events: List<RejectedEventRow>)

    /**
     * Returns up to [limit] rejected-event rows for [category] within [sinceDays], newest-first,
     * whose status is in [statuses]. The weekly roundup ranker uses the `"duplicate"` rows as the
     * "this story showed up again" signal — each one is a later sighting of an already-covered
     * event, so counting them per story yields the "most duplicates / most repeated" measure.
     */
    fun fetchRecentRejectedEvents(
        category: String,
        sinceDays: Long,
        limit: Int,
        statuses: Set<String> = setOf("duplicate")
    ): List<RejectedEventRow>

    /**
     * Step-1 extractions with a status in [statuses] per (category, franchise) since [since]; the
     * franchise trimmed and lowercased, blank ones skipped. Backs the "never gets through" mute
     * proposals.
     */
    fun countRejectedFranchisesSince(
        since: LocalDateTime,
        statuses: Set<String> = setOf("rejected")
    ): Map<Pair<String, String>, Int>

    /** The newest Step 1 rejection / duplicate of each of [urls] in [category], keyed by url (`/raw`). */
    fun fetchRejectedEventsByUrls(category: String, urls: Collection<String>): Map<String, RejectedEventRow>

    /** Deletes rejected-event log entries older than [days] days. */
    fun deleteOldRejectedEvents(days: Long = 30)
}

internal class RejectedEventRepository : RejectedEventStore {

    override fun saveRejectedEvents(events: List<RejectedEventRow>) {
        if (events.isEmpty()) return
        transaction {
            for (event in events) {
                RejectedEventsTable.insert {
                    it[category] = event.category
                    it[eventKey] = event.eventKey
                    it[subject] = event.subject
                    it[franchise] = event.franchise
                    it[eventType] = event.eventType
                    it[coreFact] = event.coreFact
                    it[importance] = event.importance
                    it[newsworthiness] = event.newsworthiness
                    it[digestFit] = event.digestFit
                    it[url] = event.url
                    it[status] = event.status
                    it[relatedPreviousEventKey] = event.relatedPreviousEventKey
                    it[articleIndex] = event.articleIndex
                    it[extractedAt] = event.extractedAt
                }
            }
        }
    }

    override fun fetchRecentRejectedEvents(
        category: String,
        sinceDays: Long,
        limit: Int,
        statuses: Set<String>
    ): List<RejectedEventRow> {
        if (limit <= 0 || statuses.isEmpty()) return emptyList()
        val cutoff = LocalDateTime.now().minusDays(sinceDays)
        return transaction {
            RejectedEventsTable
                .selectAll()
                .where {
                    (RejectedEventsTable.category eq category) and
                        (RejectedEventsTable.status inList statuses) and
                        (RejectedEventsTable.extractedAt greaterEq cutoff)
                }
                .orderBy(RejectedEventsTable.extractedAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toRow() }
        }
    }

    override fun fetchRejectedEventsByUrls(category: String, urls: Collection<String>): Map<String, RejectedEventRow> {
        val wanted = urls.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return transaction {
            RejectedEventsTable
                .selectAll()
                .where { (RejectedEventsTable.category eq category) and (RejectedEventsTable.url inList wanted) }
                .orderBy(RejectedEventsTable.extractedAt, SortOrder.ASC)
                .map { it.toRow() }
                // Ascending, so the newest extraction of a url overwrites the older ones.
                .associateBy { it.url }
        }
    }

    private fun ResultRow.toRow() = RejectedEventRow(
        category = this[RejectedEventsTable.category],
        eventKey = this[RejectedEventsTable.eventKey],
        subject = this[RejectedEventsTable.subject],
        franchise = this[RejectedEventsTable.franchise],
        eventType = this[RejectedEventsTable.eventType],
        coreFact = this[RejectedEventsTable.coreFact],
        importance = this[RejectedEventsTable.importance],
        newsworthiness = this[RejectedEventsTable.newsworthiness],
        digestFit = this[RejectedEventsTable.digestFit],
        url = this[RejectedEventsTable.url],
        status = this[RejectedEventsTable.status],
        relatedPreviousEventKey = this[RejectedEventsTable.relatedPreviousEventKey],
        articleIndex = this[RejectedEventsTable.articleIndex],
        extractedAt = this[RejectedEventsTable.extractedAt]
    )

    override fun countRejectedFranchisesSince(
        since: LocalDateTime,
        statuses: Set<String>
    ): Map<Pair<String, String>, Int> = transaction {
        RejectedEventsTable
            .select(RejectedEventsTable.category, RejectedEventsTable.franchise)
            .where { (RejectedEventsTable.extractedAt greaterEq since) and (RejectedEventsTable.status inList statuses) }
            .mapNotNull { row ->
                val f = row[RejectedEventsTable.franchise].trim().lowercase()
                if (f.isEmpty()) null else row[RejectedEventsTable.category] to f
            }
            .groupingBy { it }
            .eachCount()
    }

    override fun deleteOldRejectedEvents(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            RejectedEventsTable.deleteWhere { extractedAt less cutoff }
        }
    }
}
