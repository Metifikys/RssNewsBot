package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
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
