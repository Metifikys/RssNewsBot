package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Structured dedup memory: one row per distinct (category, event_key).
 * Upserted after a shortlist is successfully delivered to Telegram.
 * Queried each cycle to build `PREVIOUSLY_COVERED_EVENTS_JSON` for Step 1.
 */
object CoveredEventsTable : Table("covered_events") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100).index()
    val eventKey = varchar("event_key", 3000)
    val subject = varchar("subject", 500).default("")
    val franchise = varchar("franchise", 200).default("")
    val eventType = varchar("event_type", 100).default("")
    val coreFact = text("core_fact")
    val importance = integer("importance").default(0)
    /** Raw news weight 0–10 emitted by Step 1. 0 for rows written before the split. */
    val newsworthiness = integer("newsworthiness").default(0)
    /** Editorial-fit weight 0–10 emitted by Step 1. 0 for rows written before the split. */
    val digestFit = integer("digest_fit").default(0)
    val url = varchar("url", 2000).default("")
    val coveredAt = datetime("covered_at").index()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_covered_events_cat_key", category, eventKey)
    }
}

data class CoveredEventRow(
    val category: String,
    val eventKey: String,
    val subject: String,
    val franchise: String,
    val eventType: String,
    val coreFact: String,
    val importance: Int,
    val newsworthiness: Int = 0,
    val digestFit: Int = 0,
    val url: String,
    val coveredAt: LocalDateTime
)

/** Step 1 dedup memory (`covered_events`). */
interface CoveredEventStore {
    /**
     * Upserts covered events. On conflict against (category, event_key) the existing row's
     * `core_fact`, `importance`, `url`, and `covered_at` are refreshed; the key columns are
     * preserved. Static descriptive fields (`subject`, `franchise`, `event_type`) are NOT
     * refreshed — they are expected to be stable for a given event_key, and dropping updates
     * avoids occasional LLM drift destabilizing the row identity.
     */
    fun insertCoveredEvents(events: List<CoveredEventRow>)

    /** Returns up to [limit] covered events for [category] within [sinceDays], newest-first. */
    fun fetchRecentEvents(category: String, sinceDays: Long, limit: Int): List<CoveredEventRow>

    /**
     * Covered events for [category] whose `event_key` is in [eventKeys] and `covered_at >= since`.
     * Keyed lookup for the meaningful-update cooldown: unlike [fetchRecentEvents] it is not
     * bounded by `maxContextEvents`, so an event that has already scrolled out of the Step 1
     * prompt context can still be found for the cooldown check.
     */
    fun fetchCoveredEventsByKeys(category: String, eventKeys: Collection<String>, since: LocalDateTime): List<CoveredEventRow>

    /** Deletes covered events older than [retentionDays]. */
    fun pruneOldCoveredEvents(retentionDays: Long)
}

internal class CoveredEventRepository : CoveredEventStore {

    override fun insertCoveredEvents(events: List<CoveredEventRow>) {
        if (events.isEmpty()) return
        transaction {
            for (event in events) {
                val updatedRows = CoveredEventsTable.update(
                    {
                        (CoveredEventsTable.category eq event.category) and
                            (CoveredEventsTable.eventKey eq event.eventKey)
                    }
                ) {
                    it[coreFact] = event.coreFact
                    it[importance] = event.importance
                    it[newsworthiness] = event.newsworthiness
                    it[digestFit] = event.digestFit
                    it[url] = event.url
                    it[coveredAt] = event.coveredAt
                }
                if (updatedRows == 0) {
                    CoveredEventsTable.insertIgnore {
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
                        it[coveredAt] = event.coveredAt
                    }
                }
            }
        }
    }

    override fun fetchRecentEvents(category: String, sinceDays: Long, limit: Int): List<CoveredEventRow> {
        if (limit <= 0) return emptyList()
        val cutoff = LocalDateTime.now().minusDays(sinceDays)
        return transaction {
            CoveredEventsTable
                .selectAll()
                .where {
                    (CoveredEventsTable.category eq category) and
                        (CoveredEventsTable.coveredAt greaterEq cutoff)
                }
                .orderBy(CoveredEventsTable.coveredAt, SortOrder.DESC)
                .limit(limit)
                .map {
                    CoveredEventRow(
                        category = it[CoveredEventsTable.category],
                        eventKey = it[CoveredEventsTable.eventKey],
                        subject = it[CoveredEventsTable.subject],
                        franchise = it[CoveredEventsTable.franchise],
                        eventType = it[CoveredEventsTable.eventType],
                        coreFact = it[CoveredEventsTable.coreFact],
                        importance = it[CoveredEventsTable.importance],
                        newsworthiness = it[CoveredEventsTable.newsworthiness],
                        digestFit = it[CoveredEventsTable.digestFit],
                        url = it[CoveredEventsTable.url],
                        coveredAt = it[CoveredEventsTable.coveredAt]
                    )
                }
        }
    }

    override fun fetchCoveredEventsByKeys(category: String, eventKeys: Collection<String>, since: LocalDateTime): List<CoveredEventRow> {
        if (eventKeys.isEmpty()) return emptyList()
        return transaction {
            CoveredEventsTable
                .selectAll()
                .where {
                    (CoveredEventsTable.category eq category) and
                        (CoveredEventsTable.eventKey inList eventKeys.toSet()) and
                        (CoveredEventsTable.coveredAt greaterEq since)
                }
                .map {
                    CoveredEventRow(
                        category = it[CoveredEventsTable.category],
                        eventKey = it[CoveredEventsTable.eventKey],
                        subject = it[CoveredEventsTable.subject],
                        franchise = it[CoveredEventsTable.franchise],
                        eventType = it[CoveredEventsTable.eventType],
                        coreFact = it[CoveredEventsTable.coreFact],
                        importance = it[CoveredEventsTable.importance],
                        newsworthiness = it[CoveredEventsTable.newsworthiness],
                        digestFit = it[CoveredEventsTable.digestFit],
                        url = it[CoveredEventsTable.url],
                        coveredAt = it[CoveredEventsTable.coveredAt]
                    )
                }
        }
    }

    override fun pruneOldCoveredEvents(retentionDays: Long) {
        val cutoff = LocalDateTime.now().minusDays(retentionDays)
        transaction {
            CoveredEventsTable.deleteWhere { coveredAt less cutoff }
        }
    }
}
