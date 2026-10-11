package metifikys.telegram

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.config.AdminCommandsConfig
import metifikys.config.AdminConfig
import metifikys.config.AdminInspectConfig
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.db.AudienceAffinityRow
import metifikys.db.CoveredEventRow
import metifikys.db.DigestMessageRow
import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import metifikys.db.NewsDatabase
import metifikys.db.ReactionCount
import metifikys.db.TelegraphPageRow
import metifikys.digest.MuteRegistry
import metifikys.model.Article
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostInspectorTest {

    private lateinit var dbFile: File
    private lateinit var db: NewsDatabase
    private lateinit var sender: TelegramSender
    private lateinit var mutes: MuteRegistry
    private lateinit var inspector: PostInspector
    private val keyboards = mutableListOf<List<List<InlineButton>>?>()
    private val now = LocalDateTime.now().withNano(0)

    private val config = AppConfig(
        telegram = TelegramConfig(botToken = "t", updatesPolling = true),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = "x.db"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf(
            "gaming" to CategoryConfig(emoji = "🎮", feeds = listOf(FeedConfig("https://e/rss", label = "Gematsu")), channelId = "-1009876543210"),
            "tech" to CategoryConfig(emoji = "💻", feeds = listOf(FeedConfig("https://e/rss2")), channelId = "@my_tech")
        ),
        admin = AdminConfig(statusChatId = "555", userIds = listOf(7L), commands = AdminCommandsConfig(enabled = true), inspect = AdminInspectConfig())
    )

    private val chat = -1009876543210L

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("inspector-test", ".db")
        db = NewsDatabase(dbFile.absolutePath)
        sender = mockk(relaxed = true)
        every { sender.editMessageReplyMarkup(any(), any(), captureNullable(keyboards)) } returns true
        mutes = MuteRegistry(config, db)
        inspector = PostInspector(config, db, mutes, sender) { now }
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    /** A two-chunk post of the Signal Veil event, its article, cluster, reactions and affinity. */
    private fun seed() {
        db.insertArticles(
            listOf(
                Article("gaming", "Survival horror game Signal Veil announced for PS5", "https://www.gematsu.com/sv", "Signal Veil desc",
                    pubDate = now.minusHours(9), feedUrl = "https://e/rss", summary = "Serafini Productions анонсувала Signal Veil.")
            )
        )
        db.markProcessed(listOf("https://www.gematsu.com/sv"))
        db.insertDigestMessages(
            listOf(
                DigestMessageRow(chat, 15205, "gaming", "signal_veil", "https://www.gematsu.com/sv", now.minusHours(1), text = "t"),
                DigestMessageRow(chat, 15206, "gaming", "signal_veil", "https://www.gematsu.com/sv", now.minusHours(1), text = "t"),
                DigestMessageRow(chat, 15300, "gaming", "signal_veil_dated", "https://x/2", now, relatedEventKey = "signal_veil")
            )
        )
        db.insertCoveredEvents(
            listOf(
                CoveredEventRow("gaming", "signal_veil", "Signal Veil", "Signal Veil / Serafini Productions", "rumor",
                    "Serafini Productions анонсувала Signal Veil.", 3, 3, 5, "https://www.gematsu.com/sv", now.minusHours(1))
            )
        )
        db.upsertEventArticles(
            listOf(
                EventArticleRow("gaming", 1, "signal_veil", "https://www.gematsu.com/sv", "gematsu.com", now.minusHours(9), now.minusHours(2),
                    EventArticleRole.STEP1, "new", null, now.minusHours(1))
            )
        )
        db.replaceReactionCounts(chat, 15205, listOf(ReactionCount("👍", 4)))
        db.replaceReactionCounts(chat, 15206, listOf(ReactionCount("👍", 1), ReactionCount("🔥", 2)))
        db.replaceAudienceAffinity(
            listOf(AudienceAffinityRow("gaming", "event_type", "rumor", 10.0, 4.0, 0.1, 0.2, -0.12, now))
        )
        db.insertTelegraphPage(TelegraphPageRow("gaming", "signal_veil", "p", "https://telegra.ph/p", now.minusHours(1)))
    }

    private fun cardText(): String {
        val text = slot<String>()
        verify { sender.sendMessageWithKeyboard("555", capture(text), any(), any(), true) }
        return text.captured
    }

    @Test
    fun `c link, username link and a later chunk resolve to the post's first message`() {
        seed()
        val byChat = assertIs<PostInspector.Resolved.Post>(inspector.resolve(PostRef(chat, null, 15206)))
        assertEquals(15205, byChat.first.messageId)
        assertEquals(2, byChat.chunks.size)

        db.insertDigestMessages(listOf(DigestMessageRow(-100777, 9, "tech", "k", "https://t/1", now)))
        val byName = assertIs<PostInspector.Resolved.Post>(inspector.resolve(PostRef(null, "My_Tech", 9)))
        assertEquals("tech", byName.first.category)

        assertIs<PostInspector.Resolved.UnknownChannel>(inspector.resolve(PostRef(null, "someone_else", 9)))
        assertEquals(PostInspector.Resolved.NotFound, inspector.resolve(PostRef(chat, null, 1)))
    }

    @Test
    fun `the card is built from the DB rows - chain, telegraph, source, cluster, reactions summed over chunks, affinity`() {
        seed()
        inspector.inspect(listOf(PostRef(chat, null, 15206)), "555")
        val text = cardText()
        assertTrue("🔎 🎮 gaming" in text && "https://t.me/c/9876543210/15205" in text, text)
        assertTrue("· 2 повідомл." in text)
        assertTrue("↪ далі: 1 оновлення" in text && "https://t.me/c/9876543210/15300" in text, text)
        assertTrue("⚡ лонгрід: <a href=\"https://telegra.ph/p\">Telegraph ↗</a>" in text)
        assertTrue("<code>signal_veil</code> · статус: нова" in text, text)
        assertTrue("eventType: чутки (<code>rumor</code>)" in text, text)
        assertTrue("importance 3 · newsworthiness 3 · digestFit 5" in text, text)
        assertTrue("<blockquote expandable>Serafini Productions анонсувала Signal Veil.</blockquote>" in text, text)
        assertTrue("стрічка Gematsu" in text, text)
        assertTrue("(+9 год)" in text, text)
        assertTrue("<b>Кластер</b>: 1 видання · першим gematsu.com" in text, text)
        assertTrue("Реакції: 👍 5 · 🔥 2" in text, text)
        assertTrue("Affinity: тип rumor −0.12 (n 4.0/10.0)" in text, text)
    }

    @Test
    fun `the update's card links back to the original`() {
        seed()
        inspector.inspect(listOf(PostRef(chat, null, 15300)), "555")
        val text = cardText()
        assertTrue("↩ оновлення до <a href=\"https://t.me/c/9876543210/15205\">попереднього посту ↗</a>" in text, text)
        assertTrue("статус: оновлення до <code>signal_veil</code>" in text, text)
    }

    @Test
    fun `not found and unknown channels get one short reply`() {
        inspector.inspect(listOf(PostRef(chat, null, 1), PostRef(null, "someone_else", 2)), "555")
        verify {
            sender.sendMessageWithKeyboard(
                "555",
                match { it.contains("не знайшов") && it.contains("@someone_else — не канал") },
                null, false, false
            )
        }
    }

    private fun press(data: String) {
        val parts = data.split(':')
        inspector.handleCallback(IncomingCallback(1, "cb", 7, data, 555, 900), parts.drop(1))
    }

    private fun labels() = keyboards.last()!!.flatten().map { it.text }

    @Test
    fun `keyword button - confirm, then an in-category mute through the registry`() {
        seed()
        inspector.inspect(listOf(PostRef(chat, null, 15205)), "555")
        val kb = slot<List<List<InlineButton>>>()
        verify { sender.sendMessageWithKeyboard("555", any(), capture(kb), any(), true) }
        val first = kb.captured[0][0]
        assertEquals("🔇 signal veil ▸", first.text)

        press(first.callbackData)
        assertEquals(listOf("🔇 «signal veil» у gaming", "🌐 Глобально", "↩ Скасувати"), labels())
        press(keyboards.last()!![0][0].callbackData)
        assertNotNull(mutes.covering("signal veil", "gaming"))
        assertNull(mutes.covering("signal veil", null), "in-category only")
        assertTrue("🔇 signal veil ✓" in labels() && "🔇 «signal veil» зам'ючено: gaming" in labels(), labels().toString())
        assertEquals("inspect", db.fetchAdminMutes().single().source)
    }

    @Test
    fun `global keyword mute, cancel, and a stale tag is refused`() {
        seed()
        val tag = PostCard.tag("survival")
        press("in:kg:$chat:15205:2:$tag")
        assertTrue(labels().any { it.startsWith("⚠️ дані поста змінились") }, "index 2 is 'horror', not 'survival'")
        assertTrue(db.fetchAdminMutes().isEmpty())

        press("in:kg:$chat:15205:0:${PostCard.tag("signal veil")}")
        assertNotNull(mutes.covering("signal veil", "tech"), "global")
        press("in:back:$chat:15205")
        assertTrue("🔇 signal veil ✓" in labels())
    }

    @Test
    fun `event-type button - confirm, mute the type in the category, shown as muted`() {
        seed()
        press("in:t:$chat:15205")
        assertEquals(listOf("🔇 Усі «чутки» у gaming", "↩ Скасувати"), labels())
        press("in:tc:$chat:15205")
        assertEquals(setOf("rumor"), mutes.mutedEventTypes("gaming"))
        assertEquals(emptySet(), mutes.mutedEventTypes("tech"))
        assertTrue("🔇 тип: чутки ✓" in labels() && "🔇 тип «чутки» зам'ючено в gaming" in labels(), labels().toString())
        assertTrue(mutes.filterFor("gaming").isEmpty(), "a type mute is no keyword mute")

        // The next card shows it under Фільтри.
        inspector.inspect(listOf(PostRef(chat, null, 15205)), "555")
        assertTrue("🔇 тип «чутки» зам'ючено в gaming" in cardText())
    }

    @Test
    fun `a press on a post that is gone says so`() {
        press("in:k:$chat:4242:0:abcd")
        assertEquals(listOf("пост уже не в БД (видалено або ретеншн)"), labels())
    }

    @Test
    fun `routing - admin link and forward reach the inspector, a stranger and plain chatter do not`() {
        seed()
        val router = AdminCommandRouter(AdminAuth(config.admin), sender, Executor { it.run() }, clock = { Instant.now() })
        inspector.registerAll(router)
        val nowSec = Instant.now().epochSecond
        fun msg(from: Long, text: String?, fwdChat: Long? = null, fwdMsg: Long? = null) =
            IncomingMessage(1, 7, "private", from, 10, nowSec, text, fwdChat, fwdMsg)

        router.onMessage(msg(99, "https://t.me/c/9876543210/15205"))           // not an admin
        router.onMessage(msg(7, "just chatting"))                               // no link
        verify(exactly = 0) { sender.sendMessageWithKeyboard(any(), any(), any(), any(), true) }

        router.onMessage(msg(7, "дивись https://t.me/c/9876543210/15205"))
        verify(exactly = 1) { sender.sendMessageWithKeyboard("7", match { "signal_veil" in it }, any(), any(), true) }
        router.onMessage(msg(7, null, chat, 15206))                             // a forwarded chunk
        verify(exactly = 2) { sender.sendMessageWithKeyboard("7", match { "signal_veil" in it }, any(), any(), true) }
        router.onMessage(msg(7, "/inspect"))
        verify { sender.sendMessageWithKeyboard("7", match { it.startsWith("Використання: /inspect") }, null, false, false) }
        assertTrue("/inspect <посилання на пост>" in router.helpText())
    }

    @Test
    fun `threads - in the admin chat cards go to admin threads inspect, the usage reply to the sender's thread`() {
        seed()
        val threaded = config.copy(admin = config.admin.copy(threads = metifikys.config.AdminThreadsConfig(inspect = 15L)))
        val router = AdminCommandRouter(AdminAuth(threaded.admin), sender, Executor { it.run() }, clock = { Instant.now() })
        PostInspector(threaded, db, mutes, sender) { now }.registerAll(router)
        val nowSec = Instant.now().epochSecond
        fun msg(chatId: Long, text: String, thread: Long?) = IncomingMessage(1, chatId, "private", 7, 10, nowSec, text, threadId = thread)

        router.onMessage(msg(555, "https://t.me/c/9876543210/15205", thread = 3))
        verify(exactly = 1) { sender.sendMessageWithKeyboard("555", match { "signal_veil" in it }, any(), any(), true, 15L) }
        router.onMessage(msg(555, "/inspect", thread = 3))
        verify { sender.sendMessageWithKeyboard("555", match { it.startsWith("Використання: /inspect") }, null, false, false, 3L) }
        // Another chat's thread ids are its own: the card answers where the link came from.
        router.onMessage(msg(7, "https://t.me/c/9876543210/15205", thread = 4))
        verify(exactly = 1) { sender.sendMessageWithKeyboard("7", match { "signal_veil" in it }, any(), any(), true, 4L) }
    }
}
