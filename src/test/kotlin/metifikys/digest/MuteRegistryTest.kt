package metifikys.digest

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
import metifikys.model.Article
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MuteRegistryTest {

    private lateinit var dbFile: File
    private lateinit var db: NewsDatabase

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("mute-registry-test", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private val config = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = "x.db"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf(
            "gaming" to CategoryConfig(emoji = "🎮", feeds = listOf(FeedConfig("https://e/rss")), channelId = "@g", mute = listOf("cod")),
            "science" to CategoryConfig(emoji = "🔬", feeds = listOf(FeedConfig("https://e/rss2")), channelId = "@s")
        ),
        preferences = PreferencesConfig(mute = listOf("call of duty"))
    )

    private fun art(title: String, category: String = "gaming", link: String = "https://x/${title.hashCode()}") =
        Article(category = category, title = title, link = link, description = "")

    @Test
    fun `filters are config plus DB, global versus category scope`() {
        val reg = MuteRegistry(config, db)
        assertTrue(reg.filterFor("gaming").matches(art("New CoD map")))
        assertTrue(reg.filterFor("science").matches(art("Call of Duty on sale", "science")))
        assertTrue(!reg.filterFor("science").matches(art("Cod stocks recover", "science")), "category mute stays in gaming")

        assertIs<MuteRegistry.AddResult.Added>(reg.add("Palworld", "gaming", 7, "command"))
        assertIs<MuteRegistry.AddResult.Added>(reg.add("diablo", null, 7, "command"))
        assertTrue(reg.filterFor("gaming").matches(art("Palworld 1.0 is out")))
        assertTrue(!reg.filterFor("science").matches(art("Palworld 1.0 is out", "science")))
        assertTrue(reg.filterFor("science").matches(art("Diablo lore explained", "science")))
    }

    @Test
    fun `a write elsewhere shows up only after invalidate`() {
        val reg = MuteRegistry(config, db)
        assertTrue(!reg.filterFor("gaming").matches(art("Palworld news")))
        db.insertAdminMute("palworld", "gaming", 1, "command")   // behind the registry's back
        assertTrue(!reg.filterFor("gaming").matches(art("Palworld news")), "cached")
        reg.invalidate()
        assertTrue(reg.filterFor("gaming").matches(art("Palworld news")))
    }

    @Test
    fun `duplicates, validation and removal of config words`() {
        val reg = MuteRegistry(config, db)
        assertIs<MuteRegistry.AddResult.AlreadyMuted>(reg.add("COD", "gaming", 7, "command"))
        assertIs<MuteRegistry.AddResult.AlreadyMuted>(reg.add("call of duty", "gaming", 7, "command"), "global covers a category")
        assertIs<MuteRegistry.AddResult.Added>(reg.add("cod", null, 7, "command"), "a category entry does not cover global")
        assertIs<MuteRegistry.AddResult.Invalid>(reg.add("x", null, 7, "command"))
        assertIs<MuteRegistry.AddResult.Invalid>(reg.add("2077", null, 7, "command"))
        assertIs<MuteRegistry.AddResult.Invalid>(reg.add("halo", "nope", 7, "command"))

        val inConfig = reg.remove("call of duty", null, 7)
        assertIs<MuteRegistry.RemoveResult.InConfig>(inConfig)
        assertEquals(MuteRegistry.Origin.CONFIG_GLOBAL, inConfig.entry.origin)
        assertEquals(MuteRegistry.RemoveResult.Removed(1), reg.remove("COD", null, 7))
        assertEquals(MuteRegistry.RemoveResult.NotFound, reg.remove("halo", null, 7))
    }

    @Test
    fun `without a DB only the config lists exist`() {
        val reg = MuteRegistry(config)
        assertEquals(2, reg.entries().size)
        assertIs<MuteRegistry.AddResult.Invalid>(reg.add("diablo", null, 7, "command"))
    }

    @Test
    fun `preview counts matching articles and the published ones`() {
        db.insertArticles(
            listOf(
                art("Diablo IV season", link = "https://a/1"),
                art("Diablo patch notes", link = "https://a/2"),
                art("Halo news", link = "https://a/3"),
                art("Diablo in science?", category = "science", link = "https://a/4")
            )
        )
        db.insertDigestMessages(listOf(DigestMessageRow(-1, 1, "gaming", "e", "https://a/1\nhttps://a/3", LocalDateTime.now())))
        val reg = MuteRegistry(config, db)
        assertEquals(MuteRegistry.Preview(2, 1), reg.preview("diablo", "gaming", 7))
        assertEquals(MuteRegistry.Preview(3, 1), reg.preview("diablo", null, 7))
    }

    @Test
    fun `event-type mutes are per category, apart from the keyword lists, and the same word may be both`() {
        val reg = MuteRegistry(config, db)
        assertIs<MuteRegistry.AddResult.Added>(reg.addEventType(" Rumor ", "gaming", 7, "inspect"))
        assertIs<MuteRegistry.AddResult.AlreadyMuted>(reg.addEventType("rumor", "gaming", 7, "command"))
        assertIs<MuteRegistry.AddResult.Invalid>(reg.addEventType("rumor", "nope", 7, "command"))
        assertIs<MuteRegistry.AddResult.Invalid>(reg.addEventType("two words", "gaming", 7, "command"))
        assertEquals(setOf("rumor"), reg.mutedEventTypes("gaming"))
        assertEquals(emptySet(), reg.mutedEventTypes("science"))
        assertEquals(2, reg.entries().size, "keyword entries unchanged")

        assertIs<MuteRegistry.AddResult.Added>(reg.add("rumor", "gaming", 7, "command"), "a keyword 'rumor' is a different mute")
        assertTrue(reg.filterFor("gaming").matches(art("A rumor about Halo")))
        assertEquals(MuteRegistry.RemoveResult.Removed(1), reg.removeEventType("RUMOR", "gaming", 7))
        assertEquals(emptySet(), reg.mutedEventTypes("gaming"))
        assertTrue(reg.filterFor("gaming").matches(art("A rumor about Halo")), "the keyword mute stays")
        assertEquals(MuteRegistry.RemoveResult.NotFound, reg.removeEventType("rumor", "gaming", 7))
        assertEquals("keyword", db.fetchAdminMutes().single().kind)
    }

    @Test
    fun `without a DB there are no type mutes`() {
        val reg = MuteRegistry(config)
        assertEquals(emptySet(), reg.mutedEventTypes("gaming"))
        assertIs<MuteRegistry.AddResult.Invalid>(reg.addEventType("rumor", "gaming", 7, "command"))
    }
}
