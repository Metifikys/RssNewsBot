package metifikys.db

import metifikys.model.Article
import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

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
    /** Source-specific signals captured at ingestion (JSON, see [metifikys.model.ArticleMeta]); null for most feeds. */
    val metaJson = text("meta_json").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Ingested articles and their UNPROCESSED → PROCESSING → PROCESSED / DUPLICATE lifecycle. */
interface ArticleStore {
    /**
     * Returns the subset of [links] that already exist in the articles table.
     * Used to skip enrichment for articles we already have.
     */
    fun findExistingLinks(links: List<String>): Set<String>

    fun insertArticles(articles: List<Article>): Int

    fun fetchReadyForDigestByCategory(staleTimeoutHours: Long = 3): Map<String, List<Article>>

    /**
     * Single-category variant of [fetchReadyForDigestByCategory] — same readiness predicate
     * and ordering, scoped to one category so per-category pipelines don't pull each
     * other's articles.
     */
    fun fetchReadyForDigest(category: String, staleTimeoutHours: Long = 3): List<Article>

    fun fetchProcessingByCategory(category: String): List<Article>

    /**
     * Returns the articles for [links] in the order of [links] (first occurrence wins; unknown
     * links are skipped). Order matters: a resumed batch rebuilds the prompt's article list from
     * its saved links, and Step 1 `articleIndices` are positions in that list — SQLite's own
     * `IN` order (link-sorted via the unique index) would silently re-map them.
     */
    fun fetchArticlesByLinks(links: List<String>): List<Article>

    /**
     * Returns up to [limit] articles in [category] whose `pubDate` is within [sinceDays] days,
     * newest-first, across ALL statuses (DUPLICATE rows included — a near-duplicate the
     * semantic-dedup detector blocked is itself a "this story repeated" signal). Used by the
     * weekly roundup's article-level fallback for categories with no covered events.
     */
    fun fetchRecentArticles(category: String, sinceDays: Long, limit: Int): List<Article>

    fun markProcessed(links: List<String>)

    fun markProcessing(links: List<String>)

    /**
     * Startup recovery: a row still PROCESSING when a new JVM starts belongs to a worker that no
     * longer exists — unless a pending Batch API job (whose links are passed as [keepLinks]) still
     * references it. Such rows go straight back to UNPROCESSED instead of waiting out the
     * stale-timeout: on 2026-09-03 a forced shutdown mid Step-1 orphaned ~100 tech rows and, with
     * `staleTimeoutHours=26`, they resurfaced 26 h later as one ~135 KB extract prompt that timed
     * out for 3 h. Returns the number of rows reclaimed.
     */
    fun reclaimOrphanedProcessing(keepLinks: Collection<String> = emptyList()): Int

    fun markUnprocessed(links: List<String>)

    fun deleteOlderThan(days: Long = 7)

    /**
     * Returns the autoincrement ids for [links], keyed by link. Missing links are
     * absent from the map. Used by the semantic-dedup detector to bind freshly-
     * inserted articles to the embeddings it generated for them.
     */
    fun fetchArticleIdsByLinks(links: List<String>): Map<String, Int>

    /**
     * Marks [articleId] as [ArticleStatus.DUPLICATE] and sets its `duplicate_of`
     * pointer to [duplicateOfId]. Idempotent — only updates rows whose current
     * status is neither PROCESSED nor PROCESSING (so we never demote an article
     * the digest pipeline has already claimed).
     */
    fun markDuplicate(articleId: Int, duplicateOfId: Int)
}

internal class ArticleRepository : ArticleStore {

    override fun findExistingLinks(links: List<String>): Set<String> {
        if (links.isEmpty()) return emptySet()
        return transaction {
            ArticlesTable
                .select(ArticlesTable.link)
                .where { ArticlesTable.link inList links }
                .map { it[ArticlesTable.link] }
                .toSet()
        }
    }

