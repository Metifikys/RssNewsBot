package metifikys.db

import metifikys.digest.VectorMath
import metifikys.model.Article
import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArticleEmbeddingsTableTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-embeddings", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun seed(category: String, link: String, pubDate: LocalDateTime = LocalDateTime.now()): Int {
        db.insertArticles(listOf(
            Article(
                category = category,
                title = "Title $link",
                link = link,
                description = "desc",
                pubDate = pubDate
            )
        ))
        return db.fetchArticleIdsByLinks(listOf(link))[link]
            ?: error("Failed to fetch id for $link")
    }

    @Test
    fun `fetchArticleIdsByLinks returns ids for inserted articles`() {
        val id1 = seed("tech", "https://a.com/1")
        val id2 = seed("tech", "https://a.com/2")
        val map = db.fetchArticleIdsByLinks(listOf("https://a.com/1", "https://a.com/2", "https://a.com/missing"))
        assertEquals(2, map.size)
        assertEquals(id1, map["https://a.com/1"])
        assertEquals(id2, map["https://a.com/2"])
    }

    @Test
    fun `fetchArticleIdsByLinks handles empty input`() {
        assertTrue(db.fetchArticleIdsByLinks(emptyList()).isEmpty())
    }

    @Test
    fun `saveEmbedding round-trips through fetchRecentEmbeddings`() {
        val id = seed("tech", "https://a.com/1")
        val v = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        db.saveEmbedding(id, "text-embedding-3-small", VectorMath.encode(v))

        val rows = db.fetchRecentEmbeddings("tech", sinceDays = 30, limit = 10)
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(id, row.articleId)
        assertEquals("https://a.com/1", row.link)
        assertEquals("text-embedding-3-small", row.model)
        val decoded = VectorMath.decode(row.vector)
        assertEquals(v.size, decoded.size)
        for (i in v.indices) assertTrue(kotlin.math.abs(v[i] - decoded[i]) < 1e-6f)
    }

    @Test
    fun `saveEmbedding upserts on duplicate articleId`() {
        val id = seed("tech", "https://a.com/1")
        db.saveEmbedding(id, "model-a", VectorMath.encode(floatArrayOf(1f, 2f)))
        db.saveEmbedding(id, "model-b", VectorMath.encode(floatArrayOf(3f, 4f, 5f)))

        val rows = db.fetchRecentEmbeddings("tech", sinceDays = 30, limit = 10)
        assertEquals(1, rows.size)
        assertEquals("model-b", rows[0].model)
        val decoded = VectorMath.decode(rows[0].vector)
        assertEquals(3, decoded.size)
        assertEquals(3f, decoded[0])
        assertEquals(4f, decoded[1])
        assertEquals(5f, decoded[2])
    }

    @Test
    fun `fetchRecentEmbeddings filters by category`() {
        val techId = seed("tech", "https://a.com/tech")
        val gamingId = seed("gaming", "https://a.com/gaming")
        db.saveEmbedding(techId, "m", VectorMath.encode(floatArrayOf(1f)))
        db.saveEmbedding(gamingId, "m", VectorMath.encode(floatArrayOf(2f)))

        val techRows = db.fetchRecentEmbeddings("tech", sinceDays = 30, limit = 10)
        assertEquals(1, techRows.size)
        assertEquals("https://a.com/tech", techRows[0].link)

        val gamingRows = db.fetchRecentEmbeddings("gaming", sinceDays = 30, limit = 10)
        assertEquals(1, gamingRows.size)
        assertEquals("https://a.com/gaming", gamingRows[0].link)
    }

    @Test
    fun `fetchRecentEmbeddings filters out articles with old pubDate`() {
        val recentId = seed("tech", "https://a.com/recent", LocalDateTime.now().minusDays(1))
        val oldId = seed("tech", "https://a.com/old", LocalDateTime.now().minusDays(30))
        db.saveEmbedding(recentId, "m", VectorMath.encode(floatArrayOf(1f)))
        db.saveEmbedding(oldId, "m", VectorMath.encode(floatArrayOf(2f)))

        val rows = db.fetchRecentEmbeddings("tech", sinceDays = 7, limit = 10)
        assertEquals(1, rows.size)
        assertEquals("https://a.com/recent", rows[0].link)
    }

    @Test
    fun `fetchRecentEmbeddings respects limit and orders newest-first`() {
        val ids = (0..2).map { i ->
            seed("tech", "https://a.com/$i", LocalDateTime.now().minusHours(i.toLong()))
        }
        for (id in ids) db.saveEmbedding(id, "m", VectorMath.encode(floatArrayOf(1f)))

        val rows = db.fetchRecentEmbeddings("tech", sinceDays = 30, limit = 2)
        assertEquals(2, rows.size)
        assertEquals("https://a.com/0", rows[0].link) // newest first
        assertEquals("https://a.com/1", rows[1].link)
    }

    @Test
    fun `fetchRecentEmbeddings returns empty when limit is non-positive`() {
        val id = seed("tech", "https://a.com/1")
        db.saveEmbedding(id, "m", VectorMath.encode(floatArrayOf(1f)))
        assertTrue(db.fetchRecentEmbeddings("tech", sinceDays = 30, limit = 0).isEmpty())
    }

    @Test
    fun `fetchRecentEmbeddings surfaces article status`() {
        val id = seed("tech", "https://a.com/1")
        db.saveEmbedding(id, "m", VectorMath.encode(floatArrayOf(1f, 0f)))

        val initial = db.fetchRecentEmbeddings("tech", sinceDays = 30, limit = 10)
        assertEquals(ArticleStatus.UNPROCESSED.name, initial.single().status)

        db.markProcessed(listOf("https://a.com/1"))
        val afterProcessed = db.fetchRecentEmbeddings("tech", sinceDays = 30, limit = 10)
        assertEquals(ArticleStatus.PROCESSED.name, afterProcessed.single().status)
    }

    @Test
    fun `markDuplicate flips status and sets duplicate_of`() {
        val canonicalId = seed("tech", "https://a.com/canonical")
        val dupId = seed("tech", "https://a.com/dup")

        db.markDuplicate(dupId, canonicalId)

        // Read raw via Exposed since there is no API yet that returns the column
        transaction {
            val row = ArticlesTable.selectAll()
                .where { ArticlesTable.id eq dupId }
                .single()
            assertEquals(ArticleStatus.DUPLICATE.name, row[ArticlesTable.status])
            assertEquals(canonicalId, row[ArticlesTable.duplicateOf])
        }
    }

    @Test
    fun `markDuplicate excluded rows from the ready-for-digest pool`() {
        val canonicalId = seed("tech", "https://a.com/canonical")
        val dupId = seed("tech", "https://a.com/dup")

        db.markDuplicate(dupId, canonicalId)

        val ready = db.fetchReadyForDigestByCategory(staleTimeoutHours = 3)["tech"].orEmpty()
        assertEquals(1, ready.size)
        assertEquals("https://a.com/canonical", ready.single().link)
    }

    @Test
    fun `markDuplicate is a no-op when the article is already PROCESSED`() {
        val canonicalId = seed("tech", "https://a.com/canonical")
        val processedId = seed("tech", "https://a.com/already-sent")
        db.markProcessed(listOf("https://a.com/already-sent"))

        // We try to mark it as duplicate; method should refuse silently.
        db.markDuplicate(processedId, canonicalId)

        transaction {
            val row = ArticlesTable.selectAll()
                .where { ArticlesTable.id eq processedId }
                .single()
            assertEquals(ArticleStatus.PROCESSED.name, row[ArticlesTable.status])
            assertNull(row[ArticlesTable.duplicateOf])
        }
    }

    @Test
    fun `markDuplicate is a no-op when the article is currently PROCESSING`() {
        val canonicalId = seed("tech", "https://a.com/canonical")
        val processingId = seed("tech", "https://a.com/in-flight")
        db.markProcessing(listOf("https://a.com/in-flight"))

        db.markDuplicate(processingId, canonicalId)

        transaction {
            val row = ArticlesTable.selectAll()
                .where { ArticlesTable.id eq processingId }
                .single()
            assertEquals(ArticleStatus.PROCESSING.name, row[ArticlesTable.status])
            assertNull(row[ArticlesTable.duplicateOf])
        }
    }

    @Test
    fun `pruneOldEmbeddings deletes rows older than retention`() {
        val id = seed("tech", "https://a.com/1")
        db.saveEmbedding(id, "m", VectorMath.encode(floatArrayOf(1f)))

        // Backdate the embedding's createdAt
        transaction {
            ArticleEmbeddingsTable.update({ ArticleEmbeddingsTable.articleId eq id }) {
                it[createdAt] = LocalDateTime.now().minusDays(60)
            }
        }

        db.pruneOldEmbeddings(retentionDays = 30)

        val rows = db.fetchRecentEmbeddings("tech", sinceDays = 365, limit = 10)
        assertTrue(rows.isEmpty())
    }
}
