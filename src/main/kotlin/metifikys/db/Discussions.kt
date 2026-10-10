package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * `post.discussion`: one row per discussion thread summarized under a post of a category (see
 * `metifikys.discussion.DiscussionReplier`). The key makes a thread get at most one reply per
 * category — a batch resume, a re-delivery or a `meaningful_update` of the same story finds the
 * row and stays quiet. Threads below the comment threshold and failed calls leave no row, so a
 * later post about the same story may try again.
 */
object DiscussionsTable : Table("discussions") {
    val category = varchar("category", 100)
    /** The source id (`hn`, later `lobsters`, …). */
    val sourceId = varchar("source", 30)
    val threadId = varchar("thread_id", 100)
    val threadUrl = varchar("thread_url", 500)
    val eventKey = varchar("event_key", 300).nullable()
    /** The post the reply answers (its first Telegram message). */
    val postChatId = long("post_chat_id")
    val postMessageId = long("post_message_id")
    /** The reply's message id; null for `skipped` / `log_only`. */
    val replyMessageId = long("reply_message_id").nullable()
    /** The live comment count when the thread was read. */
    val commentCount = integer("comment_count")
    /** How many characters of comments the model saw. */
    val promptChars = integer("prompt_chars")
    /** The model's points as a JSON array (empty when it skipped). */
    val pointsJson = text("points_json")
    /** `sent` / `skipped` / `log_only`. */
    val status = varchar("status", 20)
    val provider = varchar("provider", 50)
    val model = varchar("model", 200)
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(category, sourceId, threadId)
}

data class DiscussionRow(
    val category: String,
    val source: String,
    val threadId: String,
    val threadUrl: String,
    val eventKey: String?,
    val postChatId: Long,
    val postMessageId: Long,
    val replyMessageId: Long?,
    val commentCount: Int,
    val promptChars: Int,
    val pointsJson: String,
    val status: String,
    val provider: String,
    val model: String,
    val createdAt: LocalDateTime
) {
    companion object {
        const val SENT = "sent"
        const val SKIPPED = "skipped"
        const val LOG_ONLY = "log_only"
    }
}

interface DiscussionStore {
    /** Stores [row] unless the thread already has one in that category; false when it had. */
    fun insertDiscussion(row: DiscussionRow): Boolean

    fun findDiscussion(category: String, source: String, threadId: String): DiscussionRow?

    fun deleteOldDiscussions(days: Long)
}

internal class DiscussionRepository : DiscussionStore {

    override fun insertDiscussion(row: DiscussionRow): Boolean = transaction {
        DiscussionsTable.insertIgnore {
            it[category] = row.category
            it[sourceId] = row.source
            it[threadId] = row.threadId
            it[threadUrl] = row.threadUrl.take(500)
            it[eventKey] = row.eventKey
            it[postChatId] = row.postChatId
            it[postMessageId] = row.postMessageId
            it[replyMessageId] = row.replyMessageId
            it[commentCount] = row.commentCount
            it[promptChars] = row.promptChars
            it[pointsJson] = row.pointsJson
            it[status] = row.status
            it[provider] = row.provider
            it[model] = row.model
            it[createdAt] = row.createdAt
        }.insertedCount > 0
    }

    override fun findDiscussion(category: String, source: String, threadId: String): DiscussionRow? = transaction {
        DiscussionsTable.selectAll()
            .where {
                (DiscussionsTable.category eq category) and (DiscussionsTable.sourceId eq source) and
                    (DiscussionsTable.threadId eq threadId)
            }
            .limit(1)
            .map { r ->
                DiscussionRow(
                    category = r[DiscussionsTable.category],
                    source = r[DiscussionsTable.sourceId],
                    threadId = r[DiscussionsTable.threadId],
                    threadUrl = r[DiscussionsTable.threadUrl],
                    eventKey = r[DiscussionsTable.eventKey],
                    postChatId = r[DiscussionsTable.postChatId],
                    postMessageId = r[DiscussionsTable.postMessageId],
                    replyMessageId = r[DiscussionsTable.replyMessageId],
                    commentCount = r[DiscussionsTable.commentCount],
                    promptChars = r[DiscussionsTable.promptChars],
                    pointsJson = r[DiscussionsTable.pointsJson],
                    status = r[DiscussionsTable.status],
                    provider = r[DiscussionsTable.provider],
                    model = r[DiscussionsTable.model],
                    createdAt = r[DiscussionsTable.createdAt]
                )
            }
            .firstOrNull()
    }

    override fun deleteOldDiscussions(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            DiscussionsTable.deleteWhere { createdAt less cutoff }
        }
    }
}