    override fun insertArticles(articles: List<Article>): Int {
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
                    it[metaJson] = article.metaJson
                }
                if (result.insertedCount > 0) inserted++
            }
        }
        return inserted
    }

    /** One place that maps an `articles` row to the model, so every query returns the same shape. */
    private fun ResultRow.toArticle(): Article = Article(
        category = this[ArticlesTable.category],
        title = this[ArticlesTable.title],
        link = this[ArticlesTable.link],
        description = this[ArticlesTable.description],
        pubDate = this[ArticlesTable.pubDate],
        imageUrl = this[ArticlesTable.imageUrl],
        summary = this[ArticlesTable.summary],
        metaJson = this[ArticlesTable.metaJson]
    )

    override fun fetchReadyForDigestByCategory(staleTimeoutHours: Long): Map<String, List<Article>> {
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
                // BUG-015: deterministic, newest-first order so the prompt's article order is stable
                // across runs. id DESC breaks ties when several rows share a pubDate.
                .orderBy(ArticlesTable.pubDate to SortOrder.DESC, ArticlesTable.id to SortOrder.DESC)
                .map { it.toArticle() }
                .groupBy { it.category }
        }
    }

    override fun fetchReadyForDigest(category: String, staleTimeoutHours: Long): List<Article> {
        val cutoff = LocalDateTime.now().minusHours(staleTimeoutHours)
        return transaction {
            ArticlesTable
                .selectAll()
                .where {
                    (ArticlesTable.category eq category) and
                        (
                            (ArticlesTable.status eq ArticleStatus.UNPROCESSED.name) or
                                (
                                    (ArticlesTable.status eq ArticleStatus.PROCESSING.name) and
                                        (ArticlesTable.processingStartedAt.isNull() or (ArticlesTable.processingStartedAt less cutoff))
                                    )
                            )
                }
                // BUG-015: deterministic, newest-first order so the prompt's article order is stable
                // across runs. id DESC breaks ties when several rows share a pubDate.
                .orderBy(ArticlesTable.pubDate to SortOrder.DESC, ArticlesTable.id to SortOrder.DESC)
                .map { it.toArticle() }
        }
    }

    override fun fetchProcessingByCategory(category: String): List<Article> {
        return transaction {
            ArticlesTable
                .selectAll()
                .where {
                    (ArticlesTable.category eq category) and
                        (ArticlesTable.status eq ArticleStatus.PROCESSING.name)
                }
                .map { it.toArticle() }
        }
    }

    override fun fetchArticlesByLinks(links: List<String>): List<Article> {
        if (links.isEmpty()) return emptyList()
        val byLink = transaction {
            ArticlesTable.selectAll()
                .where { ArticlesTable.link inList links }
                .associateBy({ it[ArticlesTable.link] }, { it.toArticle() })
        }
        return links.distinct().mapNotNull { byLink[it] }
    }

    override fun fetchRecentArticles(category: String, sinceDays: Long, limit: Int): List<Article> {
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
                .map { it.toArticle() }
        }
    }

    override fun markProcessed(links: List<String>) {
        if (links.isEmpty()) return
        transaction {
            ArticlesTable.update({ ArticlesTable.link inList links }) {
                it[status] = ArticleStatus.PROCESSED.name
                it[processingStartedAt] = null
            }
        }
    }

    override fun markProcessing(links: List<String>) {
        if (links.isEmpty()) return
        transaction {
            // BUG-016: claim only genuinely-ready (UNPROCESSED) rows. The old predicate
            // (status != PROCESSED) also matched already-PROCESSING rows and reset their
            // processingStartedAt to now() — bumping a nearly-stale article back to fresh and
            // deferring its stale-timeout recovery by another full window. A PROCESSING → PROCESSING
            // transition is now a no-op; stale rows are reclaimed via fetchReadyForDigestByCategory.
            ArticlesTable.update({
                (ArticlesTable.link inList links) and
                    (ArticlesTable.status eq ArticleStatus.UNPROCESSED.name)
            }) {
                it[status] = ArticleStatus.PROCESSING.name
                it[processingStartedAt] = LocalDateTime.now()
            }
        }
    }

    override fun reclaimOrphanedProcessing(keepLinks: Collection<String>): Int = transaction {
        val keep = keepLinks.toSet()
        ArticlesTable.update({
            val orphaned = ArticlesTable.status eq ArticleStatus.PROCESSING.name
            if (keep.isEmpty()) orphaned else orphaned and (ArticlesTable.link notInList keep)
        }) {
            it[status] = ArticleStatus.UNPROCESSED.name
            it[processingStartedAt] = null
        }
    }

    override fun markUnprocessed(links: List<String>) {
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

    override fun deleteOlderThan(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            ArticlesTable.deleteWhere { pubDate less cutoff }
        }
    }

    // ── Semantic-dedup detector (article side; the vectors live in EmbeddingRepository) ──

    override fun fetchArticleIdsByLinks(links: List<String>): Map<String, Int> {
        if (links.isEmpty()) return emptyMap()
        return transaction {
            ArticlesTable
                .select(ArticlesTable.id, ArticlesTable.link)
                .where { ArticlesTable.link inList links }
                .associate { it[ArticlesTable.link] to it[ArticlesTable.id] }
        }
    }

    override fun markDuplicate(articleId: Int, duplicateOfId: Int) {
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
}
