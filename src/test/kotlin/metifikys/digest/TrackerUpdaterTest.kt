package metifikys.digest

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.DedupConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.config.TrackerConfig
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.model.ShortlistItem
import metifikys.telegram.SentRef
import metifikys.telegram.TelegramSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackerUpdaterTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File
    private val now = LocalDateTime.of(2026, 9, 12, 14, 30)

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-tracker", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private val tracker = TrackerConfig(id = "releases", title = "📅 Найближчі релізи", eventTypes = listOf("release_date", "delay"))

    private fun config(channelId: String = "-1001234567890", trackers: List<TrackerConfig> = listOf(tracker)) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf("gaming" to CategoryConfig(
            emoji = "🎮", feeds = listOf(FeedConfig("https://example.com/rss")), channelId = channelId,
            dedup = DedupConfig(promptFile = "x.yaml"), trackers = trackers
        ))
    )

    private fun item(key: String, subject: String, eventType: String, date: String?) = ShortlistItem(
        eventKey = key, subject = subject, coreFact = "f", url = "https://example.com/$key", status = "new",
        eventType = eventType, eventDate = date
    )

    private fun row(key: String, messageId: Long) = DigestMessageRow(-1001234567890L, messageId, "gaming", key, "", now)

    private fun updater(sender: TelegramSender, at: LocalDateTime = now) = TrackerUpdater(config(), db, sender) { at }

    @Test
    fun `first digest posts and pins the tracker, sorted by date with TBA last`() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val text = slot<String>()
        every { sender.sendToChannel(any(), capture(text), any(), any()) } returns listOf(SentRef(-1001234567890L, 500L))
        every { sender.pinChatMessage(any(), any(), any()) } returns true

        updater(sender).afterDigest(
            "gaming",
            listOf(
                item("gta6", "GTA VI", "release_date", "2027-Q1"),
                item("silksong", "Hollow Knight: Silksong", "release_date", "2026-10-14"),
                item("hl3", "Half-Life 3", "major_announcement", null),      // not a tracked type
                item("kirby", "Kirby Air Riders", "delay", null)              // tracked, no date → TBA
            ),
            listOf(row("gta6", 11), row("silksong", 12), row("kirby", 13))
        )

        assertEquals(
            "**📅 Найближчі релізи** · оновлено 12.09 14:30\n\n" +
                "14.10.2026 · Hollow Knight: Silksong · [пост](https://t.me/c/1234567890/12)\n" +
                "Q1 2027 · GTA VI · [пост](https://t.me/c/1234567890/11)\n" +
                "TBA · Kirby Air Riders · [пост](https://t.me/c/1234567890/13)",
            text.captured
        )
        verify { sender.pinChatMessage("-1001234567890", 500L, true) }
        verify(exactly = 0) { sender.editMessageText(any(), any(), any(), any()) }
        val state = db.getState(TrackerUpdater.stateKey("gaming", "releases"))!!
        assertTrue(state.startsWith("-1001234567890|500|"), state)
        assertEquals(3, db.fetchTrackerEntries("gaming", "releases").size)
    }

    @Test
    fun `an unchanged body costs no Telegram call, a delay edits the message in place`() {
        val sender = mockk<TelegramSender>(relaxed = true)
        every { sender.sendToChannel(any(), any(), any(), any()) } returns listOf(SentRef(-1001234567890L, 500L))
        every { sender.pinChatMessage(any(), any(), any()) } returns true
        every { sender.editMessageText(any(), any(), any(), any()) } returns TelegramSender.EditResult.OK

        val u = updater(sender)
        u.afterDigest("gaming", listOf(item("silksong", "Hollow Knight: Silksong", "release_date", "2026-09-24")), listOf(row("silksong", 12)))
        // Same story, same date, same post, later at a different minute → header stamp differs, body does not.
        updater(sender, now.plusMinutes(30)).afterDigest("gaming", listOf(item("silksong", "Hollow Knight: Silksong", "release_date", "2026-09-24")), listOf(row("silksong", 12)))
        verify(exactly = 1) { sender.sendToChannel(any(), any(), any(), any()) }
        verify(exactly = 0) { sender.editMessageText(any(), any(), any(), any()) }

        val edited = slot<String>()
        every { sender.editMessageText(any(), any(), capture(edited), any()) } returns TelegramSender.EditResult.OK
        updater(sender, now.plusHours(1)).afterDigest("gaming", listOf(item("silksong_delay", "Hollow Knight: Silksong", "delay", "2026-10-14")), listOf(row("silksong_delay", 20)))
        verify { sender.editMessageText("-1001234567890", 500L, any(), any()) }
        assertTrue("14.10.2026 · Hollow Knight: Silksong ⏳ було 24.09.2026 · [пост](https://t.me/c/1234567890/20)" in edited.captured, edited.captured)
        val entry = db.fetchTrackerEntries("gaming", "releases").single()
        assertEquals(TrackerUpdater.STATUS_DELAYED, entry.status)
        assertEquals("2026-09-24", entry.prevDate)
        assertEquals(20L, entry.messageId)
    }

    @Test
    fun `a deleted tracker message is posted again, unpinning the old one`() {
        val sender = mockk<TelegramSender>(relaxed = true)
        every { sender.sendToChannel(any(), any(), any(), any()) } returnsMany listOf(listOf(SentRef(-1001234567890L, 500L)), listOf(SentRef(-1001234567890L, 900L)))
        every { sender.pinChatMessage(any(), any(), any()) } returns true
        every { sender.editMessageText(any(), any(), any(), any()) } returns TelegramSender.EditResult.NOT_FOUND

        updater(sender).afterDigest("gaming", listOf(item("a", "Alpha", "release_date", "2026-10-01")), listOf(row("a", 1)))
        updater(sender, now.plusHours(1)).afterDigest("gaming", listOf(item("b", "Beta", "release_date", "2026-11-01")), listOf(row("b", 2)))

        verify { sender.unpinChatMessage("-1001234567890", 500L) }
        verify { sender.pinChatMessage("-1001234567890", 900L, true) }
        assertTrue(db.getState(TrackerUpdater.stateKey("gaming", "releases"))!!.startsWith("-1001234567890|900|"))
    }

    @Test
    fun `released entries move to their own section and expire, the horizon hides far dates`() {
        val sender = mockk<TelegramSender>(relaxed = true)
        val cfg = tracker.copy(horizonDays = 30, releasedRetentionDays = 7, maxEntries = 2)
        val body = TrackerUpdater(config(trackers = listOf(cfg)), db, sender) { now }.renderBody(
            cfg, "@gaming",
            listOf(
                entry("old", "Old Game", "2026-08-01"),        // released > 7 days ago → gone
                entry("fresh", "Fresh Game", "2026-09-10"),    // released 2 days ago → section
                entry("soon", "Soon Game", "2026-09-20"),
                entry("later", "Later Game", "2026-10-05"),
                entry("far", "Far Game", "2027"),              // beyond the 30-day horizon → hidden
                entry("tba", "TBA Game", null)                 // maxEntries=2 cuts it
            ),
            now.toLocalDate()
        )
        assertEquals(
            "20.09.2026 · Soon Game\n05.10.2026 · Later Game\n\n**Вийшло за тиждень**\n10.09.2026 · Fresh Game",
            body
        )
    }

    @Test
    fun `nothing is posted while no tracked event has been seen`() {
        val sender = mockk<TelegramSender>(relaxed = true)
        updater(sender).afterDigest("gaming", listOf(item("hl3", "Half-Life 3", "major_announcement", null)), listOf(row("hl3", 1)))
        verify(exactly = 0) { sender.sendToChannel(any(), any(), any(), any()) }
    }

    private fun entry(key: String, title: String, date: String?) = metifikys.db.TrackerEntryRow(
        "gaming", "releases", key, title, date, null, TrackerUpdater.STATUS_SCHEDULED, key, null, null, now
    )
}
