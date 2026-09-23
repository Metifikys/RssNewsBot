package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
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
