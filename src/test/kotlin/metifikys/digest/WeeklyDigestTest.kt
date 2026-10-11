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
import kotlin.test.assertFalse
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

    // ── weekly.album ────────────────────────────────────────────────────────

    private class AlbumRun(val text: String?, val photos: List<metifikys.telegram.AlbumPhoto>?, val calls: List<String>)

    /**
     * Three stories u1..u3 whose articles carry [images] (null = none); [checkOk] is the HEAD
     * preflight, [albumOk] whether sendMediaGroup succeeds.
     */
    private fun albumRun(
        images: List<String?>,
        checkOk: (String) -> Boolean = { true },
        albumOk: Boolean = true,
        album: metifikys.config.WeeklyAlbumConfig = metifikys.config.WeeklyAlbumConfig(enabled = true)
    ): AlbumRun {
        val urls = listOf("https://a.com/1", "https://b.com/2", "https://c.com/3")
        val db = mockk<NewsDatabase>(relaxed = true)
        every { db.fetchRecentEvents("games", any(), any()) } returns urls.mapIndexed { i, u ->
            CoveredEventRow("games", "k$i", "Story $i", "", "release_date", "fact $i", 8 - i, 8, 8, u, LocalDateTime.now())
        }
        every { db.fetchRecentRejectedEvents(any(), any(), any(), any()) } returns emptyList()
        every { db.fetchArticlesByLinks(any()) } answers {
            firstArg<List<String>>().map { link ->
                Article(category = "games", title = link, link = link, description = "d", imageUrl = images[urls.indexOf(link)])
            }
        }
        val embedder = mockk<Embedder>()
        every { embedder.embed(any(), any(), any()) } answers {
            firstArg<List<String>>().mapIndexed { i, _ -> FloatArray(4).also { it[i % 4] = 1f } }
        }
        val llm = mockk<LlmClient>()
        every { llm.complete(any(), any()) } returns "📅 Тиждень\n" + urls.mapIndexed { i, u -> "• Story $i [S$i]($u)" }.joinToString("\n")
        val factory = mockk<LlmClientsFactory>()
        every { factory.forRender(any()) } returns llm
        val calls = mutableListOf<String>()
        val sender = mockk<TelegramSender>()
        val sent = slot<String>()
        every { sender.sendToChannel(any(), capture(sent), any(), any(), any()) } answers { calls += "text"; listOf(SentRef(-1L, 1L)) }
        val photos = slot<List<metifikys.telegram.AlbumPhoto>>()
        every { sender.sendMediaGroup("@g", capture(photos)) } answers {
            calls += "album"
            if (albumOk) listOf(SentRef(-1L, 5L), SentRef(-1L, 6L)) else emptyList()
        }
        val weekly = WeeklyConfig(enabled = true, minMentions = 1, showMentions = false, album = album)
        WeeklyDigest(config(weekly, storyIntel = false), db, factory, embedder, sender, weekly, File("."), checkOk).runForCategory("games")
        return AlbumRun(if (sent.isCaptured) sent.captured else null, if (photos.isCaptured) photos.captured else null, calls)
    }

    @Test
    fun `three pictures go out as an album with the header caption, then the text`() {
        val run = albumRun(listOf("https://img/1.jpg", "https://img/2.jpg", "https://img/3.jpg"))
        assertEquals(listOf("album", "text"), run.calls)
        assertEquals(
            listOf(
                metifikys.telegram.AlbumPhoto("https://img/1.jpg", "📅 Тиждень"),
                metifikys.telegram.AlbumPhoto("https://img/2.jpg"),
                metifikys.telegram.AlbumPhoto("https://img/3.jpg")
            ),
            run.photos
        )
        assertTrue(run.text!!.startsWith("📅 Тиждень\n\n• Story 0"))
    }

    @Test
    fun `a duplicate picture, a logo and a failed preflight are skipped, maxPhotos caps the album`() {
        val deduped = albumRun(listOf("https://img/1.jpg", "https://img/1.jpg", "https://img/3.jpg"))
        assertEquals(listOf("https://img/1.jpg", "https://img/3.jpg"), deduped.photos!!.map { it.url })
        val capped = albumRun(
            listOf("https://img/1.jpg", "https://img/2.jpg", "https://img/3.jpg"),
            album = metifikys.config.WeeklyAlbumConfig(enabled = true, maxPhotos = 2)
        )
        assertEquals(listOf("https://img/1.jpg", "https://img/2.jpg"), capped.photos!!.map { it.url })
        val filtered = albumRun(
            listOf("https://img/site-logo.png", "https://img/2.jpg", "https://img/3.jpg"),
            checkOk = { "3" !in it }
        )
        assertEquals(listOf("text"), filtered.calls)
    }

    // ── weekly.rich ─────────────────────────────────────────────────────────

    private class RichWeeklyRun(val htmls: List<String>, val attachments: List<Map<String, ByteArray>>, val calls: List<String>)

    /** Three stories with pictures (album on), a png barometer and a hashtag; [answers] are sendRichMessage's results in turn. */
    private fun richWeeklyRun(answers: List<SentRef?>): RichWeeklyRun {
        val urls = listOf("https://a.com/1", "https://b.com/2", "https://c.com/3")
        val db = historyDb(10)
        every { db.fetchRecentEvents("games", any(), any()) } returns urls.mapIndexed { i, u ->
            CoveredEventRow("games", "k$i", "Story $i", "", "release_date", "fact $i", 8 - i, 8, 8, u, LocalDateTime.now())
        }
        every { db.fetchRecentRejectedEvents(any(), any(), any(), any()) } returns emptyList()
        every { db.fetchArticlesByLinks(any()) } answers {
            firstArg<List<String>>().map { link ->
                Article(category = "games", title = link, link = link, description = "d", imageUrl = "https://img/${urls.indexOf(link) + 1}.jpg")
            }
        }
        val embedder = mockk<Embedder>()
        every { embedder.embed(any(), any(), any()) } answers {
            firstArg<List<String>>().mapIndexed { i, _ -> FloatArray(4).also { it[i % 4] = 1f } }
        }
        val llm = mockk<LlmClient>()
        every { llm.complete(any(), any()) } returns "📅 Тиждень\n" + urls.mapIndexed { i, u -> "• Story $i [S$i]($u)" }.joinToString("\n")
        val factory = mockk<LlmClientsFactory>()
        every { factory.forRender(any()) } returns llm
        val calls = mutableListOf<String>()
        val htmls = mutableListOf<String>()
        val attachments = mutableListOf<Map<String, ByteArray>>()
        val queue = ArrayDeque(answers)
        val sender = mockk<TelegramSender>()
        every { sender.sendRichMessage("@g", capture(htmls), any(), any(), capture(attachments)) } answers { calls += "rich"; queue.removeFirst() }
        every { sender.sendToChannel(any(), any(), any(), any(), any()) } answers { calls += "text"; listOf(SentRef(-1L, 1L)) }
        every { sender.sendMediaGroup(any(), any()) } answers { calls += "album"; listOf(SentRef(-1L, 5L), SentRef(-1L, 6L)) }
        every { sender.sendPhotoBytes(any(), any(), any(), any()) } answers { calls += "chart"; SentRef(-1L, 7L) }
        val weekly = WeeklyConfig(
            enabled = true, minMentions = 1, showMentions = false, hashtag = "#тиждень",
            album = metifikys.config.WeeklyAlbumConfig(enabled = true),
            barometer = metifikys.config.BarometerConfig(enabled = true, weeks = 4, minWeeks = 4, format = "png"),
            rich = metifikys.config.WeeklyRichConfig(enabled = true)
        )
        WeeklyDigest(config(weekly, storyIntel = true), db, factory, embedder, sender, weekly, File(".")) { true }.runForCategory("games")
        return RichWeeklyRun(htmls, attachments, calls)
    }

    @Test
    fun `a rich roundup is one message — collage, numbered bullets, the chart uploaded with it, the hashtag`() {
        val run = richWeeklyRun(listOf(SentRef(-1L, 9L)))
        assertEquals(listOf("rich"), run.calls)
        val html = run.htmls.single()
        assertTrue(html.startsWith(
            "<h2>📅 Тиждень</h2><tg-collage><img src=\"https://img/1.jpg\"/><img src=\"https://img/2.jpg\"/><img src=\"https://img/3.jpg\"/></tg-collage>" +
                "<ol><li>Story 0 <a href=\"https://a.com/1\">S0</a></li>"
        ), html)
        assertTrue("<figure><img src=\"tg://photo?id=barometer\"/><figcaption>📊 Барометр індустрії за нашими джерелами (4 тиж." in html, html)
        assertTrue(html.endsWith("<p>#тиждень</p>"), html)
        val png = run.attachments.single()["barometer"]!!
        val img = javax.imageio.ImageIO.read(png.inputStream())
        assertEquals(1200 to 675, img.width to img.height)
    }

    @Test
    fun `a refused rich roundup is retried without media, then the classic posts go out`() {
        val bare = richWeeklyRun(listOf(null, SentRef(-1L, 9L)))
        assertEquals(listOf("rich", "rich"), bare.calls)
        assertFalse("<tg-collage>" in bare.htmls[1] || "<figure>" in bare.htmls[1], bare.htmls[1])
        assertTrue("<p>📊 Барометр індустрії (4 тиж., за нашими джерелами)</p>" in bare.htmls[1], bare.htmls[1])
        assertTrue(bare.attachments[1].isEmpty())

        val classic = richWeeklyRun(listOf(null, null))
        assertEquals(listOf("rich", "rich", "album", "text", "chart"), classic.calls)
    }

    @Test
    fun `one picture, no album config or a failed album send leave the text alone`() {
        assertEquals(listOf("text"), albumRun(listOf("https://img/1.jpg", null, null)).calls)
        assertEquals(
            listOf("text"),
            albumRun(listOf("https://img/1.jpg", "https://img/2.jpg"), album = metifikys.config.WeeklyAlbumConfig()).calls
        )
        val failed = albumRun(listOf("https://img/1.jpg", "https://img/2.jpg", "https://img/3.jpg"), albumOk = false)
        assertEquals(listOf("album", "text"), failed.calls)
        assertTrue(failed.text!!.startsWith("📅 Тиждень\n\n• Story 0"))
    }
}
