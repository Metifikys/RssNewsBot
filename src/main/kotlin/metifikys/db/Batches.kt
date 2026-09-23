package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * Persists in-flight OpenAI batch jobs so they can be resumed after a restart.
 *
 * status values:
 *   "pending"   — submitted to OpenAI, not yet completed
 *   "completed" — results retrieved and digests sent
 *   "failed"    — terminal error; will not be retried
 */
object PendingBatchesTable : Table("pending_batches") {
    val id = integer("id").autoIncrement()
    val batchId = varchar("batch_id", 200).uniqueIndex()
    val chunkIndex = integer("chunk_index")   // 0-based chunk number within a cycle
    val totalChunks = integer("total_chunks")
    val categoryNames = varchar("category_names", 2000).default("")  // comma-separated category names in this batch
    val articleLinks = text("article_links").default("")              // newline-separated article links for this batch
    val shortlistJson = text("shortlist_json").nullable()             // serialized Step 1 shortlist for render mode; null = legacy flow
    val kind = varchar("kind", 20).default("render")                  // "render" | "extract"
    val createdAt = datetime("created_at")
    val status = varchar("status", 20).default("pending")

    override val primaryKey = PrimaryKey(id)
}

data class PendingBatch(
    val batchId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val categoryNames: String = "",
    val articleLinks: String = "",
    val shortlistJson: String? = null,
    val kind: String = "render",
    val createdAt: LocalDateTime,
    val status: String
)
