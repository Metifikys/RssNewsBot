package metifikys.telegram

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import metifikys.config.AdminCommandsConfig
import metifikys.config.AdminConfig
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.ModerationConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.db.CoveredEventRow
import metifikys.db.DigestMessageRow
import metifikys.db.ExplicitSignal
import metifikys.db.NewsDatabase
import metifikys.db.TrackerEntryRow
import metifikys.digest.MuteRegistry
import metifikys.feedback.AffinityAggregator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModerationNotifierTest {

    private lateinit var dbFile: File
    private lateinit var db: NewsDatabase
    private lateinit var sender: TelegramSender
    private lateinit var mutes: MuteRegistry
    private lateinit var notifier: ModerationNotifier
    private val keyboards = mutableListOf<List<List<InlineButton>>?>()
    private val now = LocalDateTime.now()

    private val config = AppConfig(
        telegram = TelegramConfig(botToken = "t", updatesPolling = true),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = "x.db"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf(
            "gaming" to CategoryConfig(emoji = "🎮", feeds = listOf(FeedConfig("https://e/rss")), channelId = "-100500"),
            "tech" to CategoryConfig(emoji = "💻", feeds = listOf(FeedConfig("https://e/rss2")), channelId = "@t")
        ),
        admin = AdminConfig(
            statusChatId = "555", userIds = listOf(7L), commands = AdminCommandsConfig(enabled = true),
            moderation = ModerationConfig(enabled = true, categories = listOf("gaming"))
        )
    )

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("moderation-test", ".db")
        db = NewsDatabase(dbFile.absolutePath)
        sender = mockk(relaxed = true)
        every { sender.editMessageReplyMarkup(any(), any(), captureNullable(keyboards)) } returns true
        every { sender.deleteMessage(any(), any()) } returns true
        mutes = MuteRegistry(config, db)
        notifier = ModerationNotifier(config, db, mutes, sender, Executor { it.run() }) { now }
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun press(action: String, chat: Long = -100500, msg: Long = 11) =
        notifier.handleCallback(IncomingCallback(1, "cb", 7, "mod:$action:$chat:$msg", 555, 900), listOf(action, chat.toString(), msg.toString()))

    private fun labels() = keyboards.last()!!.flatten().map { it.text }

    /** A two-chunk post of event evt-1 plus its covered event. */
    private fun seedPost(sentAt: LocalDateTime = now) {
        db.insertDigestMessages(
            listOf(
                DigestMessageRow(-100500, 11, "gaming", "evt-1", "https://a/1", sentAt),
                DigestMessageRow(-100500, 12, "gaming", "evt-1", "https://a/1", sentAt),
                DigestMessageRow(-100500, 13, "gaming", "evt-2", "https://a/2", sentAt)
            )
        )
        db.insertCoveredEvents(
            listOf(
                CoveredEventRow(
                    category = "gaming", eventKey = "evt-1", subject = "Hollow Knight: Silksong", franchise = "Silksong / Team Cherry",
                    eventType = "release_date", coreFact = "f", importance = 5, url = "https://a/1", coveredAt = sentAt
                )
            )
        )
    }

    @Test
    fun `title, html and keyboards`() {
        assertEquals("🎮 Silksong [PC]: реліз 14.10", ModerationNotifier.titleOf("\n🎮 **Silksong [PC]: реліз 14.10**\n\nbody\n[src](https://x)"))
        assertEquals(80, ModerationNotifier.titleOf("a".repeat(200)).length)
        val html = ModerationNotifier.render(ModeratedPost("gaming", 1, 2, "e", "A <b> & \"c\"", null, "https://t.me/c/500/11?x=\"1\""), "🎮")
        assertEquals("🎮 gaming · A &lt;b&gt; &amp; \"c\" · <a href=\"https://t.me/c/500/11?x=&quot;1&quot;\">пост</a>", html)

        val legacy = ModerationNotifier.keyboard(ModerationNotifier.KeyboardState(1, 2, null, false, null, canDelete = true, withFeedback = false))
        assertEquals(listOf(listOf("🗑 Видалити")), legacy.map { row -> row.map { it.text } })
        val full = ModerationNotifier.keyboard(ModerationNotifier.KeyboardState(-1001234567890, 4812, "silksong", false, ExplicitSignal.MORE, true, true))
        assertEquals(listOf("🗑 Видалити", "🔇 Mute: silksong", "👍 Більше такого ✓", "👎 Менше такого"), full.flatten().map { it.text })
        assertEquals("mod:del:-1001234567890:4812", full[0][0].callbackData)
    }

    @Test
    fun `afterDigest sends one silent html message per post, only for listed categories, and never throws`() {
        every { sender.sendMessageWithKeyboard(any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        val post = ModeratedPost("gaming", -100500, 11, "evt-1", "Title", "silksong", "https://t.me/c/500/11")
        notifier.afterDigest("gaming", listOf(post, post.copy(messageId = 13)))   // must not throw
        notifier.afterDigest("tech", listOf(post.copy(category = "tech")))
        verify(exactly = 2) { sender.sendMessageWithKeyboard("555", match { it.startsWith("🎮 gaming · Title") }, any(), true, true) }
    }

    @Test
    fun `delete asks first, then removes every chunk and its rows but keeps the covered event`() {
        seedPost()
        db.upsertTrackerEntry(
            TrackerEntryRow("gaming", "releases", "silksong", "Silksong", "2026-10-14", null, "scheduled", "evt-1", -100500, 11, now)
        )
        db.setState(AffinityAggregator.STATE_KEY, "x|y")

        press("del")
        assertEquals(listOf("✅ Так, видалити", "↩ Скасувати"), labels())
        verify(exactly = 0) { sender.deleteMessage(any(), any()) }

        press("delok")
        verify { sender.deleteMessage("-100500", 11) }
        verify { sender.deleteMessage("-100500", 12) }
        verify(exactly = 0) { sender.deleteMessage("-100500", 13) }
        assertTrue(labels().single().startsWith("🗑 видалено"), labels().toString())
        assertNull(db.fetchDigestMessage(-100500, 11))
        assertNull(db.fetchDigestMessage(-100500, 12))
        assertTrue(db.fetchDigestMessage(-100500, 13) != null)
        assertEquals(1, db.fetchCoveredEventsByKeys("gaming", listOf("evt-1"), now.minusDays(1)).size, "covered event kept")
        assertEquals(ExplicitSignal.DELETED, db.fetchAdminFeedback(-100500, 11)?.signal)
        assertNull(db.getState(AffinityAggregator.STATE_KEY), "affinity recomputes on the next tick")
        assertNull(db.fetchTrackerEntries("gaming", "releases").single().messageId)
    }

    @Test
    fun `a post older than 48 hours loses the delete button instead of failing silently`() {
        seedPost(now.minusHours(50))
        press("delok")
        verify(exactly = 0) { sender.deleteMessage(any(), any()) }
        assertTrue(labels().none { it == "🗑 Видалити" })
        assertTrue(labels().last().startsWith("⚠️ старше 48 год"), labels().toString())
    }

    @Test
    fun `more and less toggle, last press wins`() {
        seedPost()
        press("up")
        assertEquals(ExplicitSignal.MORE, db.fetchAdminFeedback(-100500, 11)?.signal)
        assertTrue("👍 Більше такого ✓" in labels())
        press("down")
        assertEquals(ExplicitSignal.LESS, db.fetchAdminFeedback(-100500, 11)?.signal)
        press("down")
        assertNull(db.fetchAdminFeedback(-100500, 11))
        assertTrue("👎 Менше такого" in labels())
    }

    @Test
    fun `mute from a post goes through the registry with the moderation source`() {
        seedPost()
        press("mute")
        assertEquals(listOf("🔇 У gaming", "🌐 Глобально", "↩ Скасувати"), labels())
        press("mcat")
        assertEquals(listOf("silksong" to "gaming"), db.fetchAdminMutes().map { it.keyword to it.category })
        assertEquals("moderation", db.fetchAdminMutes().single().source)
        assertTrue("🔇 silksong ✓" in labels(), labels().toString())
    }
}
