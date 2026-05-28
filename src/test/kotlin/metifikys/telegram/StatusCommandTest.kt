package metifikys.telegram

import io.mockk.every
import io.mockk.mockk
import metifikys.config.AppConfig
import metifikys.db.LlmCostStat
import metifikys.db.NewsDatabase
import metifikys.digest.CycleErrorLog
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusCommandTest {

    private val cmd = StatusCommand(
        config = mockk<AppConfig>(relaxed = true),
        db = mockk<NewsDatabase>(relaxed = true),
        errorLog = CycleErrorLog()
    )

    @Test
    fun `formatAge under one minute renders less-than-one`() {
        assertEquals("<1 min", cmd.formatAge(Duration.ofSeconds(0)))
        assertEquals("<1 min", cmd.formatAge(Duration.ofSeconds(45)))
    }

    @Test
    fun `formatAge under one hour renders minutes only`() {
        assertEquals("1 min", cmd.formatAge(Duration.ofMinutes(1)))
        assertEquals("27 min", cmd.formatAge(Duration.ofMinutes(27)))
        assertEquals("59 min", cmd.formatAge(Duration.ofMinutes(59)))
    }

    @Test
    fun `formatAge under one day renders hours and minutes`() {
        assertEquals("1h", cmd.formatAge(Duration.ofMinutes(60)))
        assertEquals("1h 30m", cmd.formatAge(Duration.ofMinutes(90)))
        assertEquals("13h 29m", cmd.formatAge(Duration.ofMinutes(13 * 60 + 29)))
        assertEquals("23h 59m", cmd.formatAge(Duration.ofMinutes(24 * 60 - 1)))
    }

    @Test
    fun `formatAge of one day or more renders days only`() {
        assertEquals("1d", cmd.formatAge(Duration.ofHours(24)))
        assertEquals("1d", cmd.formatAge(Duration.ofHours(25)))
        assertEquals("3d", cmd.formatAge(Duration.ofDays(3)))
    }

    @Test
    fun `formatAge clamps negative durations to zero`() {
        assertEquals("<1 min", cmd.formatAge(Duration.ofMinutes(-5)))
    }

    @Test
    fun `cost section renders zero totals when no calls recorded`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchLlmCallStats(any()) } returns emptyList()
        val statusCmd = StatusCommand(
            config = mockk<AppConfig>(relaxed = true),
            db = db,
            errorLog = CycleErrorLog()
        )
        val text = statusCmd.build()
        assertTrue(text.contains("💰 *LLM cost* (24h / 7d)"))
        assertTrue(text.contains("total: \$0.00 / \$0.00"))
    }

    @Test
    fun `cost section lists rows sorted by 24h cost descending`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchLlmCallStats(24L) } returns listOf(
            LlmCostStat("openai", "Tech", totalCostUsd = 0.05, promptTokens = 10_000, completionTokens = 5_000, callCount = 7),
            LlmCostStat("anthropic", "News", totalCostUsd = 0.30, promptTokens = 50_000, completionTokens = 20_000, callCount = 3),
            LlmCostStat("openai", "News", totalCostUsd = 0.10, promptTokens = 20_000, completionTokens = 8_000, callCount = 5)
        )
        every { db.fetchLlmCallStats(7L * 24L) } returns listOf(
            LlmCostStat("openai", "Tech", 0.50, 100_000, 50_000, 70),
            LlmCostStat("anthropic", "News", 1.20, 200_000, 80_000, 30),
            LlmCostStat("openai", "News", 0.40, 80_000, 32_000, 25)
        )
        val statusCmd = StatusCommand(
            config = mockk<AppConfig>(relaxed = true),
            db = db,
            errorLog = CycleErrorLog()
        )
        val text = statusCmd.build()
        assertTrue(text.contains("💰 *LLM cost* (24h / 7d)"))
        assertTrue(text.contains("total: \$0.45 / \$2.10"))

        val anthropicIdx = text.indexOf("anthropic · News")
        val openaiNewsIdx = text.indexOf("openai · News")
        val openaiTechIdx = text.indexOf("openai · Tech")
        assertTrue(anthropicIdx in 0 until openaiNewsIdx, "anthropic row should come before openai/News")
        assertTrue(openaiNewsIdx in 0 until openaiTechIdx, "openai/News should come before openai/Tech")

        assertTrue(text.contains("\$0.30 / \$1.20"))
        assertTrue(text.contains("10.0k in"))
        assertTrue(text.contains("3 calls"))
    }

    @Test
    fun `cost row omits dollar columns when both windows are zero`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchLlmCallStats(24L) } returns listOf(
            LlmCostStat("openrouter", "tech", totalCostUsd = 0.0, promptTokens = 29_500, completionTokens = 7_500, callCount = 80)
        )
        every { db.fetchLlmCallStats(7L * 24L) } returns listOf(
            LlmCostStat("openrouter", "tech", totalCostUsd = 0.0, promptTokens = 29_500, completionTokens = 7_500, callCount = 80)
        )
        val statusCmd = StatusCommand(
            config = mockk<AppConfig>(relaxed = true),
            db = db,
            errorLog = CycleErrorLog()
        )
        val text = statusCmd.build()
        // Zero-cost row collapses to "provider · category: (tokens in / out, N calls)"
        assertTrue(text.contains("openrouter · tech: (29.5k in / 7.5k out, 80 calls)"))
        assertTrue(!text.contains("openrouter · tech: \$0.00"))
    }
}
