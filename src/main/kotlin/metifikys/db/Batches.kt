package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
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

interface BatchStore {
    /** Persists a newly submitted batch job as "pending". */
    fun savePendingBatch(
        batchId: String,
        chunkIndex: Int,
        totalChunks: Int,
        categoryNames: String = "",
        articleLinks: String = "",
        shortlistJson: String? = null,
        kind: String = "render"
    )

    /** Marks a batch job as completed or failed. */
    fun updateBatchStatus(batchId: String, status: String)

    /** Counts pending batches for a given category name. */
    fun countPendingBatchesForCategory(category: String): Int

    /** Returns all batch jobs that are still in "pending" state. */
    fun fetchPendingBatches(): List<PendingBatch>

    /**
     * Returns currently pending batches grouped by category name. A batch with multiple
     * comma-separated category names appears under each one.
     */
    fun fetchPendingBatchesByCategory(): Map<String, List<PendingBatch>>

    /** Deletes batch records older than [days] days (completed or failed). */
    fun deleteOldBatches(days: Long = 2)
}

internal class BatchRepository : BatchStore {

    override fun savePendingBatch(
        batchId: String,
        chunkIndex: Int,
        totalChunks: Int,
        categoryNames: String,
        articleLinks: String,
        shortlistJson: String?,
        kind: String
    ) {
        transaction {
            PendingBatchesTable.insertIgnore {
                it[PendingBatchesTable.batchId] = batchId
                it[PendingBatchesTable.chunkIndex] = chunkIndex
                it[PendingBatchesTable.totalChunks] = totalChunks
                it[PendingBatchesTable.categoryNames] = categoryNames
                it[PendingBatchesTable.articleLinks] = articleLinks
                it[PendingBatchesTable.shortlistJson] = shortlistJson
                it[PendingBatchesTable.kind] = kind
                it[PendingBatchesTable.createdAt] = LocalDateTime.now()
                it[PendingBatchesTable.status] = "pending"
            }
        }
    }

    override fun updateBatchStatus(batchId: String, status: String) {
        transaction {
            PendingBatchesTable.update({ PendingBatchesTable.batchId eq batchId }) {
                it[PendingBatchesTable.status] = status
            }
        }
    }

    override fun countPendingBatchesForCategory(category: String): Int = transaction {
        // BUG-019: categoryNames stores one category today, but the (currently unused) multi-category
        // batch path would store a comma-joined list. Match `category` as a full list element —
        // exact, first, last, or middle — so re-enabling multi-category batches doesn't silently
        // break this count. (Category names are config identifiers, so LIKE wildcards aren't a concern.)
        PendingBatchesTable
            .selectAll()
            .where {
                (PendingBatchesTable.status eq "pending") and (
                    (PendingBatchesTable.categoryNames eq category) or
                        (PendingBatchesTable.categoryNames like "$category,%") or
                        (PendingBatchesTable.categoryNames like "%,$category") or
                        (PendingBatchesTable.categoryNames like "%,$category,%")
                    )
            }
            .count().toInt()
    }

    override fun fetchPendingBatches(): List<PendingBatch> = transaction {
        PendingBatchesTable
            .selectAll()
            .where { PendingBatchesTable.status eq "pending" }
            .orderBy(PendingBatchesTable.createdAt)
            .map {
                PendingBatch(
                    batchId = it[PendingBatchesTable.batchId],
                    chunkIndex = it[PendingBatchesTable.chunkIndex],
                    totalChunks = it[PendingBatchesTable.totalChunks],
                    categoryNames = it[PendingBatchesTable.categoryNames],
                    articleLinks = it[PendingBatchesTable.articleLinks],
                    shortlistJson = it[PendingBatchesTable.shortlistJson],
                    kind = it[PendingBatchesTable.kind],
                    createdAt = it[PendingBatchesTable.createdAt],
                    status = it[PendingBatchesTable.status]
                )
            }
    }

    override fun fetchPendingBatchesByCategory(): Map<String, List<PendingBatch>> {
        val pending = fetchPendingBatches()
        val grouped = mutableMapOf<String, MutableList<PendingBatch>>()
        for (batch in pending) {
            val names = batch.categoryNames
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            for (name in names) {
                grouped.getOrPut(name) { mutableListOf() }.add(batch)
            }
        }
        return grouped
    }

    override fun deleteOldBatches(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            PendingBatchesTable.deleteWhere {
                (PendingBatchesTable.createdAt less cutoff) and
                    (PendingBatchesTable.status neq "pending")
            }
        }
    }
}
