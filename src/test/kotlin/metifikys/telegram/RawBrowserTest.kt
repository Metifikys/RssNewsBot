package metifikys.telegram

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import metifikys.config.AdminCommandsConfig
import metifikys.config.AdminConfig
import metifikys.config.AdminThreadsConfig
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.db.ArticleRecord
import metifikys.db.DigestMessageRow
import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import metifikys.db.NewsDatabase
import metifikys.db.RawArticle
import metifikys.db.RejectedEventRow
import metifikys.format.TopicFormatter
import metifikys.model.Article
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RawBrowserTest {

    private lateinit var dbFile: File
    private lateinit var db: NewsDatabase
    private lateinit var sender: TelegramSender
    private lateinit var router: AdminCommandRouter
    private lateinit var raw: RawBrowser
    private val sent = mutableListOf<Triple<String, Long?, List<List<InlineButton>>?>>()
    private val edits = mutableListOf<Pair<String, List<List<InlineButton>>?>>()
    private val now = LocalDateTime.now().withNano(0)

    private val feed = "https://e/rss"
    private val config = AppConfig(
        telegram = TelegramConfig(botToken = "t", updatesPolling = true),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = "x.db"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf(
            "gaming" to CategoryConfig(
                emoji = "🎮", channelId = "-1009876543210",
                feeds = listOf(FeedConfig(feed, label = "Gematsu"), FeedConfig("https://other/rss"))
            ),
            "tech" to CategoryConfig(emoji = "💻", feeds = listOf(FeedConfig("https://t/rss")), channelId = "@my_tech")
        ),
        admin = AdminConfig(
            statusChatId = "555", userIds = listOf(7L), commands = AdminCommandsConfig(enabled = true),
            threads = AdminThreadsConfig(status = 10L, inspect = 15L, raw = 20L)
        )
    )

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("raw-test", ".db")
        db = NewsDatabase(dbFile.absolutePath)
        sender = mockk(relaxed = true)
        every { sender.sendMessageWithKeyboard(any(), any(), any(), any(), any(), any()) } answers {
            sent += Triple(secondArg(), arg(5), thirdArg())
            1L
        }
        every { sender.editMessageWithKeyboard(any(), any(), any(), any(), any()) } answers {
            edits += thirdArg<String>() to arg(3)
            TelegramSender.EditResult.OK
        }
        router = AdminCommandRouter(AdminAuth(config.admin), sender, Executor { it.run() }, clock = { Instant.now() })
        raw = RawBrowser(config, db, sender) { now }
        raw.registerAll(router)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun command(text: String, chatId: Long = 555, thread: Long? = 3) =
        router.onMessage(IncomingMessage(1, chatId, "private", 7, 10, Instant.now().epochSecond, text, threadId = thread))

    private fun press(data: String, thread: Long? = 20) =
        raw.handleCallback(IncomingCallback(2, "cb", 7, data, 555, 44, thread), data.split(':').drop(1))

    private fun labels(kb: List<List<InlineButton>>?) = kb.orEmpty().flatten().map { it.text }

    @Test
    fun `raw opens the category keyboard in the raw thread, raw cat the feed keyboard`() {
        command("/raw")
        val (text, thread, kb) = sent.single()
        assertTrue(text.startsWith("📂 <b>/raw</b>"), text)
        assertEquals(20L, thread, "admin.threads.raw, not the thread the command came from")
        assertEquals(listOf("🎮 gaming", "💻 tech"), labels(kb))
        assertEquals(listOf("raw:c:0", "raw:c:1"), kb!!.flatten().map { it.callbackData })

        sent.clear()
        command("/raw gaming")
        val (feedsText, _, feedsKb) = sent.single()
        assertTrue("<b>gaming</b> — 2 фіди" in feedsText, feedsText)
        assertEquals(listOf("Gematsu", "other/rss", "↩ топіки"), labels(feedsKb))
        assertEquals("raw:f:0:0:${PostCard.tag(feed)}", feedsKb!!.first().single().callbackData)

        sent.clear()
        command("/raw nope")
        assertEquals(3L, sent.single().second, "an error is a reply: back to the sender's thread")
        assertTrue("Невідома категорія «nope»" in sent.single().first)
    }

    @Test
    fun `outside the admin chat everything answers in the request's own thread`() {
        command("/raw", chatId = 7, thread = 4)
        assertEquals(4L, sent.single().second)
    }

    @Test
    fun `navigation edits the keyboard in place, a stale feed press goes back home`() {
        press("raw:c:1")
        assertEquals(listOf("t/rss", "↩ топіки"), labels(edits.single().second))
        press("raw:home")
        assertEquals(listOf("🎮 gaming", "💻 tech"), labels(edits.last().second))
        press("raw:f:0:0:ffff")
        assertTrue(edits.last().first.startsWith("⚠️ конфіг змінився"))
        press("raw:c:9")
        assertTrue(edits.last().first.startsWith("⚠️ конфіг змінився"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a feed press sends its 10 newest articles with their status, Step 1 trail and text`() {
        val links = (1..12).map { "https://www.gematsu.com/a$it" }
        // Insert order is the order the feed brought them: a12 is the newest.
        for ((i, link) in links.withIndex()) {
            db.insertArticles(listOf(Article("gaming", "Title ${i + 1}", link, "Text of article ${i + 1} & <more>", now.minusHours(30L - i), feedUrl = feed)))
        }
        db.insertArticles(listOf(Article("gaming", "Other feed", "https://x/o", "o", now, feedUrl = "https://other/rss")))
        db.insertArticles(listOf(Article("tech", "Same feed, other category", "https://x/t", "t", now, feedUrl = feed)))
        // a12: published as event "sv"; a11: rejected by Step 1; a10: muted at Step 0; a9 still queued.
        db.markProcessed(listOf(links[11], links[10]))
        db.markMuted(listOf(links[9]))
        val ids = db.fetchArticleIdsByLinks(links)
        db.upsertEventArticles(
            listOf(EventArticleRow("gaming", ids.getValue(links[11]), "sv", links[11], "gematsu.com", now, now, EventArticleRole.STEP1, "new", recordedAt = now))
        )
        db.insertDigestMessages(listOf(DigestMessageRow(-1009876543210L, 15205, "gaming", "sv", links[11], now.minusHours(1), text = "t")))
        db.saveRejectedEvents(
            listOf(RejectedEventRow("gaming", "junk", subject = "Some DLC", coreFact = "A minor DLC.", importance = 2, digestFit = 1,
                url = links[10], status = "rejected", extractedAt = now))
        )

        press("raw:f:0:0:${PostCard.tag(feed)}")

        assertEquals(11, sent.size, "a header and 10 articles")
        assertTrue(sent.all { it.second == 20L }, "all into the raw thread")
        assertTrue(sent.all { it.third == null }, "no keyboards on article messages")
        val header = sent[0].first
        assertTrue("<b>Gematsu</b> — 10 останніх статей" in header, header)
        val articles = sent.drop(1).map { it.first }
        assertTrue(articles[0].startsWith("<b>1/10</b> · <a href=\"${links[11]}\">Title 12</a>"), articles[0])
        assertTrue(articles.last().contains("Title 3</a>"), "a1, a2 are past the limit")
        assertTrue(articles.none { "Other feed" in it || "other category" in it })

        assertTrue("☑️ PROCESSED — Step 1 її розглянув" in articles[0], articles[0])
        assertTrue("🧩 подія <code>sv</code> · step1: new" in articles[0], articles[0])
        assertTrue("📣 у каналі: <a href=\"https://t.me/c/9876543210/15205\">пост ↗</a>" in articles[0], articles[0])
        assertTrue("🤖 Step 1: <b>rejected</b> · imp 2 · news — · fit 1" in articles[1], articles[1])
        assertTrue("Some DLC" in articles[1] && "A minor DLC." in articles[1], articles[1])
        assertTrue("🔇 PROCESSED · mute" in articles[2], articles[2])
        assertTrue("⏳ UNPROCESSED — чекає на Step 1" in articles[3], articles[3])
        assertTrue("<blockquote expandable>Text of article 12 &amp; &lt;more&gt;</blockquote>" in articles[0], articles[0])
    }

    @Test
    fun `an empty feed says so`() {
        press("raw:f:0:1:${PostCard.tag("https://other/rss")}")
        assertTrue(sent.single().first.startsWith("📭"), sent.single().first)
    }

    @Test
    fun `a long text is cut so the message fits Telegram`() {
        val record = ArticleRecord(1, "gaming", "T", "https://e/a", feed, now, now, "PROCESSED", null, summary = "s".repeat(2000))
        val html = RawCard.render(RawArticle(record, "x & y.".repeat(2000)), 1, 1, RawCard.Step1())
        assertTrue(TopicFormatter.htmlVisibleLength(html) <= RawCard.MAX_CHARS, "${TopicFormatter.htmlVisibleLength(html)}")
        assertTrue(html.endsWith("…</blockquote>"), html.takeLast(40))
        assertTrue("📄 Текст (12000 симв.):" in html)
    }

    @Test
    fun `raw is in help and the dedup pointer is shown`() {
        assertTrue("/raw [cat]" in router.helpText())
        val record = ArticleRecord(5, "gaming", "T", "https://e/a", feed, now, now, "DUPLICATE", null)
        assertTrue("(дубль статті #3)" in RawCard.statusLabel(RawArticle(record, "t", duplicateOf = 3)))
        verify(exactly = 0) { sender.sendMessageWithKeyboard(any(), any(), any(), any(), any(), any()) }
    }
}
