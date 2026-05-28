package metifikys.db

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
}
