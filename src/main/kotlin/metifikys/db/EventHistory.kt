package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Long-lived, append-only memory of the events that reached a channel (`storyIntel.enabled`): one
 * row per (category, event key), written at delivery next to `covered_events` and never updated —
 * the first appearance is what is kept. Unlike `covered_events` (14/90 days, upserted) it keeps
 * years (`storyIntel.historyRetentionDays`), so it can answer "how often was this game announced
 * without a date" (the 🌀 vaporware line) and "how many layoffs / releases per week" (the weekly
 * barometer).
 */
object EventHistoryTable : Table("event_history") {
    val category = varchar("category", 100)
    val eventKey = varchar("event_key", 3000)
    /** [metifikys.format.Hashtags.entityKey] of the subject (the franchise when there is none); "" when neither normalizes. */
    val entityKey = varchar("entity_key", 200).default("")
    val subject = varchar("subject", 500).default("")
    val franchise = varchar("franchise", 200).default("")
    val eventType = varchar("event_type", 100).default("")
    val coreFact = text("core_fact")
    /** Step 1 `eventDate` (TrackerDates vocabulary: day, month, quarter or year); null when none. */
    val eventDate = varchar("event_date", 20).nullable()
    val newsworthiness = integer("newsworthiness").default(0)
    val coveredAt = datetime("covered_at").index()

    override val primaryKey = PrimaryKey(category, eventKey)

    init {
        index("idx_event_history_cat_entity", false, category, entityKey)
    }
}

data class EventHistoryRow(
    val category: String,
    val eventKey: String,
    val entityKey: String,
    val subject: String,
    val franchise: String,
    val eventType: String,
    val coreFact: String,
    val eventDate: String?,
    val newsworthiness: Int,
    val coveredAt: LocalDateTime
)

interface EventHistoryStore {
    /** Inserts rows whose (category, event key) is new; an existing row is kept as it is (first appearance wins). */
    fun insertEventHistory(rows: List<EventHistoryRow>)

    /** Rows of [category] per entity key, for the non-blank [entityKeys] that have any, oldest first. */
    fun fetchEventHistoryByEntities(category: String, entityKeys: Collection<String>): Map<String, List<EventHistoryRow>>

    /** Rows of [category] covered at or after [since], oldest first. */
    fun fetchEventHistorySince(category: String, since: LocalDateTime): List<EventHistoryRow>

    /** Earliest `covered_at` of [category]; null when it has no rows. */
    fun earliestEventHistory(category: String): LocalDateTime?

    /** True when the table has no rows at all (the one-off warm-up from `covered_events` runs then). */
    fun isEventHistoryEmpty(): Boolean

    /** Deletes rows covered more than [days] days ago (`storyIntel.historyRetentionDays`). */
    fun deleteOldEventHistory(days: Long)
}

internal class EventHistoryRepository : EventHistoryStore {

    override fun insertEventHistory(rows: List<EventHistoryRow>) {
        if (rows.isEmpty()) return
        transaction {
            for (row in rows) {
                EventHistoryTable.insertIgnore {
                    it[category] = row.category
                    it[eventKey] = row.eventKey
                    it[entityKey] = row.entityKey
                    it[subject] = row.subject.take(500)
                    it[franchise] = row.franchise.take(200)
                    it[eventType] = row.eventType.take(100)
                    it[coreFact] = row.coreFact
                    it[eventDate] = row.eventDate?.take(20)
                    it[newsworthiness] = row.newsworthiness
                    it[coveredAt] = row.coveredAt
                }
            }
        }
    }

    override fun fetchEventHistoryByEntities(category: String, entityKeys: Collection<String>): Map<String, List<EventHistoryRow>> {
        val keys = entityKeys.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return emptyMap()
        return transaction {
            EventHistoryTable.selectAll()
                .where { (EventHistoryTable.category eq category) and (EventHistoryTable.entityKey inList keys) }
                .orderBy(EventHistoryTable.coveredAt, SortOrder.ASC)
                .map { it.toRow() }
                .groupBy { it.entityKey }
        }
    }

    override fun fetchEventHistorySince(category: String, since: LocalDateTime): List<EventHistoryRow> = transaction {
        EventHistoryTable.selectAll()
            .where { (EventHistoryTable.category eq category) and (EventHistoryTable.coveredAt greaterEq since) }
            .orderBy(EventHistoryTable.coveredAt, SortOrder.ASC)
            .map { it.toRow() }
    }

    override fun earliestEventHistory(category: String): LocalDateTime? = transaction {
        EventHistoryTable.select(EventHistoryTable.coveredAt)
            .where { EventHistoryTable.category eq category }
            .orderBy(EventHistoryTable.coveredAt, SortOrder.ASC)
            .limit(1)
            .firstOrNull()
            ?.get(EventHistoryTable.coveredAt)
    }

    override fun isEventHistoryEmpty(): Boolean = transaction {
        EventHistoryTable.select(EventHistoryTable.category).limit(1).empty()
    }

    override fun deleteOldEventHistory(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            EventHistoryTable.deleteWhere { coveredAt less cutoff }
        }
    }

    private fun ResultRow.toRow() = EventHistoryRow(
        category = this[EventHistoryTable.category],
        eventKey = this[EventHistoryTable.eventKey],
        entityKey = this[EventHistoryTable.entityKey],
        subject = this[EventHistoryTable.subject],
        franchise = this[EventHistoryTable.franchise],
        eventType = this[EventHistoryTable.eventType],
        coreFact = this[EventHistoryTable.coreFact],
        eventDate = this[EventHistoryTable.eventDate],
        newsworthiness = this[EventHistoryTable.newsworthiness],
        coveredAt = this[EventHistoryTable.coveredAt]
    )
}
