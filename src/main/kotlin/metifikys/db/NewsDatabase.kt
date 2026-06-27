package metifikys.db

import metifikys.model.Article
import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.math.ceil

object ArticlesTable : Table("articles") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100)
    val title = varchar("title", 1000)
    val link = varchar("link", 2000).uniqueIndex()
    val description = text("description")
    val pubDate = datetime("pub_date")
    val status = varchar("status", 20).default(ArticleStatus.UNPROCESSED.name).index()
    val processingStartedAt = datetime("processing_started_at").nullable().index()
    val imageUrl = text("image_url").nullable()
    val summary = text("summary").nullable()
    /**
     * Canonical article id when this row was marked [ArticleStatus.DUPLICATE] by the
     * semantic-dedup detector. Null otherwise. Soft pointer — no FK constraint, since
     * the canonical row may be pruned by `deleteOlderThan` while the duplicate row's
     * own `pubDate` is still inside the retention window.
     */
    val duplicateOf = integer("duplicate_of").nullable()

    override val primaryKey = PrimaryKey(id)
}

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

/**
 * Per-LLM-call observability log: token estimates and an estimated USD cost,
 * tagged with provider, model, category, and use-case. Append-only; aggregated
 * by `/status` (last-24h / last-7d totals) and pruned periodically.
 */
object LlmCallsTable : Table("llm_calls") {
    val id = integer("id").autoIncrement()
    val provider = varchar("provider", 50).index()
    val model = varchar("model", 200)
    val category = varchar("category", 100).nullable().index()
    val useCase = varchar("use_case", 50).nullable()
    val promptTokens = integer("prompt_tokens").default(0)
    val completionTokens = integer("completion_tokens").default(0)
    val estCostUsd = double("est_cost_usd").default(0.0)
    val ts = datetime("ts").index()

    /**
     * Wall-clock latency of the LLM call in milliseconds. Nullable: only the
     * synchronous code paths in [metifikys.ai.MeteredLlmClient] record it; batch
     * jobs leave it null (their wall-clock is poll-wait, not model latency).
     */
    val durationMs = long("duration_ms").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Per-article embedding vectors. One row per (article_id), keyed by the autoincrement id
 * of [ArticlesTable]. The vector is stored as little-endian Float32 bytes (`dim * 4`);
 * see [metifikys.digest.VectorMath] for encode/decode helpers.
 *
 * Used by the semantic-dedup detector. At current article volumes the detector scans
 * recent rows in-process — sqlite-vss / virtual tables are deferred.
 */
object ArticleEmbeddingsTable : Table("article_embeddings") {
    val articleId = integer("article_id").references(ArticlesTable.id).uniqueIndex()
    val model = varchar("model", 100)
    val dim = integer("dim")
    val vector = blob("vector")
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(articleId)
}

/**
 * Per-event embedding vectors, one row per distinct `(category, event_key)`. Written by the
 * log-only event-level analyzer ([metifikys.digest.EventSemanticAnalyzer]) for every event it
 * sees on a shortlist, and scanned next cycle as the "previously-covered" candidate set.
 *
 * Mirrors [ArticleEmbeddingsTable] but keyed by `event_key` instead of an article id, so the
 * event-level pass operates on Step 1's structured output rather than raw articles. Vector is
 * little-endian Float32 bytes (`dim * 4`); see [metifikys.digest.VectorMath].
 */
object EventEmbeddingsTable : Table("event_embeddings") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100).index()
    val eventKey = varchar("event_key", 3000)
    /** Step 1's short event label, kept so the analyzer can log subject-match flags. */
    val subject = varchar("subject", 500).default("")
    /** Step 1's franchise label, kept so the analyzer can log franchise-match flags. */
    val franchise = varchar("franchise", 200).default("")
    val model = varchar("model", 100)
    val dim = integer("dim")
    val vector = blob("vector")
    val createdAt = datetime("created_at").index()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_event_embeddings_cat_key", category, eventKey)
    }
}

object SummariesTable : Table("summaries") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100).index()
    val summary = text("summary")
    val createdAt = datetime("created_at")
    /** Number of articles/topics actually sent in this digest. 0 for rows written before the column existed. */
    val articleCount = integer("article_count").default(0)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Structured dedup memory: one row per distinct (category, event_key).
 * Upserted after a shortlist is successfully delivered to Telegram.
 * Queried each cycle to build `PREVIOUSLY_COVERED_EVENTS_JSON` for Step 1.
 */
object CoveredEventsTable : Table("covered_events") {
    val id = integer("id").autoIncrement()
    val category = varchar("category", 100).index()
    val eventKey = varchar("event_key", 3000)
    val subject = varchar("subject", 500).default("")
    val franchise = varchar("franchise", 200).default("")
    val eventType = varchar("event_type", 100).default("")
    val coreFact = text("core_fact")
    val importance = integer("importance").default(0)
    /** Raw news weight 0–10 emitted by Step 1. 0 for rows written before the split. */
    val newsworthiness = integer("newsworthiness").default(0)
    /** Editorial-fit weight 0–10 emitted by Step 1. 0 for rows written before the split. */
    val digestFit = integer("digest_fit").default(0)
    val url = varchar("url", 2000).default("")
    val coveredAt = datetime("covered_at").index()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_covered_events_cat_key", category, eventKey)
    }
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

