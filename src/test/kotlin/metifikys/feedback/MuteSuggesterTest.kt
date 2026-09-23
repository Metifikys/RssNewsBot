package metifikys.feedback

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import metifikys.config.AdminCommandsConfig
import metifikys.config.AdminConfig
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.MuteSuggestionsConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.db.AudienceAffinityRow
import metifikys.db.CoveredEventRow
import metifikys.db.MuteSuggestionsTable
import metifikys.db.NewsDatabase
import metifikys.db.RejectedEventRow
import metifikys.digest.MuteRegistry
import metifikys.model.Article
import metifikys.telegram.AdminAuth
import metifikys.telegram.AdminCallbackRouter
import metifikys.telegram.IncomingCallback
import metifikys.telegram.TelegramSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MuteSuggesterTest {

    private lateinit var dbFile: File
    private lateinit var db: NewsDatabase
    private lateinit var sender: TelegramSender
    private lateinit var mutes: MuteRegistry
    private val now = LocalDateTime.now()

    private val config = AppConfig(
        telegram = TelegramConfig(botToken = "t", updatesPolling = true),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = "x.db"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf(
            "gaming" to CategoryConfig(emoji = "🎮", feeds = listOf(FeedConfig("https://e/rss")), channelId = "@g"),
            "science" to CategoryConfig(emoji = "🔬", feeds = listOf(FeedConfig("https://e/rss2")), channelId = "@s")
        ),
        admin = AdminConfig(
            statusChatId = "555", userIds = listOf(7L), commands = AdminCommandsConfig(enabled = true),
            muteSuggestions = MuteSuggestionsConfig(enabled = true, minRejected = 3)
        )
    )

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("mute-suggester-test", ".db")
        db = NewsDatabase(dbFile.absolutePath)
        sender = mockk(relaxed = true)
        every { sender.sendMessageWithKeyboard(any(), any(), any(), any(), any()) } returns 900L
        mutes = MuteRegistry(config, db)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun affinity(key: String, score: Double, nTone: Double, category: String = "gaming") =
        AudienceAffinityRow(category, "franchise", key, n = 30.0, nTone = nTone, engagementZ = 0.0, sentiment = score, score = score, updatedAt = now)

    private fun rejected(franchise: String, times: Int, category: String = "gaming") = (1..times).map {
        RejectedEventRow(category = category, eventKey = "$franchise-$it", franchise = franchise, coreFact = "", status = "rejected", extractedAt = now)
    }

    private fun titles(vararg t: String, category: String = "gaming") =
        db.insertArticles(t.map { Article(category, it, "https://a/${it.hashCode()}", "") })

    @Test
    fun `franchise keys become keywords, generic ones are dropped`() {
        assertEquals("gta", MuteSuggester.keywordFor("GTA / Rockstar / Take-Two"))
        assertEquals("disney dreamlight valley", MuteSuggester.keywordFor("disney dreamlight valley"))
        assertNull(MuteSuggester.keywordFor("cod / activision"))
        assertNull(MuteSuggester.keywordFor("ai"))
        assertNull(MuteSuggester.keywordFor("x1"))
    }

    @Test
    fun `candidates come from dislike first, then never-through franchises, one per keyword`() {
        val c = MuteSuggester.candidates(
            affinity = listOf(
                affinity("palworld", -0.09, 4.0),                 // above maxScore -0.15 → no
                affinity("diablo / blizzard", -0.30, 3.0),
                affinity("halo", -0.50, 1.0),                    // too little evidence
                affinity("starfield", -0.20, 2.0)
            ),
            rejected = mapOf(("gaming" to "gta / rockstar") to 68, ("gaming" to "gta") to 10, ("gaming" to "zelda") to 30, ("gaming" to "rare") to 2),
            covered = mapOf(("gaming" to "zelda") to 1),
            cfg = MuteSuggestionsConfig(enabled = true, minRejected = 3),
            categories = setOf("gaming")
        )
        assertEquals(listOf("diablo", "starfield", "gta"), c.map { it.keyword })
        assertEquals(listOf("affinity", "affinity", "rejected"), c.map { it.kind })
        assertTrue("68 відхилено" in c.last().reason, c.last().reason)
    }

    @Test
    fun `a run skips zero-hit, muted and recently rejected keywords and posts silent proposals`() {
        db.replaceAudienceAffinity(listOf(affinity("diablo", -0.3, 3.0), affinity("ghostword", -0.4, 3.0), affinity("cyberpunk", -0.2, 3.0)))
        db.saveRejectedEvents(rejected("palworld", 5))
        titles("Diablo IV season 9", "Palworld 1.0 date", "Cyberpunk 2077 patch")
        mutes.add("cyberpunk", null, 7, "command")

        val s = MuteSuggester(config, db, mutes, sender) { now }
        assertEquals(2, s.run(now))
        val rows = db.fetchMuteSuggestions()
        assertEquals(listOf("diablo", "palworld"), rows.map { it.keyword })
        assertEquals(1, rows.first().hits30d)
        assertEquals(900L, rows.first().messageId)
        verify { sender.sendMessageWithKeyboard("555", match { "«diablo» (gaming)" in it }, any(), true, false) }

        // Nothing new the second time: both are pending.
        assertEquals(0, s.run(now))

        // A rejected one stays quiet for 90 days.
        s.handleCallback(IncomingCallback(1, "cb", 7, "ms:no:${rows[1].id}", 555, 900), listOf("no", rows[1].id.toString()))
        assertEquals(MuteSuggestionsTable.STATUS_REJECTED, db.fetchMuteSuggestion(rows[1].id)?.status)
        assertEquals(0, s.run(now.plusDays(30)))
    }

    @Test
    fun `approving writes the mute through the registry, a second press changes nothing`() {
        db.replaceAudienceAffinity(listOf(affinity("diablo", -0.3, 3.0)))
        titles("Diablo IV season 9")
        val s = MuteSuggester(config, db, mutes, sender) { now }
        s.run(now)
        val id = db.fetchMuteSuggestions().single().id

        val router = AdminCallbackRouter(AdminAuth(config.admin), sender, Executor { it.run() })
        router.register("ms") { cb, parts -> s.handleCallback(cb, parts) }
        router.onCallback(IncomingCallback(1, "cb-stranger", 99, "ms:cat:$id", 555, 900))
        assertEquals(MuteSuggestionsTable.STATUS_PENDING, db.fetchMuteSuggestion(id)?.status, "strangers are ignored")

        router.onCallback(IncomingCallback(2, "cb1", 7, "ms:cat:$id", 555, 900))
        assertEquals(MuteSuggestionsTable.STATUS_APPROVED, db.fetchMuteSuggestion(id)?.status)
        assertEquals(listOf("diablo" to "gaming"), db.fetchAdminMutes().map { it.keyword to it.category })
        assertEquals("suggestion", db.fetchAdminMutes().single().source)
        assertTrue(mutes.filterFor("gaming").matches(Article("gaming", "Diablo news", "https://a/x", "")))
        verify { sender.editMessageWithKeyboard("555", 900, match { "✅ зам'ючено: gaming" in it }, null, false) }

        router.onCallback(IncomingCallback(3, "cb2", 7, "ms:glob:$id", 555, 900))
        assertEquals(1, db.fetchAdminMutes().size, "already decided — no global mute on a second press")
    }

    @Test
    fun `runIfDue respects everyDays`() {
        val s = MuteSuggester(config, db, mutes, sender) { now }
        s.runIfDue()
        val first = db.getState(MuteSuggester.STATE_KEY)
        assertEquals(now.toString(), first)
        db.replaceAudienceAffinity(listOf(affinity("diablo", -0.3, 3.0)))
        titles("Diablo IV")
        s.runIfDue()
        assertTrue(db.fetchMuteSuggestions().isEmpty(), "not due yet")
        MuteSuggester(config, db, mutes, sender) { now.plusDays(8) }.runIfDue()
        assertEquals(1, db.fetchMuteSuggestions().size)
    }
}
