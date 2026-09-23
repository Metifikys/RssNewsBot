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
    RejectedEventStore by RejectedEventRepository(),
    LlmCallStore by LlmCallRepository(),
    StatusQueryStore by StatusQueryRepository(),
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

    /**
     * Covered events for [category] whose `event_key` is in [eventKeys] and `covered_at >= since`.
     * Keyed lookup for the meaningful-update cooldown: unlike [fetchRecentEvents] it is not
     * bounded by `maxContextEvents`, so an event that has already scrolled out of the Step 1
     * prompt context can still be found for the cooldown check.
     */
    fun fetchCoveredEventsByKeys(category: String, eventKeys: Collection<String>, since: LocalDateTime): List<CoveredEventRow> {
        if (eventKeys.isEmpty()) return emptyList()
        return transaction {
            CoveredEventsTable
                .selectAll()
                .where {
                    (CoveredEventsTable.category eq category) and
                        (CoveredEventsTable.eventKey inList eventKeys.toSet()) and
                        (CoveredEventsTable.coveredAt greaterEq since)
                }
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

    // ── Digest messages & reactions ──────────────────────────────────────────

    /**
     * Records one row per delivered digest message. Idempotent on `(chat_id, message_id)`:
     * a redelivery with the same ids (should not happen) is ignored rather than duplicated.
     */
    fun insertDigestMessages(rows: List<DigestMessageRow>) {
        if (rows.isEmpty()) return
        transaction {
            for (r in rows) {
                DigestMessagesTable.insertIgnore {
                    it[chatId] = r.chatId
                    it[messageId] = r.messageId
                    it[category] = r.category
                    it[eventKey] = r.eventKey
                    it[articleLinks] = r.articleLinks
                    it[sentAt] = r.sentAt
                }
            }
        }
    }

    /**
     * How many posts the channel already carries per event key: rows of [category] whose
     * `event_key` is in [eventKeys] and `sent_at >= since`, counted as distinct deliveries
     * (a chunked topic writes several rows with one `sent_at` and counts once). Keys with no
     * posts are absent. Backs `digest.maxPostsPerEvent`.
     */
    fun countDigestPostsByEventKeys(
        category: String,
        eventKeys: Collection<String>,
        since: LocalDateTime
    ): Map<String, Int> {
        if (eventKeys.isEmpty()) return emptyMap()
        return transaction {
            DigestMessagesTable
                .select(DigestMessagesTable.eventKey, DigestMessagesTable.sentAt)
                .where {
                    (DigestMessagesTable.category eq category) and
                        (DigestMessagesTable.eventKey inList eventKeys.toSet()) and
                        (DigestMessagesTable.sentAt greaterEq since)
                }
                .mapNotNull { row -> row[DigestMessagesTable.eventKey]?.let { it to row[DigestMessagesTable.sentAt] } }
                .distinct()
                .groupingBy { it.first }
                .eachCount()
        }
    }

    /**
     * The latest delivered message per event key: rows of [category] whose `event_key` is in
     * [eventKeys] and `sent_at >= since`. The newest digest wins; within one digest the lowest
     * message id (the first chunk of a chunked topic). Backs the reply threading in
     * [metifikys.digest.DigestDeliverer] — "the previous post about this story".
     */
    fun fetchLatestDigestMessagesByEventKeys(
        category: String,
        eventKeys: Collection<String>,
        since: LocalDateTime
    ): Map<String, DigestMessageRow> {
        if (eventKeys.isEmpty()) return emptyMap()
        return transaction {
            val out = LinkedHashMap<String, DigestMessageRow>()
            DigestMessagesTable
                .selectAll()
                .where {
                    (DigestMessagesTable.category eq category) and
                        (DigestMessagesTable.eventKey inList eventKeys.toSet()) and
                        (DigestMessagesTable.sentAt greaterEq since)
                }
                .orderBy(DigestMessagesTable.sentAt to SortOrder.DESC, DigestMessagesTable.messageId to SortOrder.ASC)
                .forEach { row ->
                    val key = row[DigestMessagesTable.eventKey] ?: return@forEach
                    out.putIfAbsent(
                        key,
                        DigestMessageRow(
                            chatId = row[DigestMessagesTable.chatId],
                            messageId = row[DigestMessagesTable.messageId],
                            category = row[DigestMessagesTable.category],
                            eventKey = key,
                            articleLinks = row[DigestMessagesTable.articleLinks],
                            sentAt = row[DigestMessagesTable.sentAt]
                        )
                    )
                }
            out
        }
    }

    /**
     * Replaces the stored reaction set for one message with [counts]. A `message_reaction_count`
     * update always carries the message's *complete* current reactions, so this deletes the old
     * rows and re-inserts the new set in one transaction — an empty [counts] (last reaction
     * removed) therefore clears the message. Zero/negative counts are skipped.
     */
    fun replaceReactionCounts(chatId: Long, messageId: Long, counts: List<ReactionCount>) {
        transaction {
            ReactionCountsTable.deleteWhere {
                (ReactionCountsTable.chatId eq chatId) and (ReactionCountsTable.messageId eq messageId)
            }
            val now = LocalDateTime.now()
            for (c in counts) {
                if (c.count <= 0) continue
                ReactionCountsTable.insert {
                    it[ReactionCountsTable.chatId] = chatId
                    it[ReactionCountsTable.messageId] = messageId
                    it[emoji] = c.emoji
                    it[count] = c.count
                    it[updatedAt] = now
                }
            }
        }
    }

    /**
     * Per-category reaction rollup over the last [sinceHours] hours, windowed by the digest
     * message's `sent_at`. Joins reactions to their digest message so counts are attributed to a
     * category; messages we didn't send (no `digest_messages` row) are excluded. Categories with
     * no in-window reactions are absent from the map. Aggregated in-process — the reaction volume
     * is tiny relative to articles.
     */
    fun fetchReactionSummary(sinceHours: Long): Map<String, CategoryReactionSummary> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val recs = transaction {
            ReactionCountsTable
                .join(
                    DigestMessagesTable, JoinType.INNER,
                    additionalConstraint = {
                        (ReactionCountsTable.chatId eq DigestMessagesTable.chatId) and
                            (ReactionCountsTable.messageId eq DigestMessagesTable.messageId)
                    }
                )
                .select(
                    DigestMessagesTable.category, ReactionCountsTable.chatId,
                    ReactionCountsTable.messageId, ReactionCountsTable.emoji, ReactionCountsTable.count
                )
                .where { DigestMessagesTable.sentAt greaterEq cutoff }
                .map {
                    ReactionRec(
                        category = it[DigestMessagesTable.category],
                        chatId = it[ReactionCountsTable.chatId],
                        messageId = it[ReactionCountsTable.messageId],
                        emoji = it[ReactionCountsTable.emoji],
                        count = it[ReactionCountsTable.count]
                    )
                }
        }
        return recs.groupBy { it.category }.mapValues { (cat, list) ->
            val byEmoji = list.groupBy { it.emoji }
                .map { (emoji, rows) -> ReactionCount(emoji, rows.sumOf { it.count }) }
                .sortedByDescending { it.count }
            CategoryReactionSummary(
                category = cat,
                totalReactions = list.sumOf { it.count.toLong() },
                postCount = list.map { it.chatId to it.messageId }.distinct().size.toLong(),
                byEmoji = byEmoji
            )
        }
    }

    /** Deletes digest-message links older than [days] days (by `sent_at`). */
    fun deleteOldDigestMessages(days: Long = 90) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            DigestMessagesTable.deleteWhere { sentAt less cutoff }
        }
    }

    /** Deletes reaction-count rows not touched in [days] days (by `updated_at`). */
    fun deleteOldReactionCounts(days: Long = 90) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            ReactionCountsTable.deleteWhere { updatedAt less cutoff }
        }
    }

    // ── Audience affinity ────────────────────────────────────────────────────

    /**
     * Loads the affinity-aggregation input: every digest message sent between
     * `now - lookbackDays` and `now - olderThanHours` that has an `event_key` (legacy
     * single-step messages carry none and stay out of the learning set), joined to its
     * covered-event dimensions and LEFT-joined to its current reactions. Zero-reaction
     * messages are included on purpose — they anchor the cohort baseline. Grouped
     * in-process; the volume is a few messages per category per day.
     *
     * NOTE: the INNER join to `covered_events` means the *effective* lookback is
     * `min(lookbackDays, summaryHistory.retentionDays)` — `pruneOldCoveredEvents` (14d by
     * default) deletes the dimensions of older messages, silently dropping them from the
     * learning set. Raise `summaryHistory.retentionDays` to widen the real window.
     */
    fun fetchAffinityInputs(olderThanHours: Long, lookbackDays: Long): List<AffinityInputMessage> {
        val now = LocalDateTime.now()
        val newest = now.minusHours(olderThanHours)
        val oldest = now.minusDays(lookbackDays)
        data class Flat(
            val chatId: Long, val messageId: Long, val category: String, val sentAt: LocalDateTime,
            val subject: String, val franchise: String, val eventType: String, val url: String,
            val emoji: String?, val count: Int?
        )
        val flat = transaction {
            DigestMessagesTable
                .join(
                    CoveredEventsTable, JoinType.INNER,
                    additionalConstraint = {
                        (DigestMessagesTable.category eq CoveredEventsTable.category) and
                            (DigestMessagesTable.eventKey eq CoveredEventsTable.eventKey)
                    }
                )
                .join(
                    ReactionCountsTable, JoinType.LEFT,
                    additionalConstraint = {
                        (DigestMessagesTable.chatId eq ReactionCountsTable.chatId) and
                            (DigestMessagesTable.messageId eq ReactionCountsTable.messageId)
                    }
                )
                .select(
                    DigestMessagesTable.chatId, DigestMessagesTable.messageId,
                    DigestMessagesTable.category, DigestMessagesTable.sentAt,
                    CoveredEventsTable.subject, CoveredEventsTable.franchise,
                    CoveredEventsTable.eventType, CoveredEventsTable.url,
                    ReactionCountsTable.emoji, ReactionCountsTable.count
                )
                .where { (DigestMessagesTable.sentAt greaterEq oldest) and (DigestMessagesTable.sentAt less newest) }
                .map {
                    Flat(
                        chatId = it[DigestMessagesTable.chatId],
                        messageId = it[DigestMessagesTable.messageId],
                        category = it[DigestMessagesTable.category],
                        sentAt = it[DigestMessagesTable.sentAt],
                        subject = it[CoveredEventsTable.subject],
                        franchise = it[CoveredEventsTable.franchise],
                        eventType = it[CoveredEventsTable.eventType],
                        url = it[CoveredEventsTable.url],
                        emoji = it.getOrNull(ReactionCountsTable.emoji),
                        count = it.getOrNull(ReactionCountsTable.count)
                    )
                }
        }
        return flat.groupBy { it.chatId to it.messageId }.map { (_, rows) ->
            val first = rows.first()
            AffinityInputMessage(
                chatId = first.chatId,
                messageId = first.messageId,
                category = first.category,
                sentAt = first.sentAt,
                subject = first.subject,
                franchise = first.franchise,
                eventType = first.eventType,
                url = first.url,
                reactions = rows.mapNotNull { r ->
                    val emoji = r.emoji ?: return@mapNotNull null
                    ReactionCount(emoji, r.count ?: 0)
                }
            )
        }
    }

    /**
     * Replaces the whole materialized affinity set with [rows] in one transaction.
     * A full rebuild is idempotent and the volume is tiny (tens of rows), so
     * delete-all + insert is simpler and safer than a per-key upsert diff.
     */
    fun replaceAudienceAffinity(rows: List<AudienceAffinityRow>) {
        transaction {
            AudienceAffinityTable.deleteAll()
            for (r in rows) {
                AudienceAffinityTable.insert {
                    it[category] = r.category
                    it[dimension] = r.dimension
                    it[key] = r.key
                    it[n] = r.n
                    it[nTone] = r.nTone
                    it[engagementZ] = r.engagementZ
                    it[sentiment] = r.sentiment
                    it[score] = r.score
                    it[updatedAt] = r.updatedAt
                }
            }
        }
    }

    /** Reads all materialized affinity rows (every category/dimension), unordered. */
    fun fetchAudienceAffinity(): List<AudienceAffinityRow> = transaction {
        AudienceAffinityTable.selectAll().map {
            AudienceAffinityRow(
                category = it[AudienceAffinityTable.category],
                dimension = it[AudienceAffinityTable.dimension],
                key = it[AudienceAffinityTable.key],
                n = it[AudienceAffinityTable.n],
                nTone = it[AudienceAffinityTable.nTone],
                engagementZ = it[AudienceAffinityTable.engagementZ],
                sentiment = it[AudienceAffinityTable.sentiment],
                score = it[AudienceAffinityTable.score],
                updatedAt = it[AudienceAffinityTable.updatedAt]
            )
        }
    }

    /** Internal join-row for [fetchReactionSummary] aggregation. */
    private data class ReactionRec(
        val category: String,
        val chatId: Long,
        val messageId: Long,
        val emoji: String,
        val count: Int
    )
}
