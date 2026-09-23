package metifikys.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import metifikys.config.AdminCommandsConfig
import metifikys.config.AdminConfig
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.PreferencesConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.digest.MuteRegistry
import metifikys.digest.PauseStore
import metifikys.model.Article
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminCommandsTest {

    private lateinit var dbFile: File
    private lateinit var db: NewsDatabase
    private val replies = mutableListOf<String>()
    private lateinit var router: AdminCommandRouter
    private lateinit var mutes: MuteRegistry
    private lateinit var pauses: PauseStore
    private var runnerCalls = mutableListOf<Pair<String, Boolean>>()
    private var lastListener: RunListener? = null

    private val config = AppConfig(
        telegram = TelegramConfig(botToken = "t", updatesPolling = true),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = "x.db"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf(
            "gaming" to CategoryConfig(emoji = "🎮", feeds = listOf(FeedConfig("https://e/rss")), channelId = "@g"),
            "tech" to CategoryConfig(emoji = "💻", feeds = listOf(FeedConfig("https://e/rss2")), channelId = "@t")
        ),
        preferences = PreferencesConfig(mute = listOf("call of duty")),
        admin = AdminConfig(statusChatId = "7", userIds = listOf(7L), commands = AdminCommandsConfig(enabled = true))
    )

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("admin-commands-test", ".db")
        db = NewsDatabase(dbFile.absolutePath)
        val sender = mockk<TelegramSender>()
        val text = slot<String>()
        every { sender.sendMessageWithKeyboard(any(), capture(text), any(), any(), any()) } answers { replies += text.captured; 1L }
        every { sender.sendMessageReturningId(any(), capture(text)) } answers { replies += text.captured; 2L }
        router = AdminCommandRouter(AdminAuth(config.admin), sender, Executor { it.run() })
        mutes = MuteRegistry(config, db)
        pauses = PauseStore(db)
        AdminCommands(
            config, db, mutes, pauses, StatusCommand(config, db, pauseStore = pauses), sender,
            runner = { cat, withFetch, listener ->
                runnerCalls += cat to withFetch
                lastListener = listener
                RunQueued.QUEUED
            }
        ).registerAll(router)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun send(text: String) = router.onMessage(
        IncomingMessage(1, 7, "private", 7, 1, Instant.now().epochSecond, text)
    )

    @Test
    fun `mute arguments take the last word as scope only when it is a category`() {
        val cats = setOf("gaming", "tech")
        assertEquals("diablo" to "gaming", AdminCommands.parseMuteArgs(listOf("diablo", "gaming"), cats))
        assertEquals("call of duty" to null, AdminCommands.parseMuteArgs(listOf("call", "of", "duty"), cats))
        assertEquals("gaming" to null, AdminCommands.parseMuteArgs(listOf("gaming"), cats))
        assertEquals("pc gaming" to null, AdminCommands.parseMuteArgs(listOf("pc gaming"), cats), "quoted phrase stays whole")
        assertNull(AdminCommands.parseMuteArgs(emptyList(), cats))
    }

    @Test
    fun `mute with preview, duplicate, unmute and the config hint`() {
        db.insertArticles(listOf(Article("gaming", "Diablo IV season", "https://a/1", ""), Article("gaming", "Diablo fix", "https://a/2", "")))
        db.insertDigestMessages(listOf(DigestMessageRow(-1, 1, "gaming", "e", "https://a/2", LocalDateTime.now())))

        send("/mute diablo gaming")
        assertTrue(replies.last().startsWith("🔇 «diablo» (gaming): за 7 днів збігів 2, з них у дайджесті 1"), replies.last())
        assertTrue(mutes.filterFor("gaming").matches(Article("gaming", "Diablo news", "https://a/3", "")))

        send("/mute Diablo gaming")
        assertTrue(replies.last().startsWith("Вже є: «diablo» (gaming, бот"), replies.last())

        send("/unmute diablo")
        assertTrue("/unmute \"diablo\" gaming" in replies.last(), replies.last())
        send("/unmute diablo gaming")
        assertEquals("🔊 «diablo» (gaming) знято.", replies.last())

        send("/unmute \"call of duty\"")
        assertTrue("config.yaml (preferences.mute)" in replies.last(), replies.last())
    }

    @Test
    fun `mutes lists sources and yaml prints a valid config fragment of the DB mutes`() {
        send("/mute palworld gaming")
        send("/mute «star \"wars\"»")
        send("/mutes")
        val list = replies.last()
        assertTrue("call of duty — config.yaml" in list && "palworld — бот" in list, list)

        send("/mutes yaml")
        val yaml = replies.last().substringAfter("\n\n")
        val tree = ObjectMapper(YAMLFactory()).readTree(yaml)
        assertEquals("palworld", tree["categories"]["gaming"]["mute"][0].asText())
        assertEquals(1, tree["preferences"]["mute"].size())
    }

    @Test
    fun `pause and resume a category, run queues with fetch`() {
        send("/pause tech 6h")
        assertTrue(replies.last().startsWith("⏸ tech: дайджест на паузі до "), replies.last())
        assertTrue(pauses.pausedUntil("tech")?.until != null)
        send("/pause tech soon")
        assertTrue(replies.last().startsWith("Не розумію тривалість"), replies.last())
        send("/resume tech")
        assertTrue(replies.last().startsWith("▶️ tech: пауза знята. У черзі на дайджест: 0"), replies.last())
        assertNull(pauses.pausedUntil("tech"))
        send("/pause nope")
        assertTrue(replies.last().startsWith("Невідома категорія «nope»"), replies.last())

        send("/run gaming fetch")
        assertEquals(listOf("gaming" to true), runnerCalls)
        assertEquals("▶️ gaming: у черзі (спершу збір статей)", replies.last())
        lastListener!!.started()
        db.insertDigestMessages(listOf(DigestMessageRow(-1, 9, "gaming", "e", "https://a/9", LocalDateTime.now().plusSeconds(1))))
        lastListener!!.finished()
        assertEquals("✅ gaming: 1 пост(ів)", replies.last())
    }

    @Test
    fun `status is sent as a snapshot with the pause marker`() {
        pauses.pause("gaming", null)
        send("/status")
        assertTrue("⏸ paused until /resume" in replies.last(), replies.last())
    }
}
