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
    private fun post(weekly: WeeklyConfig, storyIntel: Boolean, db: NewsDatabase = mockk(relaxed = true)): String {
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
}
