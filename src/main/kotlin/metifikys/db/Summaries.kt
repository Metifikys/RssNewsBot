package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

object SummariesTable : Table("summaries") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100).index()
    val summary = text("summary")
    val createdAt = datetime("created_at")
    /** Number of articles/topics actually sent in this digest. 0 for rows written before the column existed. */
    val articleCount = integer("article_count").default(0)

    override val primaryKey = PrimaryKey(id)
}

data class SummaryRecord(
    val category: String,
    val summary: String,
    val createdAt: LocalDateTime,
    val articleCount: Int = 0
)

interface SummaryStore {
    /** Persists a delivered summary for future deduplication context. */
    fun saveSummary(category: String, summary: String, articleCount: Int = 0)

    /** Returns the most recent [limit] summaries for [category], newest-first. */
    fun fetchRecentSummaries(category: String, limit: Int): List<SummaryRecord>

    /** Returns the most recent summary for [category], or null if none exist. */
    fun fetchLatestSummary(category: String): SummaryRecord?

    /** Deletes summaries older than [days] days. */
    fun deleteOldSummaries(days: Long = 14)
}

internal class SummaryRepository : SummaryStore {

    override fun saveSummary(category: String, summary: String, articleCount: Int) {
        transaction {
            SummariesTable.insert {
                it[SummariesTable.category] = category
                it[SummariesTable.summary] = summary
                it[SummariesTable.createdAt] = LocalDateTime.now()
                it[SummariesTable.articleCount] = articleCount
            }
        }
    }

    override fun fetchRecentSummaries(category: String, limit: Int): List<SummaryRecord> = transaction {
        SummariesTable
            .selectAll()
            .where { SummariesTable.category eq category }
            .orderBy(SummariesTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map {
                SummaryRecord(
                    category = it[SummariesTable.category],
                    summary = it[SummariesTable.summary],
                    createdAt = it[SummariesTable.createdAt],
                    articleCount = it[SummariesTable.articleCount]
                )
            }
    }

    override fun fetchLatestSummary(category: String): SummaryRecord? =
        fetchRecentSummaries(category, 1).firstOrNull()

    override fun deleteOldSummaries(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            SummariesTable.deleteWhere { createdAt less cutoff }
        }
    }
}
