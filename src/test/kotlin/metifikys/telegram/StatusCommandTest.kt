package metifikys.telegram

import io.mockk.every
import io.mockk.mockk
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.db.CategoryArticleCounts
import metifikys.db.NewsDatabase
import metifikys.db.ProviderLatency
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
    fun `latency section renders placeholder when no timed calls recorded`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchProviderLatency(any()) } returns emptyList()
        val statusCmd = StatusCommand(
            config = mockk<AppConfig>(relaxed = true),
            db = db,
            errorLog = CycleErrorLog()
        )
        val text = statusCmd.build()
        assertTrue(text.contains("⏱ *LLM latency* (24h / 7d)"))
        assertTrue(text.contains("no timed calls recorded"))
    }

    @Test
    fun `latency section lists providers sorted by 24h call count with both windows`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchProviderLatency(24L) } returns listOf(
            ProviderLatency("openai", callCount = 180, avgMs = 1100.0, p95Ms = 2300),
            ProviderLatency("codexcli", callCount = 36, avgMs = 5100.0, p95Ms = 12000)
        )
        every { db.fetchProviderLatency(7L * 24L) } returns listOf(
            ProviderLatency("openai", callCount = 1240, avgMs = 1000.0, p95Ms = 2100),
            ProviderLatency("codexcli", callCount = 300, avgMs = 5000.0, p95Ms = 11000)
        )
        val statusCmd = StatusCommand(
            config = mockk<AppConfig>(relaxed = true),
            db = db,
            errorLog = CycleErrorLog()
        )
        val text = statusCmd.build()
        assertTrue(text.contains("⏱ *LLM latency* (24h / 7d)"))

        // Ordered by 24h call volume: openai (180) before codexcli (36).
        val openaiIdx = text.indexOf("openai: ")
        val codexIdx = text.indexOf("codexcli: ")
        assertTrue(openaiIdx in 0 until codexIdx, "openai row should come before codexcli")

        assertTrue(text.contains("openai: avg 1.1s p95 2.3s (180) / avg 1.0s p95 2.1s (1240)"))
        assertTrue(text.contains("codexcli: avg 5.1s p95 12.0s (36) / avg 5.0s p95 11.0s (300)"))
    }

    @Test
    fun `latency cell shows dash for a provider absent in one window`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchProviderLatency(24L) } returns emptyList()
        every { db.fetchProviderLatency(7L * 24L) } returns listOf(
            ProviderLatency("openrouter", callCount = 50, avgMs = 800.0, p95Ms = 1500)
        )
        val statusCmd = StatusCommand(
            config = mockk<AppConfig>(relaxed = true),
            db = db,
            errorLog = CycleErrorLog()
        )
        val text = statusCmd.build()
        // 24h missing → dash; avg < 1s renders in ms, p95 ≥ 1s in seconds.
        assertTrue(text.contains("openrouter: — / avg 800ms p95 1.5s (50)"))
    }

    @Test
    fun `category block shows published and blocked counts with block percentage`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchArticleStatusCounts(24L) } returns mapOf(
            "tech" to CategoryArticleCounts("tech", published = 12, blocked = 4)
        )
        val config = mockk<AppConfig>(relaxed = true)
        every { config.categories } returns mapOf("tech" to mockk<CategoryConfig>(relaxed = true))
        val text = StatusCommand(config = config, db = db, errorLog = CycleErrorLog()).build()
        // block% = 4 / (12 + 4) = 25%
        assertTrue(text.contains("published: 12 · blocked: 4 (25%)"))
    }

    @Test
    fun `category block omits percentage when nothing seen in window`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchArticleStatusCounts(24L) } returns emptyMap()
        val config = mockk<AppConfig>(relaxed = true)
        every { config.categories } returns mapOf("tech" to mockk<CategoryConfig>(relaxed = true))
        val text = StatusCommand(config = config, db = db, errorLog = CycleErrorLog()).build()
        assertTrue(text.contains("published: 0 · blocked: 0"))
        assertTrue(!text.contains("published: 0 · blocked: 0 ("))
    }
}
