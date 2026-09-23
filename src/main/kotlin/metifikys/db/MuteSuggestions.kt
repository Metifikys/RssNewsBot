package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Mute proposals posted to the admin chat ([metifikys.feedback.MuteSuggester]) and what the admin
 * decided. `status` is `pending` → `approved` | `rejected`; a rejected keyword is not proposed
 * again for 90 days, a pending or approved one never. `reason` is the rendered evidence line
 * (affinity or rejection counts), kept so the message can be re-rendered after the decision.
 */
object MuteSuggestionsTable : Table("mute_suggestions") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100)
    val keyword = varchar("keyword", 200)
    /** `affinity` (disliked) or `rejected` (never gets through Step 1). */
    val kind = varchar("source", 20)
    val score = double("score").nullable()
    val reason = text("reason").default("")
    val hits30d = integer("hits30d").default(0)
    val published30d = integer("published30d").default(0)
    val status = varchar("status", 20).default(STATUS_PENDING)
    val createdAt = datetime("created_at")
    val decidedAt = datetime("decided_at").nullable()
    val chatId = long("chat_id").nullable()
    val messageId = long("message_id").nullable()

    override val primaryKey = PrimaryKey(id)

    const val STATUS_PENDING = "pending"
    const val STATUS_APPROVED = "approved"
    const val STATUS_REJECTED = "rejected"
}

data class MuteSuggestionRow(
    val id: Int = 0,
    val category: String,
    val keyword: String,
    val kind: String,
    val score: Double?,
    val reason: String,
    val hits30d: Int,
    val published30d: Int,
    val status: String = MuteSuggestionsTable.STATUS_PENDING,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val decidedAt: LocalDateTime? = null,
    val chatId: Long? = null,
    val messageId: Long? = null
)

interface MuteSuggestionStore {
    /** Stores a new pending proposal and returns its id. */
    fun insertMuteSuggestion(row: MuteSuggestionRow): Int

    /** Remembers which admin-chat message carries the proposal's buttons. */
    fun setMuteSuggestionMessage(id: Int, chatId: Long, messageId: Long)

    fun fetchMuteSuggestion(id: Int): MuteSuggestionRow?

    /** Every proposal ever made (a few per week), for the "don't propose again" check. */
    fun fetchMuteSuggestions(): List<MuteSuggestionRow>

    /** Moves a PENDING proposal to [status]; false when it was already decided (double press). */
    fun decideMuteSuggestion(id: Int, status: String): Boolean
}

internal class MuteSuggestionRepository : MuteSuggestionStore {

    override fun insertMuteSuggestion(row: MuteSuggestionRow): Int = transaction {
        MuteSuggestionsTable.insert {
            it[category] = row.category
            it[keyword] = row.keyword
            it[kind] = row.kind
            it[score] = row.score
            it[reason] = row.reason
            it[hits30d] = row.hits30d
            it[published30d] = row.published30d
            it[status] = row.status
            it[createdAt] = row.createdAt
        } get MuteSuggestionsTable.id
    }

    override fun setMuteSuggestionMessage(id: Int, chatId: Long, messageId: Long) {
        transaction {
            MuteSuggestionsTable.update({ MuteSuggestionsTable.id eq id }) {
                it[MuteSuggestionsTable.chatId] = chatId
                it[MuteSuggestionsTable.messageId] = messageId
            }
        }
    }

    override fun fetchMuteSuggestion(id: Int): MuteSuggestionRow? = transaction {
        MuteSuggestionsTable.selectAll().where { MuteSuggestionsTable.id eq id }.map { it.toRow() }.firstOrNull()
    }

    override fun fetchMuteSuggestions(): List<MuteSuggestionRow> = transaction {
        MuteSuggestionsTable.selectAll().orderBy(MuteSuggestionsTable.id).map { it.toRow() }
    }

    override fun decideMuteSuggestion(id: Int, status: String): Boolean = transaction {
        MuteSuggestionsTable.update({
            (MuteSuggestionsTable.id eq id) and (MuteSuggestionsTable.status eq MuteSuggestionsTable.STATUS_PENDING)
        }) {
            it[MuteSuggestionsTable.status] = status
            it[decidedAt] = LocalDateTime.now()
        } > 0
    }

    private fun ResultRow.toRow() = MuteSuggestionRow(
        id = this[MuteSuggestionsTable.id],
        category = this[MuteSuggestionsTable.category],
        keyword = this[MuteSuggestionsTable.keyword],
        kind = this[MuteSuggestionsTable.kind],
        score = this[MuteSuggestionsTable.score],
        reason = this[MuteSuggestionsTable.reason],
        hits30d = this[MuteSuggestionsTable.hits30d],
        published30d = this[MuteSuggestionsTable.published30d],
        status = this[MuteSuggestionsTable.status],
        createdAt = this[MuteSuggestionsTable.createdAt],
        decidedAt = this[MuteSuggestionsTable.decidedAt],
        chatId = this[MuteSuggestionsTable.chatId],
        messageId = this[MuteSuggestionsTable.messageId]
    )
}
