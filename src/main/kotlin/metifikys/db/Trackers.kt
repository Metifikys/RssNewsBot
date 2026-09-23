package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * State of the living trackers (`categories.<name>.trackers`): one row per
 * (category, tracker, entity) — the Step 1 subject normalized to an entity key — with the
 * event's date as Step 1 read it, the previous date when it moved, and the latest post about
 * it for the link. The rendered message id lives in `bot_state` (`tracker:<category>:<id>`).
 */
object TrackerEntriesTable : Table("tracker_entries") {
    val category = varchar("category", 100)
    val trackerId = varchar("tracker_id", 60)
    val entityKey = varchar("entity_key", 200)
    val title = text("title")
    val eventDate = varchar("event_date", 16).nullable()
    val prevDate = varchar("prev_date", 16).nullable()
    val status = varchar("status", 20)
    val eventKey = varchar("event_key", 3000)
    val chatId = long("chat_id").nullable()
    val messageId = long("message_id").nullable()
    val updatedAt = datetime("updated_at").index()

    override val primaryKey = PrimaryKey(category, trackerId, entityKey)
}

/** One entity's line in a tracker. `status` ∈ scheduled | delayed | tba. Dates use [metifikys.digest.TrackerDates] forms. */
data class TrackerEntryRow(
    val category: String,
    val trackerId: String,
    val entityKey: String,
    val title: String,
    val eventDate: String?,
    val prevDate: String?,
    val status: String,
    val eventKey: String,
    val chatId: Long?,
    val messageId: Long?,
    val updatedAt: LocalDateTime
)

interface TrackerStore {
    fun fetchTrackerEntries(category: String, trackerId: String): List<TrackerEntryRow>

    fun upsertTrackerEntry(row: TrackerEntryRow)

    /** Entries not touched by any digest in [days] days: a stale TBA or a long-released title. */
    fun deleteOldTrackerEntries(days: Long = 365)
}

internal class TrackerRepository : TrackerStore {

    override fun fetchTrackerEntries(category: String, trackerId: String): List<TrackerEntryRow> = transaction {
        TrackerEntriesTable
            .selectAll()
            .where { (TrackerEntriesTable.category eq category) and (TrackerEntriesTable.trackerId eq trackerId) }
            .map {
                TrackerEntryRow(
                    category = it[TrackerEntriesTable.category],
                    trackerId = it[TrackerEntriesTable.trackerId],
                    entityKey = it[TrackerEntriesTable.entityKey],
                    title = it[TrackerEntriesTable.title],
                    eventDate = it[TrackerEntriesTable.eventDate],
                    prevDate = it[TrackerEntriesTable.prevDate],
                    status = it[TrackerEntriesTable.status],
                    eventKey = it[TrackerEntriesTable.eventKey],
                    chatId = it[TrackerEntriesTable.chatId],
                    messageId = it[TrackerEntriesTable.messageId],
                    updatedAt = it[TrackerEntriesTable.updatedAt]
                )
            }
    }

    override fun upsertTrackerEntry(row: TrackerEntryRow) {
        transaction {
            val updated = TrackerEntriesTable.update(
                {
                    (TrackerEntriesTable.category eq row.category) and
                        (TrackerEntriesTable.trackerId eq row.trackerId) and
                        (TrackerEntriesTable.entityKey eq row.entityKey)
                }
            ) {
                it[title] = row.title
                it[eventDate] = row.eventDate
                it[prevDate] = row.prevDate
                it[status] = row.status
                it[eventKey] = row.eventKey
                it[chatId] = row.chatId
                it[messageId] = row.messageId
                it[updatedAt] = row.updatedAt
            }
            if (updated == 0) {
                TrackerEntriesTable.insertIgnore {
                    it[category] = row.category
                    it[trackerId] = row.trackerId
                    it[entityKey] = row.entityKey
                    it[title] = row.title
                    it[eventDate] = row.eventDate
                    it[prevDate] = row.prevDate
                    it[status] = row.status
                    it[eventKey] = row.eventKey
                    it[chatId] = row.chatId
                    it[messageId] = row.messageId
                    it[updatedAt] = row.updatedAt
                }
            }
        }
    }

    override fun deleteOldTrackerEntries(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            TrackerEntriesTable.deleteWhere { updatedAt less cutoff }
        }
    }
}
