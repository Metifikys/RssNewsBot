package metifikys.db

import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

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

/**
 * Embedding vectors: per-article (semantic-dedup detector) and per-event (log-only
 * event-level analyzer). The article-side writes that touch `articles` itself
 * (`fetchArticleIdsByLinks`, `markDuplicate`) live in [ArticleStore].
 */
interface EmbeddingStore {
    /**
     * Persists a single embedding. Idempotent: if a row already exists for [articleId]
     * it is replaced with the new model/dim/vector (the detector runs once per cycle
     * so this is rare, but it keeps re-runs deterministic).
     */
    fun saveEmbedding(articleId: Int, model: String, vector: ByteArray)

    /**
     * Returns up to [limit] embeddings for articles in [category] whose `pubDate`
     * is within [sinceDays] days, newest-first. Used by the semantic-dedup detector
     * to scan candidate neighbours.
     */
    fun fetchRecentEmbeddings(category: String, sinceDays: Long, limit: Int): List<EmbeddingRow>

    /** Deletes embedding rows older than [retentionDays] (by `created_at`). */
    fun pruneOldEmbeddings(retentionDays: Long)

    /** Stored vectors of [articleIds] made with [model], keyed by article id (the story lines' reprint check). */
    fun fetchEmbeddingsByArticleIds(articleIds: Collection<Int>, model: String): Map<Int, ByteArray>

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
    )

    /**
     * Returns up to [limit] event embeddings in [category] whose `created_at` is within
     * [sinceDays] days, newest-first. Candidate set for the event-level analyzer's
     * brute-force cosine scan against previously-covered events.
     */
    fun fetchRecentEventEmbeddings(category: String, sinceDays: Long, limit: Int): List<EventEmbeddingRow>

    /** The stored vectors of [eventKeys] in [category], keyed by event key (the cross-channel links). */
    fun fetchEventEmbeddings(category: String, eventKeys: Collection<String>): Map<String, EventEmbeddingRow>

    /** Deletes event embedding rows older than [retentionDays] (by `created_at`). */
    fun pruneOldEventEmbeddings(retentionDays: Long)
}

internal class EmbeddingRepository : EmbeddingStore {

    // ── Article embeddings (semantic-dedup detector) ─────────────────────────

    override fun saveEmbedding(articleId: Int, model: String, vector: ByteArray) {
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

    override fun fetchRecentEmbeddings(category: String, sinceDays: Long, limit: Int): List<EmbeddingRow> {
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

    override fun fetchEmbeddingsByArticleIds(articleIds: Collection<Int>, model: String): Map<Int, ByteArray> {
        if (articleIds.isEmpty()) return emptyMap()
        return transaction {
            ArticleEmbeddingsTable
                .select(ArticleEmbeddingsTable.articleId, ArticleEmbeddingsTable.vector)
                .where { (ArticleEmbeddingsTable.articleId inList articleIds.distinct()) and (ArticleEmbeddingsTable.model eq model) }
                .associate { it[ArticleEmbeddingsTable.articleId] to it[ArticleEmbeddingsTable.vector].bytes }
        }
    }

    override fun pruneOldEmbeddings(retentionDays: Long) {
        val cutoff = LocalDateTime.now().minusDays(retentionDays)
        transaction {
            ArticleEmbeddingsTable.deleteWhere { createdAt less cutoff }
        }
    }

    // ── Event embeddings (log-only event-level analyzer) ─────────────────────

    override fun saveEventEmbedding(
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

    override fun fetchRecentEventEmbeddings(category: String, sinceDays: Long, limit: Int): List<EventEmbeddingRow> {
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

    override fun fetchEventEmbeddings(category: String, eventKeys: Collection<String>): Map<String, EventEmbeddingRow> {
        if (eventKeys.isEmpty()) return emptyMap()
        return transaction {
            EventEmbeddingsTable
                .selectAll()
                .where { (EventEmbeddingsTable.category eq category) and (EventEmbeddingsTable.eventKey inList eventKeys.toSet()) }
                .associate { it[EventEmbeddingsTable.eventKey] to it.toEventEmbeddingRow() }
        }
    }

    private fun ResultRow.toEventEmbeddingRow() = EventEmbeddingRow(
        category = this[EventEmbeddingsTable.category],
        eventKey = this[EventEmbeddingsTable.eventKey],
        subject = this[EventEmbeddingsTable.subject],
        franchise = this[EventEmbeddingsTable.franchise],
        model = this[EventEmbeddingsTable.model],
        vector = this[EventEmbeddingsTable.vector].bytes,
        createdAt = this[EventEmbeddingsTable.createdAt]
    )

    override fun pruneOldEventEmbeddings(retentionDays: Long) {
        val cutoff = LocalDateTime.now().minusDays(retentionDays)
        transaction {
            EventEmbeddingsTable.deleteWhere { createdAt less cutoff }
        }
    }
}
