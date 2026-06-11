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
import metifikys.db.EventEmbeddingRow
import metifikys.db.NewsDatabase
import metifikys.model.ShortlistItem
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventSemanticAnalyzerTest {

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

    private fun item(
        eventKey: String,
        subject: String = "S-$eventKey",
        coreFact: String = "core fact for $eventKey",
        status: String = "new"
    ) = ShortlistItem(
        eventKey = eventKey,
        subject = subject,
        coreFact = coreFact,
        url = "https://a.com/$eventKey",
        status = status
    )

    private fun pastEvent(
        eventKey: String,
        vector: FloatArray,
        subject: String = "S-$eventKey",
        franchise: String = "",
        model: String = "text-embedding-3-small",
        category: String = "tech"
    ) = EventEmbeddingRow(
        category = category,
        eventKey = eventKey,
        subject = subject,
        franchise = franchise,
        model = model,
        vector = VectorMath.encode(VectorMath.l2Normalize(vector)),
        createdAt = LocalDateTime.now().minusDays(1)
    )

    @Test
    fun `eventEnabled false triggers no embed and no persist`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true, eventEnabled = false))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk(relaxed = true)

        EventSemanticAnalyzer(cfg, db, embedder).analyzeAndFilter("tech", listOf(item("e1")))

        verify(exactly = 0) { embedder.embed(any(), any()) }
        verify(exactly = 0) { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `enabled with no recent covered events embeds and persists`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(enabled = true, eventEnabled = true, model = "text-embedding-3-small")
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.fetchRecentEventEmbeddings("tech", 14L, any()) } returns emptyList()
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs

        EventSemanticAnalyzer(cfg, db, embedder).analyzeAndFilter("tech", listOf(item("e1")))

        verify(exactly = 1) { embedder.embed(any(), any()) }
        // subject + franchise are persisted alongside the vector (for the analyzer's match flags).
        verify(exactly = 1) { db.saveEventEmbedding("tech", "e1", "S-e1", "", "text-embedding-3-small", any()) }
    }

    @Test
    fun `near-duplicate against a recent covered event persists but never mutates`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(enabled = true, eventEnabled = true, eventThreshold = 0.9)
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns
            listOf(pastEvent("old-1", floatArrayOf(0.99f, 0.1f, 0f)))

        EventSemanticAnalyzer(cfg, db, embedder).analyzeAndFilter("tech", listOf(item("new-1")))

        verify(exactly = 1) { db.saveEventEmbedding("tech", "new-1", any(), any(), any(), any()) }
        // Analyze-only: the analyzer must never touch article/event state.
        verify(exactly = 0) { db.markDuplicate(any(), any()) }
        verify(exactly = 0) { db.markProcessed(any()) }
        verify(exactly = 0) { db.markUnprocessed(any()) }
    }

    @Test
    fun `mixed-model recent vectors are filtered out`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(enabled = true, eventEnabled = true, model = "text-embedding-3-small", eventThreshold = 0.5)
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns listOf(
            pastEvent("a", floatArrayOf(1f, 0f, 0f), model = "text-embedding-3-small"),
            pastEvent("b", floatArrayOf(1f, 0f, 0f), model = "text-embedding-3-large")
        )

        EventSemanticAnalyzer(cfg, db, embedder).analyzeAndFilter("tech", listOf(item("new-1")))

        // Should not throw despite the large-model candidate; one embed call, one persist.
        verify(exactly = 1) { embedder.embed(any(), any()) }
        verify(exactly = 1) { db.saveEventEmbedding("tech", "new-1", any(), any(), any(), any()) }
    }

    @Test
    fun `candidate set excludes the current shortlist's own event keys`() {
        val cfg = appConfig(
            "tech",
            SemanticDedupConfig(enabled = true, eventEnabled = true, eventThreshold = 0.5)
        )
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs
        // The only "recent" row shares the current event's key — it must be excluded so the
        // event never matches its own prior persistence. No crash; still persists.
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns
            listOf(pastEvent("same-key", floatArrayOf(1f, 0f, 0f)))

        EventSemanticAnalyzer(cfg, db, embedder).analyzeAndFilter("tech", listOf(item("same-key")))

        verify(exactly = 1) { db.saveEventEmbedding("tech", "same-key", any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate event keys within a shortlist are embedded once`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true, eventEnabled = true))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        val capturedTexts = slot<List<String>>()
        every { embedder.embed(capture(capturedTexts), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns emptyList()
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs

        EventSemanticAnalyzer(cfg, db, embedder)
            .analyzeAndFilter("tech", listOf(item("dup", status = "new"), item("dup", status = "meaningful_update")))

        verify(exactly = 1) { embedder.embed(any(), any()) }
        assertTrue(capturedTexts.captured.size == 1, "duplicate event_key should collapse to one embed input")
    }

    @Test
    fun `embedder failure is swallowed and nothing is persisted`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true, eventEnabled = true))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        every { embedder.embed(any(), any()) } throws RuntimeException("network down")

        // Must not throw — the analyzer is unbreakable by contract.
        EventSemanticAnalyzer(cfg, db, embedder).analyzeAndFilter("tech", listOf(item("e1")))

        verify(exactly = 0) { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `empty shortlist is a no-op`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true, eventEnabled = true))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk(relaxed = true)

        EventSemanticAnalyzer(cfg, db, embedder).analyzeAndFilter("tech", emptyList())

        verify(exactly = 0) { embedder.embed(any(), any()) }
        verify(exactly = 0) { db.fetchRecentEventEmbeddings(any(), any(), any()) }
    }

    @Test
    fun `oversized core fact is truncated before reaching the embedder`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true, eventEnabled = true))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()

        val capturedTexts = slot<List<String>>()
        every { embedder.embed(capture(capturedTexts), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns emptyList()
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs

        val huge = item("big", subject = "Big event", coreFact = "x".repeat(50_000))
        EventSemanticAnalyzer(cfg, db, embedder).analyzeAndFilter("tech", listOf(huge))

        val sent = capturedTexts.captured.single()
        assertTrue(sent.length <= 6000, "embed input should be capped, was ${sent.length}")
        assertTrue(sent.startsWith("Big event"), "subject must survive truncation")
    }

    // ── Hard filter (eventHardThreshold) ─────────────────────────────────────

    private fun hardCfg() = appConfig(
        "tech",
        SemanticDedupConfig(
            enabled = true, eventEnabled = true,
            eventThreshold = 0.72, eventHardThreshold = 0.80
        )
    )

    @Test
    fun `null eventHardThreshold never drops even at cosine 1`() {
        val cfg = appConfig("tech", SemanticDedupConfig(enabled = true, eventEnabled = true, eventThreshold = 0.72))
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns
            listOf(pastEvent("covered", floatArrayOf(1f, 0f, 0f)))

        val result = EventSemanticAnalyzer(cfg, db, embedder)
            .analyzeAndFilter("tech", listOf(item("dup", status = "new")))

        assertEquals(1, result.size)  // logged HIT but log-only → kept
        verify(exactly = 1) { db.saveEventEmbedding("tech", "dup", any(), any(), any(), any()) }
    }

    @Test
    fun `status new above hard threshold is dropped and not persisted`() {
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns
            listOf(pastEvent("covered", floatArrayOf(1f, 0f, 0f)))  // cosine 1.0 >= hard 0.80

        val result = EventSemanticAnalyzer(hardCfg(), db, embedder)
            .analyzeAndFilter("tech", listOf(item("new-dup", status = "new")))

        assertTrue(result.isEmpty(), "hard-rejected new event must be dropped")
        // Rejected events are never persisted (would pollute the candidate set).
        verify(exactly = 0) { db.saveEventEmbedding("tech", "new-dup", any(), any(), any(), any()) }
    }

    @Test
    fun `meaningful_update above hard threshold is kept`() {
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns
            listOf(pastEvent("covered", floatArrayOf(1f, 0f, 0f)))

        val result = EventSemanticAnalyzer(hardCfg(), db, embedder)
            .analyzeAndFilter("tech", listOf(item("upd", status = "meaningful_update")))

        assertEquals(1, result.size)  // follow-ups are intentional re-coverage
        verify(exactly = 1) { db.saveEventEmbedding("tech", "upd", any(), any(), any(), any()) }
    }

    @Test
    fun `HIT below hard threshold is kept and persisted`() {
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()
        every { embedder.embed(any(), any()) } returns listOf(floatArrayOf(1f, 0f, 0f))
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs
        // cosine ~0.75: above eventThreshold (0.72, HIT) but below eventHardThreshold (0.80).
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns
            listOf(pastEvent("covered", floatArrayOf(0.75f, 0.66f, 0f)))

        val result = EventSemanticAnalyzer(hardCfg(), db, embedder)
            .analyzeAndFilter("tech", listOf(item("midhit", status = "new")))

        assertEquals(1, result.size)
        verify(exactly = 1) { db.saveEventEmbedding("tech", "midhit", any(), any(), any(), any()) }
    }

    @Test
    fun `all events rejected returns empty list`() {
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()
        every { embedder.embed(any(), any()) } returns
            listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 0f, 0f))
        every { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) } just Runs
        every { db.fetchRecentEventEmbeddings(any(), any(), any()) } returns
            listOf(pastEvent("covered", floatArrayOf(1f, 0f, 0f)))

        val result = EventSemanticAnalyzer(hardCfg(), db, embedder)
            .analyzeAndFilter("tech", listOf(item("d1", status = "new"), item("d2", status = "new")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `embed failure with hard filter set returns input unchanged (fail-open)`() {
        val db: NewsDatabase = mockk(relaxed = true)
        val embedder: Embedder = mockk()
        every { embedder.embed(any(), any()) } throws RuntimeException("network down")

        val input = listOf(item("e1", status = "new"))
        val result = EventSemanticAnalyzer(hardCfg(), db, embedder).analyzeAndFilter("tech", input)

        assertEquals(input, result)  // a transient error must never empty a digest
        verify(exactly = 0) { db.saveEventEmbedding(any(), any(), any(), any(), any(), any()) }
    }
}
