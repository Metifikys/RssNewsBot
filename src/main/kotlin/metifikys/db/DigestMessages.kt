package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * Links each delivered digest message to the article(s)/event it rendered, so reactions
 * Telegram later reports per `(chat_id, message_id)` can be joined back to a category and event.
 * One row per Telegram message actually sent (a topic is normally one message). `event_key` is
 * null for legacy single-step categories that produce no shortlist.
 */
object DigestMessagesTable : Table("digest_messages") {
    val chatId = long("chat_id")
    val messageId = long("message_id")
    val category = varchar("category", 100).index()
    val eventKey = varchar("event_key", 3000).nullable()
    val articleLinks = text("article_links").default("")   // newline-separated article links in this message
    val sentAt = datetime("sent_at").index()

    override val primaryKey = PrimaryKey(chatId, messageId)

    init {
        // Reply threading looks up "the last post about this event" by (category, event_key).
        index("idx_digest_messages_cat_event", false, category, eventKey)
    }
}

/**
 * Current aggregated reaction counts per `(chat_id, message_id, emoji)`, written by
 * [metifikys.telegram.TelegramUpdatesPoller] from `message_reaction_count` updates. Each
 * update carries the message's complete reaction set, so writes are replace-all for the
 * message, not increments. Custom emoji are stored as `custom:<id>`, paid reactions as `paid`.
 */
object ReactionCountsTable : Table("reaction_counts") {
    val chatId = long("chat_id")
    val messageId = long("message_id")
    val emoji = varchar("emoji", 64)
    val count = integer("count")
    val updatedAt = datetime("updated_at").index()

    override val primaryKey = PrimaryKey(chatId, messageId, emoji)
}

/** One delivered digest message, linking a Telegram `(chatId, messageId)` to what it rendered. */
data class DigestMessageRow(
    val chatId: Long,
    val messageId: Long,
    val category: String,
    val eventKey: String?,
    val articleLinks: String,
    val sentAt: LocalDateTime
)

/** A single emoji's current aggregated count on a message. */
data class ReactionCount(val emoji: String, val count: Int)

/**
 * Per-category reaction rollup produced by [NewsDatabase.fetchReactionSummary] over a time
 * window: [totalReactions] summed across every emoji, [postCount] distinct messages carrying
 * any reaction, and [byEmoji] the per-emoji totals sorted highest-first for a "top" line.
 */
data class CategoryReactionSummary(
    val category: String,
    val totalReactions: Long,
    val postCount: Long,
    val byEmoji: List<ReactionCount>
)
