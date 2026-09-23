package metifikys.digest

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import metifikys.ai.Embedder
import metifikys.ai.LlmClient
import metifikys.ai.LlmClientsFactory
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.DedupConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.StoryIntelConfig
import metifikys.config.TelegramConfig
import metifikys.config.WeeklyConfig
import metifikys.db.CoveredEventRow
import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import metifikys.db.NewsDatabase
import metifikys.model.Article
import metifikys.telegram.SentRef
import metifikys.telegram.TelegramSender
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyDigestTest {

    private val url = "https://alpha.com/story"

    private fun config(weekly: WeeklyConfig, storyIntel: Boolean) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf(
            "games" to CategoryConfig(
                emoji = "🎮", feeds = listOf(FeedConfig("https://example.com/rss")), channelId = "@g",
                dedup = DedupConfig(promptFile = "x.yaml")
            )
        ),
        weekly = weekly,
        storyIntel = StoryIntelConfig(enabled = storyIntel)
    )

    private fun member(id: Int, domain: String) = EventArticleRow(
        category = "games", articleId = id, eventKey = "k1", link = "https://$domain/story", domain = domain,
        pubDate = LocalDateTime.of(2026, 9, 1, 10, id), seenAt = null, role = EventArticleRole.STEP1,
        step1Status = "new", recordedAt = LocalDateTime.now()
    )

    /** Runs one weekly roundup with a single covered story and returns the posted text. */
    private fun post(
        weekly: WeeklyConfig,
        storyIntel: Boolean,
        db: NewsDatabase = mockk(relaxed = true),
        setupSender: (TelegramSender) -> Unit = {}
    ): String {
        every { db.fetchRecentEvents("games", any(), any()) } returns listOf(
            CoveredEventRow("games", "k1", "Silksong", "Hollow Knight", "release_date", "date", 8, 8, 8, url, LocalDateTime.now())
        )
        every { db.fetchRecentRejectedEvents(any(), any(), any(), any()) } returns emptyList()
        every { db.fetchEventClusters("games", any()) } returns mapOf(
            "k1" to listOf(member(1, "alpha.com"), member(2, "beta.com"), member(3, "gamma.com"))
        )
        every { db.fetchArticlesByLinks(any()) } answers {
            firstArg<List<String>>().map { link ->
                Article(category = "games", title = if ("alpha" in link) "Silksong date" else "Silksong date via Alpha", link = link, description = "d")
            }
        }
        val embedder = mockk<Embedder>()
        every { embedder.embed(any(), any(), any()) } answers { firstArg<List<String>>().map { floatArrayOf(1f, 0f) } }
        val llm = mockk<LlmClient>()
        every { llm.complete(any(), any()) } returns "Тиждень\n• **Silksong** отримав дату. [Alpha]($url)"
        val factory = mockk<LlmClientsFactory>()
        every { factory.forRender(any()) } returns llm
        val sender = mockk<TelegramSender>()
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any(), any()) } returns listOf(SentRef(-1L, 1L))
        setupSender(sender)

        val cfg = config(weekly, storyIntel)
        WeeklyDigest(cfg, db, factory, embedder, sender, weekly, File(".")).runForCategory("games")
        return sent.captured
    }

    @Test
    fun `showOutlets replaces the mention badge with the story's outlets`() {
        val text = post(WeeklyConfig(enabled = true, minMentions = 1, showOutlets = true), storyIntel = true)
        assertEquals("Тиждень\n\n• **Silksong** отримав дату. [Alpha]($url) 📢 3 видання · 1 першоджерело", text)
    }

    @Test
    fun `without showOutlets the badge stays 🔁 N`() {
        val text = post(WeeklyConfig(enabled = true, minMentions = 1), storyIntel = true)
        assertEquals("Тиждень\n\n• **Silksong** отримав дату. [Alpha]($url) 🔁 1", text)
    }

    @Test
    fun `the source leaderboard closes the roundup, before the hashtag`() {
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchEventArticlesSince("games", any()) } returns
            listOf(member(1, "alpha.com"), member(2, "beta.com"), member(3, "gamma.com"))
        val weekly = WeeklyConfig(
            enabled = true, minMentions = 1, hashtag = "#тиждень",
            sourceLeaderboard = metifikys.config.SourceLeaderboardConfig(enabled = true, minEvents = 1, top = 1)
        )
        val text = post(weekly, storyIntel = true, db = db)
        assertEquals(
            "Тиждень\n\n• **Silksong** отримав дату. [Alpha]($url) 🔁 1\n\n🥇 Хто пише першим (30 дн.): alpha.com — 1 з 1\n\n#тиждень",
            text
        )
    }

    // ── weekly.barometer ────────────────────────────────────────────────────

    private fun historyDb(weeksOfHistory: Long): NewsDatabase {
        val db = mockk<NewsDatabase>(relaxed = true)
        val now = LocalDateTime.now()
        every { db.earliestEventHistory("games") } returns now.minusWeeks(weeksOfHistory)
        every { db.fetchEventHistorySince("games", any()) } returns listOf(
            metifikys.db.EventHistoryRow("games", "e1", "studio_a", "Studio A", "", "studio_closure", "closed", null, 6, now),
            metifikys.db.EventHistoryRow("games", "e2", "studio_b", "Studio B (звільнення)", "", "industry_news", "50 people", null, 6, now.minusWeeks(1))
        )
        return db
    }

    private fun barometerWeekly(format: String, minWeeks: Int = 4) = WeeklyConfig(
        enabled = true, minMentions = 1, hashtag = "#тиждень",
        barometer = metifikys.config.BarometerConfig(enabled = true, weeks = 4, minWeeks = minWeeks, format = format)
    )

    @Test
    fun `text barometer goes into the roundup before the hashtag`() {
        val text = post(barometerWeekly("text"), storyIntel = true, db = historyDb(10))
        assertEquals(
            "Тиждень\n\n• **Silksong** отримав дату. [Alpha]($url) 🔁 1\n\n" +
                "📊 Барометр індустрії (4 тиж., за нашими джерелами)\n" +
                "Звільнення і закриття ▁▁██ 1 (у середньому 0,5)\n" +
                "Новини про релізи ▁▁▁▁ 0 (у середньому 0,0)\n\n#тиждень",
            text
        )
    }

    @Test
    fun `png barometer is its own photo post after the roundup, and its failure leaves the roundup alone`() {
        for (photoFails in listOf(false, true)) {
            val photo = slot<ByteArray>()
            val caption = slot<String>()
            val text = post(barometerWeekly("png"), storyIntel = true, db = historyDb(10)) { sender ->
                if (photoFails) every { sender.sendPhotoBytes(any(), any(), any(), any()) } throws RuntimeException("boom")
                else every { sender.sendPhotoBytes("@g", capture(photo), capture(caption), any()) } returns SentRef(-1L, 2L)
            }
            assertEquals("Тиждень\n\n• **Silksong** отримав дату. [Alpha]($url) 🔁 1\n\n#тиждень", text)
            if (!photoFails) {
                val img = javax.imageio.ImageIO.read(photo.captured.inputStream())
                assertEquals(1200 to 675, img.width to img.height)
                assertTrue(caption.captured.startsWith("📊 Барометр індустрії за нашими джерелами (4 тиж."), caption.captured)
            }
        }
    }

    @Test
    fun `no barometer until the history spans minWeeks, nor with storyIntel off`() {
        assertEquals(
            "Тиждень\n\n• **Silksong** отримав дату. [Alpha]($url) 🔁 1\n\n#тиждень",
            post(barometerWeekly("text", minWeeks = 4), storyIntel = true, db = historyDb(1))
        )
        val db = historyDb(10)
        assertEquals(
            "Тиждень\n\n• **Silksong** отримав дату. [Alpha]($url) 🔁 1\n\n#тиждень",
            post(barometerWeekly("text"), storyIntel = false, db = db)
        )
        io.mockk.verify(exactly = 0) { db.fetchEventHistorySince(any(), any()) }
    }
}