data class SummaryRecord(
    val category: String,
    val summary: String,
    val createdAt: LocalDateTime,
    val articleCount: Int = 0
)

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

/**
 * Aggregate row produced by [NewsDatabase.fetchLlmCallStats]: one entry per
 * (provider, category) bucket within the requested time window.
 */
data class LlmCostStat(
    val provider: String,
    val category: String?,
    val totalCostUsd: Double,
    val promptTokens: Long,
    val completionTokens: Long,
    val callCount: Long
)

/**
 * Per-provider latency aggregate produced by [NewsDatabase.fetchProviderLatency]
 * over the rows that recorded a `duration_ms` (synchronous calls only). [p95Ms]
 * is a nearest-rank percentile computed in-process.
 */
data class ProviderLatency(
    val provider: String,
    val callCount: Long,
    val avgMs: Double,
    val p95Ms: Long
)

/**
 * Per-category article outcome counts produced by [NewsDatabase.fetchArticleStatusCounts]
 * within a time window (windowed by `pubDate`): [articles] = rows in status PROCESSED,
 * [blocked] = rows the semantic/event dedup filter hard-rejected (status DUPLICATE).
 *
 * NOTE: [articles] counts articles by RSS `pubDate`, not by when they reached the channel.
 * For the actual publish-event count see [NewsDatabase.fetchPublishedTopicCounts].
 */
data class CategoryArticleCounts(
    val category: String,
    val articles: Long,
    val blocked: Long
)

/**
 * One row from [ArticleEmbeddingsTable] joined with the article's category, link, title,
 * pubDate, and status. Returned by [NewsDatabase.fetchRecentEmbeddings] for the
 * semantic-dedup detector's brute-force cosine scan. [status] is the [ArticleStatus]
 * name string; the detector needs it to decide whether a near-duplicate against a
 * neighbour is safe to hard-reject (only true when the neighbour is already PROCESSED).
 */
data class EmbeddingRow(
    val articleId: Int,
    val link: String,
    val title: String,
    val category: String,
    val model: String,
    val vector: ByteArray,
    val pubDate: LocalDateTime,
    val status: String
)

/**
 * One row from [EventEmbeddingsTable]. Returned by [NewsDatabase.fetchRecentEventEmbeddings]
 * as the candidate set for the log-only event-level analyzer's cosine scan. Unlike
 * [EmbeddingRow] there is no status to gate on — the event-level pass is analyze-only and
 * never rejects, so it only needs the key, model, vector, and recency.
 */
data class EventEmbeddingRow(
    val category: String,
    val eventKey: String,
    val subject: String,
    val franchise: String,
    val model: String,
    val vector: ByteArray,
    val createdAt: LocalDateTime
)

data class CoveredEventRow(
    val category: String,
    val eventKey: String,
    val subject: String,
    val franchise: String,
    val eventType: String,
    val coreFact: String,
    val importance: Int,
    val newsworthiness: Int = 0,
    val digestFit: Int = 0,
    val url: String,
    val coveredAt: LocalDateTime
)

class NewsDatabase(dbPath: String) {

