package metifikys.db

import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
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
