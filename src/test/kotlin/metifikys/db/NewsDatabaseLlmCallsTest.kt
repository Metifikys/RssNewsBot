package metifikys.db

import metifikys.model.Article
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NewsDatabaseLlmCallsTest {

    @Test
    fun `insertLlmCall persists row and fetchLlmCallStats aggregates by provider and category`(
        @TempDir tmp: Path
    ) {
        val db = NewsDatabase(tmp.resolve("llm.db").toString())
        val now = LocalDateTime.now()

        db.insertLlmCall("openai", "gpt-4o-mini", "Tech", "RENDER", 1000, 500, 0.001, now)
        db.insertLlmCall("openai", "gpt-4o-mini", "Tech", "RENDER", 2000, 1000, 0.002, now.minusMinutes(30))
        db.insertLlmCall("anthropic", "claude-haiku-4-5", "News", "EXTRACT", 500, 200, 0.0005, now.minusHours(2))
        db.insertLlmCall("openai", "gpt-4o-mini", "News", "SUMMARIZE", 300, 100, 0.0001, now.minusHours(3))

        val day = db.fetchLlmCallStats(24).sortedBy { (it.provider + (it.category ?: "")) }

        assertEquals(3, day.size)
        val openaiTech = day.first { it.provider == "openai" && it.category == "Tech" }
        assertEquals(2L, openaiTech.callCount)
        assertEquals(3000L, openaiTech.promptTokens)
        assertEquals(1500L, openaiTech.completionTokens)
        assertEquals(0.003, openaiTech.totalCostUsd, 1e-9)

        val openaiNews = day.first { it.provider == "openai" && it.category == "News" }
        assertEquals(1L, openaiNews.callCount)

        val anthropicNews = day.first { it.provider == "anthropic" && it.category == "News" }
        assertEquals(1L, anthropicNews.callCount)
        assertEquals(0.0005, anthropicNews.totalCostUsd, 1e-9)
    }

    @Test
    fun `fetchLlmCallStats with short window excludes older rows`(@TempDir tmp: Path) {
        val db = NewsDatabase(tmp.resolve("llm-window.db").toString())
        val now = LocalDateTime.now()

        db.insertLlmCall("openai", "m", "C", "RENDER", 100, 50, 0.0, now)
        db.insertLlmCall("openai", "m", "C", "RENDER", 200, 100, 0.0, now.minusHours(48))

        val day = db.fetchLlmCallStats(24)
        assertEquals(1, day.size)
        assertEquals(1L, day[0].callCount)

        val week = db.fetchLlmCallStats(7 * 24)
        assertEquals(1, week.size)
        assertEquals(2L, week[0].callCount)
    }

    @Test
    fun `null category groups separately`(@TempDir tmp: Path) {
        val db = NewsDatabase(tmp.resolve("llm-null.db").toString())
        val now = LocalDateTime.now()

        db.insertLlmCall("openai", "m", null, "BATCH", 100, 50, 0.0, now)
        db.insertLlmCall("openai", "m", "Tech", "RENDER", 100, 50, 0.0, now)

        val stats = db.fetchLlmCallStats(24)
        assertEquals(2, stats.size)
        assertNotNull(stats.firstOrNull { it.category == null })
        assertNotNull(stats.firstOrNull { it.category == "Tech" })
    }

    @Test
    fun `deleteOldLlmCalls prunes rows older than retention window`(@TempDir tmp: Path) {
        val db = NewsDatabase(tmp.resolve("llm-prune.db").toString())
        val now = LocalDateTime.now()

        db.insertLlmCall("openai", "m", "C", "RENDER", 1, 1, 0.0, now)
        db.insertLlmCall("openai", "m", "C", "RENDER", 1, 1, 0.0, now.minusDays(40))

        db.deleteOldLlmCalls(days = 30)

        val all = db.fetchLlmCallStats(365 * 24)
        assertEquals(1, all.size)
        assertTrue(all[0].callCount == 1L)
    }

    @Test
    fun `empty table yields empty stat list`(@TempDir tmp: Path) {
        val db = NewsDatabase(tmp.resolve("llm-empty.db").toString())
        val stats = db.fetchLlmCallStats(24)
        assertTrue(stats.isEmpty())
    }

    @Test
    fun `fetchProviderLatency averages and p95s over timed calls, excluding null durations`(
        @TempDir tmp: Path
    ) {
        val db = NewsDatabase(tmp.resolve("llm-lat.db").toString())
        val now = LocalDateTime.now()

        // openai: five timed calls 100..500ms, plus one batch row (null) that must be excluded.
        listOf(100L, 200L, 300L, 400L, 500L).forEach { d ->
            db.insertLlmCall("openai", "m", "C", "RENDER", 1, 1, 0.0, now, d)
        }
        db.insertLlmCall("openai", "m", "C", "BATCH", 1, 1, 0.0, now, null)
        db.insertLlmCall("codexcli", "m", "C", "RENDER", 1, 1, 0.0, now, 1000L)

        val lat = db.fetchProviderLatency(24)

        // Sorted by call count descending: openai (5 timed) before codexcli (1).
        assertEquals(2, lat.size)
        assertEquals("openai", lat[0].provider)
        assertEquals(5L, lat[0].callCount)            // null-duration batch row excluded
        assertEquals(300.0, lat[0].avgMs, 1e-9)
        assertEquals(500L, lat[0].p95Ms)             // nearest-rank: ceil(0.95*5)=5 -> 5th value
        assertEquals("codexcli", lat[1].provider)
        assertEquals(1L, lat[1].callCount)
        assertEquals(1000L, lat[1].p95Ms)
    }

    @Test
    fun `fetchProviderLatency excludes rows outside the window`(@TempDir tmp: Path) {
        val db = NewsDatabase(tmp.resolve("llm-lat-window.db").toString())
        val now = LocalDateTime.now()
        db.insertLlmCall("openai", "m", "C", "RENDER", 1, 1, 0.0, now, 100L)
        db.insertLlmCall("openai", "m", "C", "RENDER", 1, 1, 0.0, now.minusHours(48), 999L)

        val day = db.fetchProviderLatency(24)
        assertEquals(1, day.size)
        assertEquals(1L, day[0].callCount)
        assertEquals(100.0, day[0].avgMs, 1e-9)
    }

    @Test
    fun `fetchArticleStatusCounts tallies published and blocked per category`(@TempDir tmp: Path) {
        val db = NewsDatabase(tmp.resolve("article-counts.db").toString())
        val now = LocalDateTime.now()

        db.insertArticles(
            listOf(
                Article("tech", "t1", "l1", "d", now),
                Article("tech", "t2", "l2", "d", now),
                Article("tech", "t3", "l3", "d", now),   // stays UNPROCESSED
                Article("tech", "t4", "l4", "d", now),    // will be DUPLICATE
                Article("gaming", "g1", "lg1", "d", now)
            )
        )
        db.markProcessed(listOf("l1", "l2", "lg1"))
        val ids = db.fetchArticleIdsByLinks(listOf("l1", "l4"))
        db.markDuplicate(ids.getValue("l4"), ids.getValue("l1"))

        val counts = db.fetchArticleStatusCounts(24)

        val tech = counts.getValue("tech")
        assertEquals(2L, tech.published)   // l1, l2
        assertEquals(1L, tech.blocked)     // l4
        val gaming = counts.getValue("gaming")
        assertEquals(1L, gaming.published)
        assertEquals(0L, gaming.blocked)
    }

    @Test
    fun `fetchArticleStatusCounts excludes articles published before the window`(@TempDir tmp: Path) {
        val db = NewsDatabase(tmp.resolve("article-counts-window.db").toString())
        val now = LocalDateTime.now()
        db.insertArticles(
            listOf(
                Article("tech", "t1", "l1", "d", now),
                Article("tech", "t2", "l2", "d", now.minusHours(48))
            )
        )
        db.markProcessed(listOf("l1", "l2"))

        val counts = db.fetchArticleStatusCounts(24)
        assertEquals(1L, counts.getValue("tech").published)  // only the in-window row
    }
}