    init {
        // Security: reject path traversal and resolve to a canonical absolute path
        require(dbPath.isNotBlank()) { "Database path must not be blank" }
        require(!dbPath.contains("..")) { "Database path must not contain '..' (path traversal): $dbPath" }
        val canonicalPath = java.io.File(dbPath).canonicalPath

        // busy_timeout makes a connection that hits a held write lock wait-and-retry (up to 5s)
        // instead of failing fast with SQLITE_BUSY. xerial applies this per-connection, so it
        // covers every Exposed `transaction { }` (each opens its own connection) — important now
        // that categories are processed concurrently and several may write in the same window.
        val jdbcUrl = "jdbc:sqlite:$canonicalPath?busy_timeout=5000"
        Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                ArticlesTable, PendingBatchesTable, SummariesTable, CoveredEventsTable, RejectedEventsTable,
                LlmCallsTable, ArticleEmbeddingsTable, EventEmbeddingsTable
            )
        }
        migrateArticleStatuses(jdbcUrl)
    }

    private fun migrateArticleStatuses(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                val hasProcessedColumn = statement.executeQuery("PRAGMA table_info(articles)").use { rs ->
                    var exists = false
                    while (rs.next()) {
                        if (rs.getString("name").equals("processed", ignoreCase = true)) {
                            exists = true
                            break
                        }
                    }
                    exists
                }

                if (hasProcessedColumn) {
                    statement.executeUpdate(
                        """
                        UPDATE articles
                        SET status = '${ArticleStatus.PROCESSED.name}'
                        WHERE processed = 1
                          AND status = '${ArticleStatus.UNPROCESSED.name}'
                        """.trimIndent()
                    )
                }

                statement.executeUpdate(
                    """
                    UPDATE articles
                    SET processing_started_at = NULL
                    WHERE status != '${ArticleStatus.PROCESSING.name}'
                    """.trimIndent()
                )

                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_articles_status ON articles(status)")
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_articles_processing_started_at ON articles(processing_started_at)"
                )
            }
        }
    }

    /**
     * Returns the subset of [links] that already exist in the articles table.
     * Used to skip enrichment for articles we already have.
     */
    fun findExistingLinks(links: List<String>): Set<String> {
        if (links.isEmpty()) return emptySet()
        return transaction {
            ArticlesTable
                .select(ArticlesTable.link)
                .where { ArticlesTable.link inList links }
                .map { it[ArticlesTable.link] }
                .toSet()
        }
    }

    fun insertArticles(articles: List<Article>): Int {
        var inserted = 0
        transaction {
            for (article in articles) {
                val result = ArticlesTable.insertIgnore {
                    it[category] = article.category
                    it[title] = article.title
                    it[link] = article.link
                    it[description] = article.description
                    it[pubDate] = article.pubDate
                    it[status] = ArticleStatus.UNPROCESSED.name
                    it[processingStartedAt] = null
                    it[imageUrl] = article.imageUrl
                    it[summary] = article.summary
                }
                if (result.insertedCount > 0) inserted++
            }
        }
        return inserted
    }

    fun fetchReadyForDigestByCategory(staleTimeoutHours: Long = 3): Map<String, List<Article>> {
        val cutoff = LocalDateTime.now().minusHours(staleTimeoutHours)
        return transaction {
            ArticlesTable
                .selectAll()
                .where {
                    (ArticlesTable.status eq ArticleStatus.UNPROCESSED.name) or
                        (
                            (ArticlesTable.status eq ArticleStatus.PROCESSING.name) and
                                (ArticlesTable.processingStartedAt.isNull() or (ArticlesTable.processingStartedAt less cutoff))
                            )
                }
                .map {
                    Article(
                        category = it[ArticlesTable.category],
                        title = it[ArticlesTable.title],
                        link = it[ArticlesTable.link],
                        description = it[ArticlesTable.description],
                        pubDate = it[ArticlesTable.pubDate],
                        imageUrl = it[ArticlesTable.imageUrl],
                        summary = it[ArticlesTable.summary]
                    )
                }
                .groupBy { it.category }
        }
    }

    fun fetchProcessingByCategory(category: String): List<Article> {
        return transaction {
            ArticlesTable
                .selectAll()
                .where {
                    (ArticlesTable.category eq category) and
                        (ArticlesTable.status eq ArticleStatus.PROCESSING.name)
                }
                .map {
                    Article(
                        category = it[ArticlesTable.category],
                        title = it[ArticlesTable.title],
                        link = it[ArticlesTable.link],
                        description = it[ArticlesTable.description],
                        pubDate = it[ArticlesTable.pubDate],
                        imageUrl = it[ArticlesTable.imageUrl],
                        summary = it[ArticlesTable.summary]
                    )
                }
        }
    }

    fun fetchArticlesByLinks(links: List<String>): List<Article> {
        if (links.isEmpty()) return emptyList()
        return transaction {
            ArticlesTable.selectAll()
                .where { ArticlesTable.link inList links }
                .map {
                    Article(
                        category = it[ArticlesTable.category],
                        title = it[ArticlesTable.title],
                        link = it[ArticlesTable.link],
                        description = it[ArticlesTable.description],
                        pubDate = it[ArticlesTable.pubDate],
                        imageUrl = it[ArticlesTable.imageUrl],
                        summary = it[ArticlesTable.summary]
                    )
                }
        }
    }

    /**
     * Returns up to [limit] articles in [category] whose `pubDate` is within [sinceDays] days,
     * newest-first, across ALL statuses (DUPLICATE rows included — a near-duplicate the
     * semantic-dedup detector blocked is itself a "this story repeated" signal). Used by the
     * weekly roundup's article-level fallback for categories with no covered events.
     */
    fun fetchRecentArticles(category: String, sinceDays: Long, limit: Int): List<Article> {
        if (limit <= 0) return emptyList()
        val cutoff = LocalDateTime.now().minusDays(sinceDays)
        return transaction {
            ArticlesTable.selectAll()
                .where {
                    (ArticlesTable.category eq category) and
                        (ArticlesTable.pubDate greaterEq cutoff)
                }
                .orderBy(ArticlesTable.pubDate, SortOrder.DESC)
                .limit(limit)
                .map {
                    Article(
                        category = it[ArticlesTable.category],
                        title = it[ArticlesTable.title],
                        link = it[ArticlesTable.link],
                        description = it[ArticlesTable.description],
                        pubDate = it[ArticlesTable.pubDate],
                        imageUrl = it[ArticlesTable.imageUrl],
                        summary = it[ArticlesTable.summary]
                    )
                }
        }
    }

    fun markProcessed(links: List<String>) {
        if (links.isEmpty()) return
        transaction {
            ArticlesTable.update({ ArticlesTable.link inList links }) {
                it[status] = ArticleStatus.PROCESSED.name
                it[processingStartedAt] = null
            }
        }
    }

    fun markProcessing(links: List<String>) {
        if (links.isEmpty()) return
        transaction {
            ArticlesTable.update({
                (ArticlesTable.link inList links) and
                    (ArticlesTable.status neq ArticleStatus.PROCESSED.name)
            }) {
                it[status] = ArticleStatus.PROCESSING.name
                it[processingStartedAt] = LocalDateTime.now()
            }
        }
    }

    fun markUnprocessed(links: List<String>) {
        if (links.isEmpty()) return
        transaction {
            ArticlesTable.update({
                (ArticlesTable.link inList links) and
                    (ArticlesTable.status neq ArticleStatus.PROCESSED.name)
            }) {
                it[status] = ArticleStatus.UNPROCESSED.name
                it[processingStartedAt] = null
            }
        }
    }

    fun deleteOlderThan(days: Long = 7) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            ArticlesTable.deleteWhere { pubDate less cutoff }
        }
    }

    // ── Batch persistence ─────────────────────────────────────────────────────

    /** Persists a newly submitted batch job as "pending". */
    fun savePendingBatch(
        batchId: String,
        chunkIndex: Int,
        totalChunks: Int,
        categoryNames: String = "",
        articleLinks: String = "",
        shortlistJson: String? = null,
        kind: String = "render"
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

    /** Marks a batch job as completed or failed. */
    fun updateBatchStatus(batchId: String, status: String) {
        transaction {
            PendingBatchesTable.update({ PendingBatchesTable.batchId eq batchId }) {
                it[PendingBatchesTable.status] = status
            }
        }
    }

    /** Counts pending batches for a given category name. */
    fun countPendingBatchesForCategory(category: String): Int = transaction {
        PendingBatchesTable
            .selectAll()
            .where {
                (PendingBatchesTable.status eq "pending") and
                    (PendingBatchesTable.categoryNames eq category)
            }
            .count().toInt()
    }

    /** Returns all batch jobs that are still in "pending" state. */
    fun fetchPendingBatches(): List<PendingBatch> = transaction {
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

    /**
     * Returns currently pending batches grouped by category name. A batch with multiple
     * comma-separated category names appears under each one.
     */
    fun fetchPendingBatchesByCategory(): Map<String, List<PendingBatch>> {
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

    /** Deletes batch records older than [days] days (completed or failed). */
    fun deleteOldBatches(days: Long = 2) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            PendingBatchesTable.deleteWhere {
                (PendingBatchesTable.createdAt less cutoff) and
                    (PendingBatchesTable.status neq "pending")
            }
        }
    }

    // ── Summary history ───────────────────────────────────────────────────────

    /** Persists a delivered summary for future deduplication context. */
    fun saveSummary(category: String, summary: String, articleCount: Int = 0) {
        transaction {
            SummariesTable.insert {
                it[SummariesTable.category] = category
                it[SummariesTable.summary] = summary
                it[SummariesTable.createdAt] = LocalDateTime.now()
                it[SummariesTable.articleCount] = articleCount
            }
        }
    }

    /** Returns the most recent [limit] summaries for [category], newest-first. */
    fun fetchRecentSummaries(category: String, limit: Int): List<SummaryRecord> = transaction {
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

    /** Returns the most recent summary for [category], or null if none exist. */
    fun fetchLatestSummary(category: String): SummaryRecord? =
        fetchRecentSummaries(category, 1).firstOrNull()

    /** Deletes summaries older than [days] days. */
    fun deleteOldSummaries(days: Long = 14) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            SummariesTable.deleteWhere { createdAt less cutoff }
        }
    }

    // ── Covered events (Step 1 dedup memory) ─────────────────────────────────

    /**
     * Upserts covered events. On conflict against (category, event_key) the existing row's
     * `core_fact`, `importance`, `url`, and `covered_at` are refreshed; the key columns are
     * preserved. Static descriptive fields (`subject`, `franchise`, `event_type`) are NOT
     * refreshed — they are expected to be stable for a given event_key, and dropping updates
     * avoids occasional LLM drift destabilizing the row identity.
     */
    fun insertCoveredEvents(events: List<CoveredEventRow>) {
        if (events.isEmpty()) return
        transaction {
            for (event in events) {
                val updatedRows = CoveredEventsTable.update(
                    {
                        (CoveredEventsTable.category eq event.category) and
                            (CoveredEventsTable.eventKey eq event.eventKey)
                    }
                ) {
                    it[coreFact] = event.coreFact
                    it[importance] = event.importance
                    it[newsworthiness] = event.newsworthiness
                    it[digestFit] = event.digestFit
                    it[url] = event.url
                    it[coveredAt] = event.coveredAt
                }
                if (updatedRows == 0) {
                    CoveredEventsTable.insertIgnore {
                        it[category] = event.category
                        it[eventKey] = event.eventKey
                        it[subject] = event.subject
                        it[franchise] = event.franchise
                        it[eventType] = event.eventType
                        it[coreFact] = event.coreFact
                        it[importance] = event.importance
                        it[newsworthiness] = event.newsworthiness
                        it[digestFit] = event.digestFit
                        it[url] = event.url
                        it[coveredAt] = event.coveredAt
                    }
                }
            }
        }
    }

    /** Returns up to [limit] covered events for [category] within [sinceDays], newest-first. */
    fun fetchRecentEvents(category: String, sinceDays: Long, limit: Int): List<CoveredEventRow> {
        if (limit <= 0) return emptyList()
        val cutoff = LocalDateTime.now().minusDays(sinceDays)
        return transaction {
            CoveredEventsTable
                .selectAll()
                .where {
                    (CoveredEventsTable.category eq category) and
                        (CoveredEventsTable.coveredAt greaterEq cutoff)
                }
                .orderBy(CoveredEventsTable.coveredAt, SortOrder.DESC)
                .limit(limit)
                .map {
                    CoveredEventRow(
                        category = it[CoveredEventsTable.category],
                        eventKey = it[CoveredEventsTable.eventKey],
                        subject = it[CoveredEventsTable.subject],
                        franchise = it[CoveredEventsTable.franchise],
                        eventType = it[CoveredEventsTable.eventType],
                        coreFact = it[CoveredEventsTable.coreFact],
                        importance = it[CoveredEventsTable.importance],
                        newsworthiness = it[CoveredEventsTable.newsworthiness],
                        digestFit = it[CoveredEventsTable.digestFit],
                        url = it[CoveredEventsTable.url],
                        coveredAt = it[CoveredEventsTable.coveredAt]
                    )
                }
        }
    }

    /** Deletes covered events older than [retentionDays]. */
    fun pruneOldCoveredEvents(retentionDays: Long) {
        val cutoff = LocalDateTime.now().minusDays(retentionDays)
        transaction {
            CoveredEventsTable.deleteWhere { coveredAt less cutoff }
        }
    }

    // ── Rejected events (prompt-tuning log) ──────────────────────────────────

    /** Appends a batch of rejected/duplicate Step 1 extractions for prompt tuning. */
    fun saveRejectedEvents(events: List<RejectedEventRow>) {
        if (events.isEmpty()) return
        transaction {
            for (event in events) {
                RejectedEventsTable.insert {
                    it[category] = event.category
                    it[eventKey] = event.eventKey
                    it[subject] = event.subject
                    it[franchise] = event.franchise
                    it[eventType] = event.eventType
                    it[coreFact] = event.coreFact
                    it[importance] = event.importance
                    it[newsworthiness] = event.newsworthiness
                    it[digestFit] = event.digestFit
                    it[url] = event.url
                    it[status] = event.status
                    it[relatedPreviousEventKey] = event.relatedPreviousEventKey
                    it[articleIndex] = event.articleIndex
                    it[extractedAt] = event.extractedAt
                }
            }
        }
    }

    /**
     * Returns up to [limit] rejected-event rows for [category] within [sinceDays], newest-first,
     * whose status is in [statuses]. The weekly roundup ranker uses the `"duplicate"` rows as the
     * "this story showed up again" signal — each one is a later sighting of an already-covered
     * event, so counting them per story yields the "most duplicates / most repeated" measure.
     */
    fun fetchRecentRejectedEvents(
        category: String,
        sinceDays: Long,
        limit: Int,
        statuses: Set<String> = setOf("duplicate")
    ): List<RejectedEventRow> {
        if (limit <= 0 || statuses.isEmpty()) return emptyList()
        val cutoff = LocalDateTime.now().minusDays(sinceDays)
        return transaction {
            RejectedEventsTable
                .selectAll()
                .where {
                    (RejectedEventsTable.category eq category) and
                        (RejectedEventsTable.status inList statuses) and
                        (RejectedEventsTable.extractedAt greaterEq cutoff)
                }
                .orderBy(RejectedEventsTable.extractedAt, SortOrder.DESC)
                .limit(limit)
                .map {
                    RejectedEventRow(
                        category = it[RejectedEventsTable.category],
                        eventKey = it[RejectedEventsTable.eventKey],
                        subject = it[RejectedEventsTable.subject],
                        franchise = it[RejectedEventsTable.franchise],
                        eventType = it[RejectedEventsTable.eventType],
                        coreFact = it[RejectedEventsTable.coreFact],
                        importance = it[RejectedEventsTable.importance],
                        newsworthiness = it[RejectedEventsTable.newsworthiness],
                        digestFit = it[RejectedEventsTable.digestFit],
                        url = it[RejectedEventsTable.url],
                        status = it[RejectedEventsTable.status],
                        relatedPreviousEventKey = it[RejectedEventsTable.relatedPreviousEventKey],
                        articleIndex = it[RejectedEventsTable.articleIndex],
                        extractedAt = it[RejectedEventsTable.extractedAt]
                    )
                }
        }
    }

    /** Deletes rejected-event log entries older than [days] days. */
    fun deleteOldRejectedEvents(days: Long = 30) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            RejectedEventsTable.deleteWhere { extractedAt less cutoff }
        }
    }

    // ── LLM call metering ────────────────────────────────────────────────────

    fun insertLlmCall(
        provider: String,
        model: String,
        category: String?,
        useCase: String?,
        promptTokens: Int,
        completionTokens: Int,
        estCostUsd: Double,
        ts: LocalDateTime = LocalDateTime.now(),
        durationMs: Long? = null
    ) {
        transaction {
            LlmCallsTable.insert {
                it[LlmCallsTable.provider] = provider
                it[LlmCallsTable.model] = model
                it[LlmCallsTable.category] = category
                it[LlmCallsTable.useCase] = useCase
                it[LlmCallsTable.promptTokens] = promptTokens
                it[LlmCallsTable.completionTokens] = completionTokens
                it[LlmCallsTable.estCostUsd] = estCostUsd
                it[LlmCallsTable.ts] = ts
                it[LlmCallsTable.durationMs] = durationMs
            }
        }
    }

    /**
     * Aggregates LLM calls within the last [sinceHours] hours, grouped by (provider, category).
     * Empty when no rows fall in the window.
     */
    fun fetchLlmCallStats(sinceHours: Long): List<LlmCostStat> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val costSum = LlmCallsTable.estCostUsd.sum()
        val promptSum = LlmCallsTable.promptTokens.sum()
        val completionSum = LlmCallsTable.completionTokens.sum()
        val callCount = LlmCallsTable.id.count()
        return transaction {
            LlmCallsTable
                .select(
                    LlmCallsTable.provider, LlmCallsTable.category,
                    costSum, promptSum, completionSum, callCount
                )
                .where { LlmCallsTable.ts greaterEq cutoff }
                .groupBy(LlmCallsTable.provider, LlmCallsTable.category)
                .map {
                    LlmCostStat(
                        provider = it[LlmCallsTable.provider],
                        category = it[LlmCallsTable.category],
                        totalCostUsd = it[costSum] ?: 0.0,
                        promptTokens = (it[promptSum] ?: 0).toLong(),
                        completionTokens = (it[completionSum] ?: 0).toLong(),
                        callCount = it[callCount]
                    )
                }
        }
    }

    /**
     * Per-provider latency over the last [sinceHours] hours, computed from rows that
     * recorded a `duration_ms` (synchronous calls; batch jobs are excluded). Returns
     * avg and nearest-rank p95, sorted by call count descending. Empty when no timed
     * rows fall in the window.
     */
    fun fetchProviderLatency(sinceHours: Long): List<ProviderLatency> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val byProvider = transaction {
            LlmCallsTable
                .select(LlmCallsTable.provider, LlmCallsTable.durationMs)
                .where { LlmCallsTable.ts greaterEq cutoff }
                .mapNotNull { row ->
                    val d = row[LlmCallsTable.durationMs] ?: return@mapNotNull null
                    row[LlmCallsTable.provider] to d
                }
        }.groupBy({ it.first }, { it.second })

        return byProvider.map { (provider, durations) ->
            val sorted = durations.sorted()
            ProviderLatency(
                provider = provider,
                callCount = sorted.size.toLong(),
                avgMs = sorted.average(),
                p95Ms = nearestRankPercentile(sorted, 95)
            )
        }.sortedByDescending { it.callCount }
    }

    /** Nearest-rank percentile [p] (1..100) over an ascending-sorted list; 0 when empty. */
    private fun nearestRankPercentile(sortedAsc: List<Long>, p: Int): Long {
        if (sortedAsc.isEmpty()) return 0L
        val rank = ceil(p / 100.0 * sortedAsc.size).toInt().coerceIn(1, sortedAsc.size)
        return sortedAsc[rank - 1]
    }

    /**
     * Per-category article outcome counts over the last [sinceHours] hours, windowed by
     * `pubDate`. Each entry reports PROCESSED (published) and DUPLICATE (dedup-blocked)
     * counts. Categories with no in-window rows are absent from the map.
     */
    fun fetchArticleStatusCounts(sinceHours: Long): Map<String, CategoryArticleCounts> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val countExpr = ArticlesTable.id.count()
        val rows = transaction {
            ArticlesTable
                .select(ArticlesTable.category, ArticlesTable.status, countExpr)
                .where { ArticlesTable.pubDate greaterEq cutoff }
                .groupBy(ArticlesTable.category, ArticlesTable.status)
                .map { Triple(it[ArticlesTable.category], it[ArticlesTable.status], it[countExpr]) }
        }
        val byCategory = mutableMapOf<String, MutableMap<String, Long>>()
        for ((cat, status, cnt) in rows) {
            byCategory.getOrPut(cat) { mutableMapOf() }[status] = cnt
        }
        return byCategory.mapValues { (cat, statusCounts) ->
            CategoryArticleCounts(
                category = cat,
                articles = statusCounts[ArticleStatus.PROCESSED.name] ?: 0L,
                blocked = statusCounts[ArticleStatus.DUPLICATE.name] ?: 0L
            )
        }
    }

    /**
     * Per-category SUM of `articleCount` over digests actually published (windowed by
     * `SummariesTable.createdAt`) in the last [sinceHours]. Each topic in a digest is one
     * channel message, so this approximates the post count visible on the channel.
     * Categories with no in-window digests are absent from the map.
     */
    fun fetchPublishedTopicCounts(sinceHours: Long): Map<String, Long> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val sumExpr = SummariesTable.articleCount.sum()
        return transaction {
            SummariesTable
                .select(SummariesTable.category, sumExpr)
                .where { SummariesTable.createdAt greaterEq cutoff }
                .groupBy(SummariesTable.category)
                .mapNotNull { row ->
                    val s = row[sumExpr] ?: return@mapNotNull null
                    row[SummariesTable.category] to s.toLong()
                }
                .toMap()
        }
    }

    /** Deletes LLM-call records older than [days] days. */
    fun deleteOldLlmCalls(days: Long = 30) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            LlmCallsTable.deleteWhere { ts less cutoff }
        }
    }

    // ── Article embeddings (semantic-dedup detector) ─────────────────────────

    /**
     * Returns the autoincrement ids for [links], keyed by link. Missing links are
     * absent from the map. Used by the semantic-dedup detector to bind freshly-
     * inserted articles to the embeddings it generated for them.
     */
    fun fetchArticleIdsByLinks(links: List<String>): Map<String, Int> {
        if (links.isEmpty()) return emptyMap()
        return transaction {
            ArticlesTable
                .select(ArticlesTable.id, ArticlesTable.link)
                .where { ArticlesTable.link inList links }
                .associate { it[ArticlesTable.link] to it[ArticlesTable.id] }
        }
    }

    /**
     * Persists a single embedding. Idempotent: if a row already exists for [articleId]
     * it is replaced with the new model/dim/vector (the detector runs once per cycle
     * so this is rare, but it keeps re-runs deterministic).
     */
    fun saveEmbedding(articleId: Int, model: String, vector: ByteArray) {
        val dim = vector.size / 4
        transaction {
            val updated = ArticleEmbeddingsTable.update(
                { ArticleEmbeddingsTable.articleId eq articleId }
            ) {
                it[ArticleEmbeddingsTable.model] = model
                it[ArticleEmbeddingsTable.dim] = dim
                it[ArticleEmbeddingsTable.vector] = org.jetbrains.exposed.sql.statements.api.ExposedBlob(vector)
                it[createdAt] = LocalDateTime.now()
            }
            if (updated == 0) {
                ArticleEmbeddingsTable.insertIgnore {
                    it[ArticleEmbeddingsTable.articleId] = articleId
                    it[ArticleEmbeddingsTable.model] = model
                    it[ArticleEmbeddingsTable.dim] = dim
                    it[ArticleEmbeddingsTable.vector] = org.jetbrains.exposed.sql.statements.api.ExposedBlob(vector)
                    it[createdAt] = LocalDateTime.now()
                }
            }
        }
    }

    /**
     * Returns up to [limit] embeddings for articles in [category] whose `pubDate`
     * is within [sinceDays] days, newest-first. Used by the semantic-dedup detector
     * to scan candidate neighbours.
     */
    fun fetchRecentEmbeddings(category: String, sinceDays: Long, limit: Int): List<EmbeddingRow> {
        if (limit <= 0) return emptyList()
        val cutoff = LocalDateTime.now().minusDays(sinceDays)
        return transaction {
            (ArticleEmbeddingsTable innerJoin ArticlesTable)
                .select(
                    ArticleEmbeddingsTable.articleId, ArticleEmbeddingsTable.model,
                    ArticleEmbeddingsTable.vector, ArticlesTable.link, ArticlesTable.title,
                    ArticlesTable.category, ArticlesTable.pubDate, ArticlesTable.status
                )
                .where {
                    (ArticlesTable.category eq category) and
                        (ArticlesTable.pubDate greaterEq cutoff)
                }
                .orderBy(ArticlesTable.pubDate, SortOrder.DESC)
                .limit(limit)
                .map {
                    EmbeddingRow(
                        articleId = it[ArticleEmbeddingsTable.articleId],
                        link = it[ArticlesTable.link],
                        title = it[ArticlesTable.title],
                        category = it[ArticlesTable.category],
                        model = it[ArticleEmbeddingsTable.model],
                        vector = it[ArticleEmbeddingsTable.vector].bytes,
                        pubDate = it[ArticlesTable.pubDate],
                        status = it[ArticlesTable.status]
                    )
                }
        }
    }

    /**
     * Marks [articleId] as [ArticleStatus.DUPLICATE] and sets its `duplicate_of`
     * pointer to [duplicateOfId]. Idempotent — only updates rows whose current
     * status is neither PROCESSED nor PROCESSING (so we never demote an article
     * the digest pipeline has already claimed).
     */
    fun markDuplicate(articleId: Int, duplicateOfId: Int) {
        transaction {
            ArticlesTable.update({
                (ArticlesTable.id eq articleId) and
                    (ArticlesTable.status neq ArticleStatus.PROCESSED.name) and
                    (ArticlesTable.status neq ArticleStatus.PROCESSING.name)
            }) {
                it[status] = ArticleStatus.DUPLICATE.name
                it[duplicateOf] = duplicateOfId
                it[processingStartedAt] = null
            }
        }
    }

    /** Deletes embedding rows older than [retentionDays] (by `created_at`). */
    fun pruneOldEmbeddings(retentionDays: Long) {
        val cutoff = LocalDateTime.now().minusDays(retentionDays)
        transaction {
            ArticleEmbeddingsTable.deleteWhere { createdAt less cutoff }
        }
    }

    // ── Event embeddings (log-only event-level analyzer) ─────────────────────

    /**
     * Persists a single event embedding, upserted by `(category, event_key)`. Idempotent:
     * if a row already exists for the pair it is replaced with the new model/dim/vector and
     * `created_at` is refreshed (so recency reflects the latest sighting of the event).
     */
    fun saveEventEmbedding(
        category: String,
        eventKey: String,
        subject: String,
        franchise: String,
        model: String,
        vector: ByteArray
    ) {
        val dim = vector.size / 4
        transaction {
            val updated = EventEmbeddingsTable.update(
                { (EventEmbeddingsTable.category eq category) and (EventEmbeddingsTable.eventKey eq eventKey) }
            ) {
                it[EventEmbeddingsTable.subject] = subject
                it[EventEmbeddingsTable.franchise] = franchise
                it[EventEmbeddingsTable.model] = model
                it[EventEmbeddingsTable.dim] = dim
                it[EventEmbeddingsTable.vector] = org.jetbrains.exposed.sql.statements.api.ExposedBlob(vector)
                it[createdAt] = LocalDateTime.now()
            }
            if (updated == 0) {
                EventEmbeddingsTable.insertIgnore {
                    it[EventEmbeddingsTable.category] = category
                    it[EventEmbeddingsTable.eventKey] = eventKey
                    it[EventEmbeddingsTable.subject] = subject
                    it[EventEmbeddingsTable.franchise] = franchise
                    it[EventEmbeddingsTable.model] = model
                    it[EventEmbeddingsTable.dim] = dim
                    it[EventEmbeddingsTable.vector] = org.jetbrains.exposed.sql.statements.api.ExposedBlob(vector)
                    it[createdAt] = LocalDateTime.now()
                }
            }
        }
    }

    /**
     * Returns up to [limit] event embeddings in [category] whose `created_at` is within
     * [sinceDays] days, newest-first. Candidate set for the event-level analyzer's
     * brute-force cosine scan against previously-covered events.
     */
    fun fetchRecentEventEmbeddings(category: String, sinceDays: Long, limit: Int): List<EventEmbeddingRow> {
        if (limit <= 0) return emptyList()
        val cutoff = LocalDateTime.now().minusDays(sinceDays)
        return transaction {
            EventEmbeddingsTable
                .select(
                    EventEmbeddingsTable.category, EventEmbeddingsTable.eventKey,
                    EventEmbeddingsTable.subject, EventEmbeddingsTable.franchise,
                    EventEmbeddingsTable.model, EventEmbeddingsTable.vector, EventEmbeddingsTable.createdAt
                )
                .where {
                    (EventEmbeddingsTable.category eq category) and
                        (EventEmbeddingsTable.createdAt greaterEq cutoff)
                }
                .orderBy(EventEmbeddingsTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map {
                    EventEmbeddingRow(
                        category = it[EventEmbeddingsTable.category],
                        eventKey = it[EventEmbeddingsTable.eventKey],
                        subject = it[EventEmbeddingsTable.subject],
                        franchise = it[EventEmbeddingsTable.franchise],
                        model = it[EventEmbeddingsTable.model],
                        vector = it[EventEmbeddingsTable.vector].bytes,
                        createdAt = it[EventEmbeddingsTable.createdAt]
                    )
                }
        }
    }

    /** Deletes event embedding rows older than [retentionDays] (by `created_at`). */
    fun pruneOldEventEmbeddings(retentionDays: Long) {
        val cutoff = LocalDateTime.now().minusDays(retentionDays)
        transaction {
            EventEmbeddingsTable.deleteWhere { createdAt less cutoff }
        }
    }
}
