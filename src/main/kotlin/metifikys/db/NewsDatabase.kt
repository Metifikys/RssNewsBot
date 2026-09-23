package metifikys.db

import io.github.oshai.kotlinlogging.KotlinLogging
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

private val logger = KotlinLogging.logger {}

class NewsDatabase(dbPath: String) :
    BatchStore by BatchRepository(),
    SummaryStore by SummaryRepository(),
    CoveredEventStore by CoveredEventRepository(),
    RejectedEventStore by RejectedEventRepository(),
    LlmCallStore by LlmCallRepository(),
    StatusQueryStore by StatusQueryRepository(),
    EmbeddingStore by EmbeddingRepository(),
    DigestMessageStore by DigestMessageRepository(),
    AffinityStore by AffinityRepository(),
    BadgeCacheStore by BadgeCacheRepository(),
    TrackerStore by TrackerRepository(),
    EntityIntroStore by EntityIntroRepository(),
    BotStateStore by BotStateRepository() {

    /** Canonical path of the DB file; side jobs (e.g. [RetentionArchiver]) place their files next to it. */
    val canonicalPath: String

    /** JDBC URL of the main DB (WAL + busy_timeout), for raw-JDBC side jobs such as [RetentionArchiver]. */
    val jdbcUrl: String

    init {
        // Security: reject path traversal and resolve to a canonical absolute path
        require(dbPath.isNotBlank()) { "Database path must not be blank" }
        require(!dbPath.contains("..")) { "Database path must not contain '..' (path traversal): $dbPath" }
        canonicalPath = java.io.File(dbPath).canonicalPath

        // Concurrency hardening for the multi-writer workload (category workers, reaction poller,
        // batch callbacks, weekly digest). Xerial parses these query params into SQLiteConfig and
        // applies them per-connection, so every Exposed `transaction { }` (each opens its own
        // connection) is covered:
        //   journal_mode=WAL   — readers no longer block the single writer; kills most
        //                        "database is locked". WAL is a persistent property of the db file.
        //   synchronous=NORMAL — SQLite-recommended pairing with WAL; holds write locks for less
        //                        time. Only the last transaction(s) can be lost on an OS/power
        //                        crash (not on a normal app crash) — fine for a news bot.
        //   busy_timeout=15000 — a writer that still hits a held write lock waits-and-retries up
        //                        to 15s instead of failing fast with SQLITE_BUSY.
        jdbcUrl = "jdbc:sqlite:$canonicalPath" +
            "?journal_mode=WAL" +
            "&synchronous=NORMAL" +
            "&busy_timeout=15000"
        Database.connect(jdbcUrl, driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                ArticlesTable, PendingBatchesTable, SummariesTable, CoveredEventsTable, RejectedEventsTable,
                LlmCallsTable, ArticleEmbeddingsTable, EventEmbeddingsTable,
                DigestMessagesTable, ReactionCountsTable, AudienceAffinityTable, BotStateTable,
                BadgeCacheTable, EntityIntrosTable, TrackerEntriesTable
            )
        }
        migrateArticleStatuses(jdbcUrl)
    }

    private fun migrateArticleStatuses(jdbcUrl: String) {
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                // Confirm WAL actually engaged. SQLite silently falls back to a rollback journal
                // when the db file is on a network share; production is local disk, so expect "wal".
                statement.executeQuery("PRAGMA journal_mode=WAL").use { rs ->
                    val mode = if (rs.next()) rs.getString(1) else "?"
                    logger.info { "SQLite journal_mode=$mode" }
                }

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

                // BUG-025: only touch rows that actually carry a stray timestamp, so the returned
                // count means "rows that violated the invariant" rather than "all non-PROCESSING
                // rows". A non-zero count signals a state-transition that failed to clear it at the
                // source (markProcessed/markUnprocessed both null it now) — worth surfacing.
                val fixed = statement.executeUpdate(
                    """
                    UPDATE articles
                    SET processing_started_at = NULL
                    WHERE status != '${ArticleStatus.PROCESSING.name}'
                      AND processing_started_at IS NOT NULL
                    """.trimIndent()
                )
                if (fixed > 0) {
                    logger.warn {
                        "[Migrate] Cleared a stray processing_started_at on $fixed non-PROCESSING row(s) — " +
                            "a state transition left the invariant broken (safety net caught it)."
                    }
                }

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
                // BUG-015: deterministic, newest-first order so the prompt's article order is stable
                // across runs. id DESC breaks ties when several rows share a pubDate.
                .orderBy(ArticlesTable.pubDate to SortOrder.DESC, ArticlesTable.id to SortOrder.DESC)
                .map { it.toArticle() }
                .groupBy { it.category }
        }
    }

    /**
     * Single-category variant of [fetchReadyForDigestByCategory] — same readiness predicate
     * and ordering, scoped to one category so per-category pipelines don't pull each
     * other's articles.
     */
    fun fetchReadyForDigest(category: String, staleTimeoutHours: Long = 3): List<Article> {
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

    fun fetchProcessingByCategory(category: String): List<Article> {
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

    /**
     * Returns the articles for [links] in the order of [links] (first occurrence wins; unknown
     * links are skipped). Order matters: a resumed batch rebuilds the prompt's article list from
     * its saved links, and Step 1 `articleIndices` are positions in that list — SQLite's own
     * `IN` order (link-sorted via the unique index) would silently re-map them.
     */
    fun fetchArticlesByLinks(links: List<String>): List<Article> {
        if (links.isEmpty()) return emptyList()
        val byLink = transaction {
            ArticlesTable.selectAll()
                .where { ArticlesTable.link inList links }
                .associateBy({ it[ArticlesTable.link] }, { it.toArticle() })
        }
        return links.distinct().mapNotNull { byLink[it] }
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
                .map { it.toArticle() }
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

    /**
     * Startup recovery: a row still PROCESSING when a new JVM starts belongs to a worker that no
     * longer exists — unless a pending Batch API job (whose links are passed as [keepLinks]) still
     * references it. Such rows go straight back to UNPROCESSED instead of waiting out the
     * stale-timeout: on 2026-09-03 a forced shutdown mid Step-1 orphaned ~100 tech rows and, with
     * `staleTimeoutHours=26`, they resurfaced 26 h later as one ~135 KB extract prompt that timed
     * out for 3 h. Returns the number of rows reclaimed.
     */
    fun reclaimOrphanedProcessing(keepLinks: Collection<String> = emptyList()): Int = transaction {
        val keep = keepLinks.toSet()
        ArticlesTable.update({
            val orphaned = ArticlesTable.status eq ArticleStatus.PROCESSING.name
            if (keep.isEmpty()) orphaned else orphaned and (ArticlesTable.link notInList keep)
        }) {
            it[status] = ArticleStatus.UNPROCESSED.name
            it[processingStartedAt] = null
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
}
