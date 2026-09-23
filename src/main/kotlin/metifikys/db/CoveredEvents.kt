package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
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
