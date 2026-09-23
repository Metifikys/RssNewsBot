package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * `factCheck` verdicts: one row per judged post (see `metifikys.digest.FactChecker`). Log-only —
 * read by `/status` and for calibrating the judge by hand; pruned after `factCheck.retentionDays`.
 */
object FactChecksTable : Table("fact_checks") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100).index()
    val chatId = long("chat_id")
    val messageId = long("message_id")
    val eventKey = varchar("event_key", 300).nullable()
    /** The post as the channel got it, without the footer (badges, hashtags). */
    val postText = text("post_text")
    /** How much source text the judge saw (the render's own sources). */
    val sourceChars = integer("source_chars")
    /** `supported` / `minor` / `unsupported`. */
    val verdict = varchar("verdict", 20)
    /** The judge's `claims` array as JSON (`claim`, `status`, `note`). */
    val claimsJson = text("claims_json")
    val judgeProvider = varchar("judge_provider", 50)
    val judgeModel = varchar("judge_model", 200)
    val checkedAt = datetime("checked_at").index()

    override val primaryKey = PrimaryKey(id)
}

data class FactCheckRow(
    val category: String,
    val chatId: Long,
    val messageId: Long,
    val eventKey: String?,
    val postText: String,
    val sourceChars: Int,
    val verdict: String,
    val claimsJson: String,
    val judgeProvider: String,
    val judgeModel: String,
    val checkedAt: LocalDateTime
) {
    companion object {
        const val SUPPORTED = "supported"
        const val MINOR = "minor"
        const val UNSUPPORTED = "unsupported"
        val VERDICTS = setOf(SUPPORTED, MINOR, UNSUPPORTED)
    }
}

interface FactCheckStore {
    fun insertFactCheck(row: FactCheckRow)

    /** Checks made at or after [since], oldest first. */
    fun fetchFactChecks(since: LocalDateTime): List<FactCheckRow>

    fun deleteOldFactChecks(days: Long)
}

internal class FactCheckRepository : FactCheckStore {

    override fun insertFactCheck(row: FactCheckRow) {
        transaction {
            FactChecksTable.insert {
                it[category] = row.category
                it[chatId] = row.chatId
                it[messageId] = row.messageId
                it[eventKey] = row.eventKey
                it[postText] = row.postText
                it[sourceChars] = row.sourceChars
                it[verdict] = row.verdict
                it[claimsJson] = row.claimsJson
                it[judgeProvider] = row.judgeProvider
                it[judgeModel] = row.judgeModel
                it[checkedAt] = row.checkedAt
            }
        }
    }

    override fun fetchFactChecks(since: LocalDateTime): List<FactCheckRow> = transaction {
        FactChecksTable.selectAll()
            .where { FactChecksTable.checkedAt greaterEq since }
            .orderBy(FactChecksTable.checkedAt to SortOrder.ASC)
            .map { r ->
                FactCheckRow(
                    category = r[FactChecksTable.category],
                    chatId = r[FactChecksTable.chatId],
                    messageId = r[FactChecksTable.messageId],
                    eventKey = r[FactChecksTable.eventKey],
                    postText = r[FactChecksTable.postText],
                    sourceChars = r[FactChecksTable.sourceChars],
                    verdict = r[FactChecksTable.verdict],
                    claimsJson = r[FactChecksTable.claimsJson],
                    judgeProvider = r[FactChecksTable.judgeProvider],
                    judgeModel = r[FactChecksTable.judgeModel],
                    checkedAt = r[FactChecksTable.checkedAt]
                )
            }
    }

    override fun deleteOldFactChecks(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            FactChecksTable.deleteWhere { checkedAt less cutoff }
        }
    }
}
