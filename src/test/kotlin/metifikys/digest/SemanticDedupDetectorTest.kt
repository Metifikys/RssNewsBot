package metifikys.digest

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.ai.Embedder
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.SemanticDedupConfig
import metifikys.config.TelegramConfig
import metifikys.db.EmbeddingRow
import metifikys.db.NewsDatabase
import metifikys.model.Article
import metifikys.model.ArticleStatus
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticDedupDetectorTest {

    private fun appConfig(catName: String, sd: SemanticDedupConfig?): AppConfig =
        AppConfig(
            telegram = TelegramConfig(botToken = "x"),
            openai = OpenAIConfig(apiKey = "x", model = "m", batchModel = "m"),
            database = DatabaseConfig(path = "ignored"),
            scheduler = SchedulerConfig(intervalMinutes = 60),
            categories = mapOf(
                catName to CategoryConfig(
                    emoji = ":x:",
                    feeds = listOf(FeedConfig("https://example.com/rss")),
                    channelId = "@ch",
                    semanticDedup = sd
                )
            )
        )

    private fun article(link: String, category: String = "tech", title: String = "T-$link") =
        Article(
            category = category,
            title = title,
            link = link,
            description = "body for $link",
            pubDate = LocalDateTime.now()
        )

    @Test
    fun `disabled category triggers no embed and no log`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = false))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk(relaxed = true)

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/1")))

        verify(exactly = 0) { embedder.embed(any(), any()) }
        verify(exactly = 0) { db.saveEmbedding(any(), any(), any()) }
    }

    @Test
    fun `enabled category with no recent vectors embeds and persists`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true, model = "text-embedding-3-small"))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(listOf("https://a.com/1")) } returns mapOf("https://a.com/1" to 42)
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEmbedding(any(), any(), any()) } just Runs
        every { db.fetchRecentEmbeddings("tech", 14L, any()) } returns emptyList()

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/1")))

        verify(exactly = 1) { embedder.embed(any(), any()) }
        verify(exactly = 1) {
            db.saveEmbedding(42, "text-embedding-3-small", any())
        }
    }

    private fun pastRow(
        articleId: Int,
        vector: FloatArray,
        status: ArticleStatus = ArticleStatus.PROCESSED,
        model: String = "text-embedding-3-small",
        category: String = "tech",
        link: String = "https://a.com/old-$articleId",
        title: String = "Older article $articleId"
    ): EmbeddingRow = EmbeddingRow(
        articleId = articleId,
        link = link,
        title = title,
        category = category,
        model = model,
        vector = VectorMath.encode(VectorMath.l2Normalize(vector)),
        pubDate = LocalDateTime.now().minusDays(1),
        status = status.name
    )

    @Test
    fun `near-duplicate against a recent vector is logged as HIT`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(enabled = true, threshold = 0.9, model = "text-embedding-3-small")
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(listOf("https://a.com/new")) } returns
            mapOf("https://a.com/new" to 99)
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEmbedding(any(), any(), any()) } just Runs
        every { db.fetchRecentEmbeddings("tech", any(), any()) } returns
            listOf(pastRow(1, floatArrayOf(0.99f, 0.1f, 0f)))

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/new")))

        verify { db.saveEmbedding(99, "text-embedding-3-small", any()) }
        verify { db.fetchRecentEmbeddings("tech", 14L, any()) }
        // No hard filter configured → no markDuplicate even though we crossed `threshold`.
        verify(exactly = 0) { db.markDuplicate(any(), any()) }
    }

    @Test
    fun `unrelated past vector does not produce HIT`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(enabled = true, threshold = 0.95)
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(any()) } returns mapOf("https://a.com/new" to 99)
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEmbedding(any(), any(), any()) } just Runs
        every { db.fetchRecentEmbeddings(any(), any(), any()) } returns
            listOf(pastRow(1, floatArrayOf(0f, 1f, 0f)))

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/new")))

        verify(exactly = 0) { db.markProcessed(any()) }
        verify(exactly = 0) { db.markUnprocessed(any()) }
        verify(exactly = 0) { db.markProcessing(any()) }
        verify(exactly = 0) { db.markDuplicate(any(), any()) }
    }

    @Test
    fun `mixed-model recent vectors are filtered out`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(enabled = true, model = "text-embedding-3-small", threshold = 0.5)
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(any()) } returns mapOf("https://a.com/new" to 99)
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEmbedding(any(), any(), any()) } just Runs
        every { db.fetchRecentEmbeddings(any(), any(), any()) } returns listOf(
            pastRow(1, floatArrayOf(1f, 0f, 0f), model = "text-embedding-3-small"),
            pastRow(2, floatArrayOf(1f, 0f, 0f), model = "text-embedding-3-large")
        )

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/new")))

        verify(exactly = 1) { embedder.embed(any(), any()) }
    }

    @Test
    fun `hard-filter rejects article when top-1 is PROCESSED and above hardThreshold`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(
                enabled = true,
                threshold = 0.7,
                hardThreshold = 0.85
            )
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(any()) } returns mapOf("https://a.com/new" to 99)
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEmbedding(any(), any(), any()) } just Runs
        // Top-1 is a near-perfect match AND it's PROCESSED → hard reject.
        every { db.fetchRecentEmbeddings(any(), any(), any()) } returns listOf(
            pastRow(7, floatArrayOf(1f, 0f, 0f), status = ArticleStatus.PROCESSED)
        )

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/new")))

        verify(exactly = 1) { db.markDuplicate(99, 7) }
    }

    @Test
    fun `hard-filter does NOT reject when top-1 is UNPROCESSED`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(
                enabled = true,
                threshold = 0.7,
                hardThreshold = 0.85
            )
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(any()) } returns mapOf("https://a.com/new" to 99)
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEmbedding(any(), any(), any()) } just Runs
        // Near-perfect cosine but status is UNPROCESSED → LLM must own this dedup.
        every { db.fetchRecentEmbeddings(any(), any(), any()) } returns listOf(
            pastRow(7, floatArrayOf(1f, 0f, 0f), status = ArticleStatus.UNPROCESSED)
        )

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/new")))

        verify(exactly = 0) { db.markDuplicate(any(), any()) }
    }

    @Test
    fun `hard-filter does NOT reject when top-1 is below hardThreshold`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(
                enabled = true,
                threshold = 0.5,
                hardThreshold = 0.9
            )
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(any()) } returns mapOf("https://a.com/new" to 99)
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEmbedding(any(), any(), any()) } just Runs
        // Status is PROCESSED but cosine ~0.74 — below hardThreshold (0.9) so no reject.
        every { db.fetchRecentEmbeddings(any(), any(), any()) } returns listOf(
            pastRow(7, floatArrayOf(0.74f, 0.67f, 0f), status = ArticleStatus.PROCESSED)
        )

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/new")))

        verify(exactly = 0) { db.markDuplicate(any(), any()) }
    }

    @Test
    fun `hard-filter is a no-op when hardThreshold is null`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(
                enabled = true,
                threshold = 0.5,
                hardThreshold = null
            )
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(any()) } returns mapOf("https://a.com/new" to 99)
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEmbedding(any(), any(), any()) } just Runs
        every { db.fetchRecentEmbeddings(any(), any(), any()) } returns listOf(
            pastRow(7, floatArrayOf(1f, 0f, 0f), status = ArticleStatus.PROCESSED)
        )

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/new")))

        verify(exactly = 0) { db.markDuplicate(any(), any()) }
    }

    @Test
    fun `markDuplicate failure is swallowed and cycle continues`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(enabled = true, threshold = 0.5, hardThreshold = 0.7)
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(any()) } returns mapOf("https://a.com/new" to 99)
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEmbedding(any(), any(), any()) } just Runs
        every { db.fetchRecentEmbeddings(any(), any(), any()) } returns listOf(
            pastRow(7, floatArrayOf(1f, 0f, 0f), status = ArticleStatus.PROCESSED)
        )
        every { db.markDuplicate(any(), any()) } throws RuntimeException("db locked")

        val detector = SemanticDedupDetector(cfg, db, embedder)
        // Must not throw — the cycle relies on the detector being unbreakable.
        detector.detectAndLog(listOf(article("https://a.com/new")))

        verify(exactly = 1) { db.markDuplicate(99, 7) } // we tried once
    }

    @Test
    fun `embedder failure on one category does not break detector`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(enabled = true)
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { db.fetchArticleIdsByLinks(any()) } returns mapOf("https://a.com/1" to 7)
        every { embedder.embed(any(), any()) } throws RuntimeException("network down")

        val detector = SemanticDedupDetector(cfg, db, embedder)
        // Must not throw — the cycle relies on the detector being unbreakable.
        detector.detectAndLog(listOf(article("https://a.com/1")))

        // Nothing was persisted because embedding failed.
        verify(exactly = 0) { db.saveEmbedding(any(), any(), any()) }
    }

    @Test
    fun `articles without a resolvable id are skipped`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk(relaxed = true)

        every { db.fetchArticleIdsByLinks(any()) } returns emptyMap()

        val detector = SemanticDedupDetector(cfg, db, embedder)
        detector.detectAndLog(listOf(article("https://a.com/orphan")))

        verify(exactly = 0) { embedder.embed(any(), any()) }
        verify(exactly = 0) { db.saveEmbedding(any(), any(), any()) }
    }

    @Test
    fun `empty input is a no-op`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk(relaxed = true)

        SemanticDedupDetector(cfg, db, embedder).detectAndLog(emptyList())

        verify(exactly = 0) { db.fetchArticleIdsByLinks(any()) }
        verify(exactly = 0) { embedder.embed(any(), any()) }
    }

    @Test
    fun `oversized article body is truncated before reaching the embedder`() {
        // Regression: one article exceeding OpenAI's 8192-token per-input cap rejected
        // the whole batch (input[124] error), causing the category to be skipped.
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        val longArticle = Article(
            category = "tech",
            title = "Huge article",
            link = "https://a.com/huge",
            description = "ignored body",
            summary = "x".repeat(50_000),
            pubDate = LocalDateTime.now()
        )

        val capturedTexts = slot<List<String>>()
        every { db.fetchArticleIdsByLinks(any()) } returns mapOf("https://a.com/huge" to 1)
        every { embedder.embed(capture(capturedTexts), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.fetchRecentEmbeddings(any(), any(), any()) } returns emptyList()

        SemanticDedupDetector(cfg, db, embedder).detectAndLog(listOf(longArticle))

        val sent = capturedTexts.captured.single()
        assertTrue(sent.length <= 6000, "embed input should be capped, was ${sent.length}")
        assertTrue(sent.startsWith("Huge article"), "title must survive truncation")
    }

    @Test
    fun `assertion sanity check on test helpers`() {
        // Sanity: encode/decode a normalized vector survives the round-trip we use elsewhere.
        val v = VectorMath.l2Normalize(floatArrayOf(1f, 1f, 1f))
        val back = VectorMath.decode(VectorMath.encode(v))
        assertEquals(v.size, back.size)
        assertTrue(VectorMath.cosine(v, back) > 0.999)
    }
}
