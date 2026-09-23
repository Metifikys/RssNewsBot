package metifikys.digest

import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.CrossLinksConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.SchedulerConfig
import metifikys.config.SemanticDedupConfig
import metifikys.config.TelegramConfig
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.model.ShortlistItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossLinksTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-crosslinks", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private val model = "text-embedding-3-small"

    private fun category(emoji: String, channelId: String) = CategoryConfig(
        emoji = emoji,
        feeds = listOf(FeedConfig("https://example.com/rss")),
        channelId = channelId,
        semanticDedup = SemanticDedupConfig(eventEnabled = true)
    )

    private fun config(techChannel: String = "@tech_news") = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf(
            "gaming" to category("🎮", "@gaming_news"),
            "tech" to category("🖥", techChannel)
        )
    )

    private fun vec(vararg v: Float) = VectorMath.encode(VectorMath.l2Normalize(floatArrayOf(*v)))

    private fun item(key: String) = ShortlistItem(eventKey = key, coreFact = "fact", url = "https://example.com/$key", status = "new")

    private fun sent(category: String, key: String, messageId: Long, chatId: Long = -1001234L) =
        DigestMessageRow(chatId, messageId, category, key, "", LocalDateTime.now().minusHours(2))

    /** gaming "rtx" ≈ tech "gpu" (cos ≈ 0.99), gaming "zelda" is unrelated to both tech events. */
    private fun seed() {
        db.saveEventEmbedding("gaming", "rtx", "RTX 5090 in games", "", model, vec(1f, 0.1f, 0f))
        db.saveEventEmbedding("gaming", "zelda", "Zelda", "", model, vec(0f, 0f, 1f))
        db.saveEventEmbedding("tech", "gpu", "Nvidia [RTX] 5090 launch", "", model, vec(1f, 0f, 0f))
        db.saveEventEmbedding("tech", "phone", "Phone", "", model, vec(0f, 1f, 0f))
    }

    private val cfg = CrossLinksConfig(categories = listOf("tech"), minSimilarity = 0.9)

    @Test
    fun `links the related post of the other channel`() {
        seed()
        db.insertDigestMessages(listOf(sent("tech", "gpu", 77)))
        val lines = CrossLinks(config(), db).resolve("gaming", listOf(item("rtx"), item("zelda")), cfg)
        assertEquals(mapOf("rtx" to "🔗 У 🖥 Tech: [Nvidia (RTX) 5090 launch](https://t.me/tech_news/77)"), lines)
    }

    @Test
    fun `numeric channel id gives a t_me c link`() {
        seed()
        db.insertDigestMessages(listOf(sent("tech", "gpu", 77)))
        val lines = CrossLinks(config(techChannel = "-1001234"), db).resolve("gaming", listOf(item("rtx")), cfg)
        assertEquals("🔗 У 🖥 Tech: [Nvidia (RTX) 5090 launch](https://t.me/c/1234/77)", lines["rtx"])
    }

    @Test
    fun `an event that never reached the other channel is not linked`() {
        seed()
        assertTrue(CrossLinks(config(), db).resolve("gaming", listOf(item("rtx")), cfg).isEmpty())
    }

    @Test
    fun `below the threshold, another model, logOnly and a shared channel give no line`() {
        seed()
        db.insertDigestMessages(listOf(sent("tech", "gpu", 77)))
        val links = CrossLinks(config(), db)
        assertTrue(links.resolve("gaming", listOf(item("rtx")), cfg.copy(minSimilarity = 0.999)).isEmpty())
        assertTrue(links.resolve("gaming", listOf(item("rtx")), cfg.copy(logOnly = true)).isEmpty())
        assertTrue(CrossLinks(config(techChannel = "@gaming_news"), db).resolve("gaming", listOf(item("rtx")), cfg).isEmpty())
        db.saveEventEmbedding("tech", "gpu", "Nvidia RTX 5090 launch", "", "other-model", vec(1f, 0f, 0f))
        assertTrue(links.resolve("gaming", listOf(item("rtx")), cfg).isEmpty())
    }

    @Test
    fun `the best posted candidate wins over a better unposted one`() {
        seed()
        db.saveEventEmbedding("tech", "gpu-review", "RTX 5090 review", "", model, vec(1f, 0.05f, 0f))
        db.insertDigestMessages(listOf(sent("tech", "gpu", 77)))
        val lines = CrossLinks(config(), db).resolve("gaming", listOf(item("rtx")), cfg)
        assertEquals("🔗 У 🖥 Tech: [Nvidia (RTX) 5090 launch](https://t.me/tech_news/77)", lines["rtx"])
    }

    @Test
    fun `line cuts a long subject and falls back for a blank one`() {
        val long = CrossLinks.line("🖥", "tech", "x".repeat(100), "https://t.me/a/1")
        assertEquals("🔗 У 🖥 Tech: [${"x".repeat(79)}…](https://t.me/a/1)", long)
        assertEquals("🔗 У 🖥 Tech: [пост](https://t.me/a/1)", CrossLinks.line("🖥", "tech", " ", "https://t.me/a/1"))
    }
}
