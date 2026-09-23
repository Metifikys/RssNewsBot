package metifikys.telegram

import io.mockk.every
import io.mockk.mockk
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.FeedConfig
import metifikys.config.FeedHealthConfig
import metifikys.db.FeedStatsRow
import java.time.LocalDateTime
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
        assertTrue(text.contains("⏱ *LLM latency* (avg / p95 · req · 24h / 7d)"))
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
        assertTrue(text.contains("⏱ *LLM latency* (avg / p95 · req · 24h / 7d)"))

        // Ordered by 24h call volume: openai (180) before codexcli (36).
        val openaiIdx = text.indexOf("openai: ")
        val codexIdx = text.indexOf("codexcli: ")
        assertTrue(openaiIdx in 0 until codexIdx, "openai row should come before codexcli")

        // Two lines per provider: first 24h, second (indented) 7d.
        assertTrue(text.contains("openai: 1.1s / 2.3s · 180\n  1.0s / 2.1s · 1240"))
        assertTrue(text.contains("codexcli: 5.1s / 12.0s · 36\n  5.0s / 11.0s · 300"))
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
        // 24h missing → dash on first line; avg < 1s renders in ms, p95 ≥ 1s in seconds.
        assertTrue(text.contains("openrouter: —\n  800ms / 1.5s · 50"))
    }

    @Test
    fun `category block shows published articles and blocked counts with percentages`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchArticleStatusCounts(24L) } returns mapOf(
            "tech" to CategoryArticleCounts("tech", articles = 12, blocked = 4)
        )
        every { db.fetchPublishedTopicCounts(24L) } returns mapOf("tech" to 7L)
        val config = mockk<AppConfig>(relaxed = true)
        every { config.categories } returns mapOf("tech" to mockk<CategoryConfig>(relaxed = true))
        val text = StatusCommand(config = config, db = db, errorLog = CycleErrorLog()).build()
        // denom = articles + blocked = 16; ✅% = 7/16 = 43%, ⛔% = 4/16 = 25%
        assertTrue(text.contains("✅ 7 (43%) · 📰 12 · ⛔ 4 (25%)"))
    }

    @Test
    fun `category block omits percentages when nothing seen in window`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchArticleStatusCounts(24L) } returns emptyMap()
        every { db.fetchPublishedTopicCounts(24L) } returns emptyMap()
        val config = mockk<AppConfig>(relaxed = true)
        every { config.categories } returns mapOf("tech" to mockk<CategoryConfig>(relaxed = true))
        val text = StatusCommand(config = config, db = db, errorLog = CycleErrorLog()).build()
        assertTrue(text.contains("✅ 0 · 📰 0 · ⛔ 0"))
        assertTrue(!text.contains("✅ 0 ("))
    }

    @Test
    fun `variants section compares the arms per category and is absent without tagged posts`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchVariantPosts(any()) } returns listOf(
            metifikys.db.VariantPost("tech", "control", emptyList()),
            metifikys.db.VariantPost("tech", "persona", listOf(metifikys.db.ReactionCount("👍", 2)))
        )
        val config = mockk<AppConfig>(relaxed = true)
        every { config.categories } returns mapOf("tech" to mockk<CategoryConfig>(relaxed = true))
        every { config.feedback } returns metifikys.config.FeedbackConfig()
        val text = StatusCommand(config = config, db = db, errorLog = CycleErrorLog()).build()
        assertTrue(text.contains("🧪 *Variants* (30d)\n  tech: persona 1 · 2.0/post · 100% reacted · 100% 👍+ | control 1 · 0.0/post · 0% reacted"), text)

        every { db.fetchVariantPosts(any()) } returns emptyList()
        assertTrue("Variants" !in StatusCommand(config = config, db = db, errorLog = CycleErrorLog()).build())
    }

    private fun feedHealthCmd(enabled: Boolean, db: NewsDatabase): StatusCommand {
        val config = mockk<AppConfig>(relaxed = true)
        every { config.feedHealth } returns FeedHealthConfig(enabled = enabled)
        every { config.categories } returns mapOf(
            "tech" to CategoryConfig(emoji = "x", feeds = listOf(FeedConfig("https://dead.example/feed")), channelId = "@t")
        )
        return StatusCommand(config = config, db = db, errorLog = CycleErrorLog())
    }

    private fun feedDb(): NewsDatabase {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchFeedTrackingSince() } returns LocalDateTime.now().minusDays(60)
        every { db.fetchFeedLastNew() } returns emptyMap()
        every { db.fetchFeedStats() } returns listOf(
            FeedStatsRow("https://dead.example/feed", "tech", LocalDateTime.now(), null, 12, 12, 12, "Got HTTP 404", null)
        )
        return db
    }

    @Test
    fun `feeds section lists a dead feed when feedHealth is on`() {
        val text = feedHealthCmd(true, feedDb()).build()
        assertTrue(text.contains("📡 *Feeds* (14d): tech 0 ok · 1 💀"), text)
        assertTrue(text.contains("💀 tech dead.example/feed — no success yet, 12 failed in a row (Got HTTP 404)"), text)
    }

    @Test
    fun `feeds section is absent when off and dropped when its query fails`() {
        assertTrue("Feeds" !in feedHealthCmd(false, feedDb()).build())
        val broken = feedDb()
        every { broken.fetchFeedArticleOutcomes(any()) } throws IllegalStateException("boom")
        val text = feedHealthCmd(true, broken).build()
        assertTrue("Feeds" !in text)
        assertTrue(text.contains("📊 *Status*"))
    }

    @Test
    fun `telegram 429 counter is shown only when wired`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        val config = mockk<AppConfig>(relaxed = true)
        val withCounter = StatusCommand(config = config, db = db, errorLog = CycleErrorLog(), telegramRateLimits = { 3 }).build()
        assertTrue(withCounter.contains("📨 Telegram 429 (24h): 3"), withCounter)
        val without = StatusCommand(config = config, db = db, errorLog = CycleErrorLog()).build()
        assertTrue("Telegram 429" !in without)
    }

    // ── 🥇 Хто першим (storyIntel) ──────────────────────────────────────────

    private fun firstSourceStatus(storyIntel: Boolean, db: NewsDatabase): StatusCommand {
        val config = mockk<AppConfig>(relaxed = true)
        every { config.storyIntel } returns metifikys.config.StoryIntelConfig(enabled = storyIntel)
        every { config.weekly } returns metifikys.config.WeeklyConfig(
            sourceLeaderboard = metifikys.config.SourceLeaderboardConfig(lookbackDays = 30, minEvents = 2, top = 2)
        )
        every { config.categories } returns mapOf(
            "gaming" to CategoryConfig(emoji = "x", feeds = listOf(FeedConfig("https://a.example/feed")), channelId = "@g")
        )
        return StatusCommand(config = config, db = db, errorLog = CycleErrorLog())
    }

    private fun storyRows(): List<metifikys.db.EventArticleRow> {
        val t0 = LocalDateTime.of(2026, 9, 1, 12, 0)
        var id = 0
        fun r(key: String, domain: String, min: Long) = metifikys.db.EventArticleRow(
            "gaming", ++id, key, "https://$domain/$id", domain, t0.plusMinutes(min), null,
            metifikys.db.EventArticleRole.STEP1, "new", null, t0
        )
        return listOf(
            r("e1", "fast.com", 0), r("e1", "mid.com", 30), r("e1", "slow.com", 90),
            r("e2", "fast.com", 0), r("e2", "mid.com", 10), r("e2", "slow.com", 60),
            r("e3", "mid.com", 0), r("e3", "fast.com", 5), r("e3", "slow.com", 20)
        )
    }

    @Test
    fun `first-source section ranks outlets per category`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchEventArticlesSince("gaming", any()) } returns storyRows()
        val text = firstSourceStatus(true, db).build()
        assertTrue("🥇 *Хто першим* (30 дн.)\n  gaming: fast.com 67% (2/3) · mid.com 33% (1/3)" in text, text)
    }

    @Test
    fun `first-source section is absent with storyIntel off`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchEventArticlesSince(any(), any()) } returns storyRows()
        assertTrue("Хто першим" !in firstSourceStatus(false, db).build())
    }

    // ── factCheck ─────────────────────────────────────────────────────────────

    private fun factConfig() = AppConfig(
        telegram = metifikys.config.TelegramConfig(botToken = "t"),
        openai = metifikys.config.OpenAIConfig(apiKey = "sk"),
        database = metifikys.config.DatabaseConfig(path = ":memory:"),
        scheduler = metifikys.config.SchedulerConfig(intervalMinutes = 60),
        categories = mapOf("tech" to CategoryConfig(emoji = "💻", feeds = listOf(FeedConfig("https://e.com/rss")), channelId = "-1001234567890")),
        factCheck = metifikys.config.FactCheckConfig(
            enabled = true, judge = metifikys.config.LlmOverride("openai", "m"),
            alert = metifikys.config.FactCheckAlertConfig(windowDays = 7, minChecks = 4, maxUnsupportedRate = 0.2)
        )
    )

    private fun check(verdict: String, messageId: Long) = metifikys.db.FactCheckRow(
        "tech", -1001234567890L, messageId, "k$messageId", "post", 100, verdict, "[]", "openai", "m", LocalDateTime.now().minusHours(messageId)
    )

    @Test
    fun `fact-check section is hidden below minChecks`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchFactChecks(any()) } returns listOf(check("supported", 1), check("unsupported", 2))
        val text = StatusCommand(factConfig(), db, CycleErrorLog()).build()
        assertTrue("Fact-check" !in text, text)
    }

    @Test
    fun `fact-check line, and the warning with links once unsupported passes the threshold`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchFactChecks(any()) } returns listOf(
            check("supported", 1), check("minor", 2), check("supported", 3), check("supported", 4), check("supported", 5)
        )
        val calm = StatusCommand(factConfig(), db, CycleErrorLog()).build()
        assertTrue("🧐 Fact-check 7d: 5 · unsupported 0 (0%) · minor 1" in calm, calm)
        assertTrue("⚠️ unsupported" !in calm, calm)

        every { db.fetchFactChecks(any()) } returns listOf(
            check("unsupported", 1), check("unsupported", 2), check("supported", 3), check("supported", 4)
        )
        val alarmed = StatusCommand(factConfig(), db, CycleErrorLog()).build()
        assertTrue("🧐 Fact-check 7d: 4 · unsupported 2 (50%) · minor 0" in alarmed, alarmed)
        assertTrue("⚠️ unsupported above 20%: https://t.me/c/1234567890/1 https://t.me/c/1234567890/2" in alarmed, alarmed)
    }
}
