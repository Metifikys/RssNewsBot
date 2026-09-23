package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
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
    val url = varchar("url", 2000).default("")
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
