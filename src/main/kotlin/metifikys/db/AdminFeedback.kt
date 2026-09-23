package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Explicit "more / less like this" from the admin chat's moderation buttons, one row per post
 * (its first message), the last press winning. Kept apart from `reaction_counts` on purpose:
 * every `message_reaction_count` update REPLACES a message's whole reaction set, so a row
 * written there would be wiped by the next reaction. The post's category, event and send time
 * are copied in so a deleted post (whose `digest_messages` rows are gone) still counts.
 * `signal`: +1 more, -1 less, -2 deleted.
 */
object AdminFeedbackTable : Table("admin_feedback") {
    val chatId = long("chat_id")
    val messageId = long("message_id")
    val category = varchar("category", 100)
    val eventKey = varchar("event_key", 3000).nullable()
    val sentAt = datetime("sent_at")
    val signal = integer("signal")
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(chatId, messageId)
}

/** Explicit signal values of [AdminFeedbackTable.signal]. */
object ExplicitSignal {
    const val MORE = 1
    const val LESS = -1
    const val DELETED = -2
}

data class AdminFeedbackRow(
    val chatId: Long,
    val messageId: Long,
    val category: String,
    val eventKey: String?,
    val sentAt: LocalDateTime,
    val signal: Int,
    val createdAt: LocalDateTime = LocalDateTime.now()
)

/** An explicit signal joined to its covered event's dimensions, for the affinity aggregation. */
data class ExplicitFeedbackInput(
    val chatId: Long,
    val messageId: Long,
    val category: String,
    val sentAt: LocalDateTime,
    val subject: String,
    val franchise: String,
    val eventType: String,
    val url: String,
    val signal: Int
)

interface AdminFeedbackStore {
    /** Sets (replaces) the explicit signal of one post. */
    fun upsertAdminFeedback(row: AdminFeedbackRow)

    fun fetchAdminFeedback(chatId: Long, messageId: Long): AdminFeedbackRow?

    fun deleteAdminFeedback(chatId: Long, messageId: Long): Boolean

    /**
     * Explicit signals of posts sent in the last [lookbackDays], joined to `covered_events` by
     * (category, event_key) for their dimensions. Legacy posts (no event key) have no dimensions
     * and are left out.
     */
    fun fetchExplicitFeedbackInputs(lookbackDays: Long): List<ExplicitFeedbackInput>

    /** Deletes signals of posts sent more than [days] days ago. */
    fun deleteOldAdminFeedback(days: Long = 90)
}

internal class AdminFeedbackRepository : AdminFeedbackStore {

    override fun upsertAdminFeedback(row: AdminFeedbackRow) {
        transaction {
            AdminFeedbackTable.deleteWhere {
                (AdminFeedbackTable.chatId eq row.chatId) and (AdminFeedbackTable.messageId eq row.messageId)
            }
            AdminFeedbackTable.insert {
                it[chatId] = row.chatId
                it[messageId] = row.messageId
                it[category] = row.category
                it[eventKey] = row.eventKey
                it[sentAt] = row.sentAt
                it[signal] = row.signal
                it[createdAt] = row.createdAt
            }
        }
    }

    override fun fetchAdminFeedback(chatId: Long, messageId: Long): AdminFeedbackRow? = transaction {
        AdminFeedbackTable.selectAll()
            .where { (AdminFeedbackTable.chatId eq chatId) and (AdminFeedbackTable.messageId eq messageId) }
            .map {
                AdminFeedbackRow(
                    chatId = it[AdminFeedbackTable.chatId],
                    messageId = it[AdminFeedbackTable.messageId],
                    category = it[AdminFeedbackTable.category],
                    eventKey = it[AdminFeedbackTable.eventKey],
                    sentAt = it[AdminFeedbackTable.sentAt],
                    signal = it[AdminFeedbackTable.signal],
                    createdAt = it[AdminFeedbackTable.createdAt]
                )
            }
            .firstOrNull()
    }

    override fun deleteAdminFeedback(chatId: Long, messageId: Long): Boolean = transaction {
        AdminFeedbackTable.deleteWhere {
            (AdminFeedbackTable.chatId eq chatId) and (AdminFeedbackTable.messageId eq messageId)
        } > 0
    }

    override fun fetchExplicitFeedbackInputs(lookbackDays: Long): List<ExplicitFeedbackInput> {
        val oldest = LocalDateTime.now().minusDays(lookbackDays)
        return transaction {
            AdminFeedbackTable
                .join(
                    CoveredEventsTable, JoinType.INNER,
                    additionalConstraint = {
                        (AdminFeedbackTable.category eq CoveredEventsTable.category) and
                            (AdminFeedbackTable.eventKey eq CoveredEventsTable.eventKey)
                    }
                )
                .select(
                    AdminFeedbackTable.chatId, AdminFeedbackTable.messageId, AdminFeedbackTable.category,
                    AdminFeedbackTable.sentAt, AdminFeedbackTable.signal,
                    CoveredEventsTable.subject, CoveredEventsTable.franchise,
                    CoveredEventsTable.eventType, CoveredEventsTable.url
                )
                .where { AdminFeedbackTable.sentAt greaterEq oldest }
                .map {
                    ExplicitFeedbackInput(
                        chatId = it[AdminFeedbackTable.chatId],
                        messageId = it[AdminFeedbackTable.messageId],
                        category = it[AdminFeedbackTable.category],
                        sentAt = it[AdminFeedbackTable.sentAt],
                        subject = it[CoveredEventsTable.subject],
                        franchise = it[CoveredEventsTable.franchise],
                        eventType = it[CoveredEventsTable.eventType],
                        url = it[CoveredEventsTable.url],
                        signal = it[AdminFeedbackTable.signal]
                    )
                }
        }
    }

    override fun deleteOldAdminFeedback(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            AdminFeedbackTable.deleteWhere { sentAt less cutoff }
        }
    }
}
