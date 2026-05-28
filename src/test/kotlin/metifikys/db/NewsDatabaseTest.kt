package metifikys.db

import metifikys.model.Article
import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewsDatabaseTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-news", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun article(link: String, category: String = "tech") = Article(
        category = category,
        title = "Title for $link",
        link = link,
        description = "Some description",
        pubDate = LocalDateTime.now()
    )

    @Test
    fun `insertArticles inserts new articles and returns count`() {
        val articles = listOf(article("https://example.com/1"), article("https://example.com/2"))
        val count = db.insertArticles(articles)
        assertEquals(2, count)
    }

    @Test
    fun `insertArticles deduplicates by link`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))
        val count = db.insertArticles(listOf(a))
        assertEquals(0, count)
    }

    @Test
    fun `default insert status is UNPROCESSED`() {
        db.insertArticles(listOf(article("https://example.com/1")))

        val ready = db.fetchReadyForDigestByCategory()

        assertEquals(1, ready["tech"]?.size)
    }

    @Test
    fun `markProcessing excludes fresh rows from ready pool`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))
        db.markProcessing(listOf(a.link))

        val ready = db.fetchReadyForDigestByCategory(staleTimeoutHours = 3)

        assertTrue(ready.isEmpty())
    }

    @Test
    fun `stale PROCESSING rows are reclaimable after cutoff`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))
        db.markProcessing(listOf(a.link))

        transaction {
            ArticlesTable.update({ ArticlesTable.link eq a.link }) {
                it[processingStartedAt] = LocalDateTime.now().minusHours(5)
            }
        }

        val ready = db.fetchReadyForDigestByCategory(staleTimeoutHours = 1)

        assertEquals(1, ready["tech"]?.size)
    }

    @Test
    fun `markUnprocessed returns rows to ready pool`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))
        db.markProcessing(listOf(a.link))

        db.markUnprocessed(listOf(a.link))

        val ready = db.fetchReadyForDigestByCategory()
        assertEquals(1, ready["tech"]?.size)
    }

    @Test
    fun `markProcessed permanently removes rows from ready pool`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))

        db.markProcessed(listOf(a.link))
        db.markUnprocessed(listOf(a.link))

        val ready = db.fetchReadyForDigestByCategory()
        assertTrue(ready.isEmpty())
    }

    @Test
    fun `deleteOlderThan removes old articles`() {
        val oldArticle = Article(
            category = "tech",
            title = "Old",
            link = "https://example.com/old",
            description = "old",
            pubDate = LocalDateTime.now().minusDays(10)
        )
        db.insertArticles(listOf(oldArticle))
        db.deleteOlderThan(7)
        val result = db.fetchReadyForDigestByCategory()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `migration from legacy processed column maps processed rows to PROCESSED`() {
        val legacyFile = File.createTempFile("legacy-news", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${legacyFile.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE articles (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          category VARCHAR(100) NOT NULL,
                          title VARCHAR(1000) NOT NULL,
                          link VARCHAR(2000) NOT NULL UNIQUE,
                          description TEXT NOT NULL,
                          pub_date DATETIME NOT NULL,
                          processed BOOLEAN NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        INSERT INTO articles(category, title, link, description, pub_date, processed)
                        VALUES ('tech', 'Done', 'https://example.com/done', 'desc', datetime('now'), 1)
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        INSERT INTO articles(category, title, link, description, pub_date, processed)
                        VALUES ('tech', 'Todo', 'https://example.com/todo', 'desc', datetime('now'), 0)
                        """.trimIndent()
                    )
                }
            }

            val migratedDb = NewsDatabase(legacyFile.absolutePath)
            val readyLinks = migratedDb.fetchReadyForDigestByCategory()["tech"]?.map { it.link } ?: emptyList()

            assertTrue(readyLinks.contains("https://example.com/todo"))
            assertFalse(readyLinks.contains("https://example.com/done"))
        } finally {
            legacyFile.delete()
        }
    }

    @Test
    fun `imageUrl round-trips through insert and fetch`() {
        val withImage = Article(
            category = "tech",
            title = "T",
            link = "https://example.com/img",
            description = "d",
            pubDate = LocalDateTime.now(),
            imageUrl = "https://cdn.example.com/p.jpg"
        )
        val withoutImage = Article(
            category = "tech",
            title = "T2",
            link = "https://example.com/no-img",
            description = "d",
            pubDate = LocalDateTime.now()
        )
        db.insertArticles(listOf(withImage, withoutImage))

        val byLink = db.fetchReadyForDigestByCategory()["tech"].orEmpty().associateBy { it.link }
        assertEquals("https://cdn.example.com/p.jpg", byLink["https://example.com/img"]?.imageUrl)
        assertEquals(null, byLink["https://example.com/no-img"]?.imageUrl)
    }

    // ── findExistingLinks tests ──────────────────────────────────────────────

    @Test
    fun `findExistingLinks returns links that exist in DB`() {
        db.insertArticles(listOf(article("https://example.com/1"), article("https://example.com/2")))
        val existing = db.findExistingLinks(listOf(
            "https://example.com/1",
            "https://example.com/2",
            "https://example.com/3"
        ))
        assertEquals(setOf("https://example.com/1", "https://example.com/2"), existing)
    }

    @Test
    fun `findExistingLinks returns empty set for no matches`() {
        val existing = db.findExistingLinks(listOf("https://example.com/999"))
        assertTrue(existing.isEmpty())
    }

    @Test
    fun `findExistingLinks handles empty input`() {
        val existing = db.findExistingLinks(emptyList())
        assertTrue(existing.isEmpty())
    }

    // ── Summary history tests ─────────────────────────────────────────────────

    @Test
    fun `saveSummary and fetchRecentSummaries returns summaries newest-first`() {
        db.saveSummary("tech", "First summary")
        Thread.sleep(50) // ensure distinct timestamps
        db.saveSummary("tech", "Second summary")
        Thread.sleep(50)
        db.saveSummary("tech", "Third summary")

        val results = db.fetchRecentSummaries("tech", 2)
        assertEquals(2, results.size)
        assertEquals("Third summary", results[0].summary)
        assertEquals("Second summary", results[1].summary)
    }

    @Test
    fun `fetchRecentSummaries isolates by category`() {
        db.saveSummary("tech", "Tech summary")
        db.saveSummary("gaming", "Gaming summary")

        val techResults = db.fetchRecentSummaries("tech", 10)
        assertEquals(1, techResults.size)
        assertEquals("Tech summary", techResults[0].summary)
    }

    @Test
    fun `fetchRecentSummaries returns empty list when no summaries exist`() {
        val results = db.fetchRecentSummaries("tech", 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `countPendingBatchesForCategory counts only pending rows for that category`() {
        db.savePendingBatch("batch-1", 0, 1, "tech")
        db.savePendingBatch("batch-2", 0, 1, "tech")
        db.savePendingBatch("batch-3", 0, 1, "tech")
        db.savePendingBatch("batch-4", 0, 1, "gaming")
        db.updateBatchStatus("batch-2", "completed")

        assertEquals(2, db.countPendingBatchesForCategory("tech"))
        assertEquals(1, db.countPendingBatchesForCategory("gaming"))
        assertEquals(0, db.countPendingBatchesForCategory("news"))
    }

    @Test
    fun `fetchArticlesByLinks returns only the requested articles`() {
        db.insertArticles(listOf(
            article("https://example.com/1"),
            article("https://example.com/2"),
            article("https://example.com/3")
        ))
        val result = db.fetchArticlesByLinks(listOf("https://example.com/1", "https://example.com/3"))
        assertEquals(2, result.size)
        assertEquals(setOf("https://example.com/1", "https://example.com/3"), result.map { it.link }.toSet())
    }

    @Test
    fun `savePendingBatch articleLinks round-trips through fetchPendingBatches`() {
        val links = listOf("https://example.com/a", "https://example.com/b").joinToString("\n")
        db.savePendingBatch("batch-rt", 0, 1, "tech", links)
        val batch = db.fetchPendingBatches().single { it.batchId == "batch-rt" }
        assertEquals(links, batch.articleLinks)
    }

    @Test
    fun `deleteOldSummaries preserves recent summaries`() {
        db.saveSummary("tech", "Recent summary")
        db.deleteOldSummaries(14)

        val results = db.fetchRecentSummaries("tech", 10)
        assertEquals(1, results.size)
    }
}
