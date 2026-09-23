package metifikys.db

import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.DedupConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.StoryIntelConfig
import metifikys.config.TelegramConfig
import metifikys.model.ShortlistItem
import metifikys.story.EventHistoryRecorder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventHistoryTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-event-history", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private val now = LocalDateTime.now().withNano(0)

    private fun item(key: String, subject: String = "Hollow Knight: Silksong", type: String = "major_announcement", date: String? = null) =
        ShortlistItem(eventKey = key, subject = subject, franchise = "Hollow Knight", eventType = type, coreFact = "fact $key", url = "https://x/$key", status = "new", eventDate = date)

    private fun config(enabled: Boolean) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf("gaming" to CategoryConfig(emoji = "🎮", feeds = listOf(FeedConfig("https://e.com/rss")), channelId = "@g", dedup = DedupConfig())),
        storyIntel = StoryIntelConfig(enabled = enabled)
    )

    @Test
    fun `insert keeps the first appearance of an event and groups by entity`() {
        val rec = EventHistoryRecorder(config(true), db)
        rec.record("gaming", listOf(item("silksong-announce", date = "2027")), now.minusDays(400))
        rec.record("gaming", listOf(item("silksong-announce", type = "release_date", date = "2027-02-14")), now)
        rec.record("gaming", listOf(item("silksong-trailer")), now.minusDays(100))
        rec.record("gaming", listOf(item("other", subject = "Hades II")), now)

        val bySilksong = db.fetchEventHistoryByEntities("gaming", listOf("hollow_knight_silksong", ""))
        val rows = bySilksong.getValue("hollow_knight_silksong")
        assertEquals(listOf("silksong-announce", "silksong-trailer"), rows.map { it.eventKey })
        assertEquals("major_announcement", rows[0].eventType)
        assertEquals("2027", rows[0].eventDate)
        assertEquals(now.minusDays(400), rows[0].coveredAt)
        assertEquals(1, bySilksong.size)

        assertEquals(listOf("silksong-trailer", "other"), db.fetchEventHistorySince("gaming", now.minusDays(200)).map { it.eventKey })
        assertEquals(now.minusDays(400), db.earliestEventHistory("gaming"))
        assertNull(db.earliestEventHistory("tech"))

        db.deleteOldEventHistory(365)
        assertEquals(listOf("silksong-trailer", "other"), db.fetchEventHistorySince("gaming", now.minusDays(1000)).map { it.eventKey })
    }

    @Test
    fun `nothing is written with storyIntel off`() {
        EventHistoryRecorder(config(false), db).record("gaming", listOf(item("a")), now)
        assertTrue(db.isEventHistoryEmpty())
    }

    @Test
    fun `warm-up copies covered and duplicate events once, the earliest sighting first`() {
        db.insertCoveredEvents(listOf(
            CoveredEventRow("gaming", "silksong-date", "Hollow Knight: Silksong", "Hollow Knight", "release_date", "fact", 5, 7, 6, "u", now.minusDays(10))
        ))
        db.saveRejectedEvents(listOf(
            RejectedEventRow(category = "gaming", eventKey = "silksong-date", subject = "Hollow Knight: Silksong", coreFact = "dup", status = "duplicate", extractedAt = now.minusDays(20)),
            RejectedEventRow(category = "gaming", eventKey = "silksong-rumor", subject = "Hollow Knight: Silksong", eventType = "rumor", coreFact = "dup", status = "duplicate", extractedAt = now.minusDays(5)),
            RejectedEventRow(category = "gaming", eventKey = "junk", coreFact = "x", status = "rejected", extractedAt = now.minusDays(5))
        ))
        val rec = EventHistoryRecorder(config(true), db)

        assertEquals(2 + 1, rec.backfillIfEmpty())
        val rows = db.fetchEventHistorySince("gaming", now.minusDays(100))
        assertEquals(listOf("silksong-date" to now.minusDays(20), "silksong-rumor" to now.minusDays(5)), rows.map { it.eventKey to it.coveredAt })
        assertEquals(setOf("hollow_knight_silksong"), rows.map { it.entityKey }.toSet())

        assertEquals(0, rec.backfillIfEmpty())
        assertEquals(2, db.fetchEventHistorySince("gaming", now.minusDays(100)).size)
    }
}
