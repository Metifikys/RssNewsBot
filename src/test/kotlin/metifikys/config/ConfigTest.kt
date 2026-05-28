package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigTest {

    @Test
    fun `load valid yaml config`() {
        val yaml = """
            telegram:
              botToken: "test-token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 180
            categories:
              tech:
                emoji: "💻"
                channelId: "@tech_channel"
                feeds:
                  - https://example.com/rss
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        val config = ConfigLoader.load(file.absolutePath)

        assertEquals("test-token", config.telegram.botToken)
        assertEquals("sk-test", config.openai.apiKey)
        assertEquals("test.db", config.database.path)
        assertEquals(180L, config.scheduler.intervalMinutes)
        assertTrue(config.categories.containsKey("tech"))
        assertEquals("💻", config.categories["tech"]?.emoji)
        assertEquals("@tech_channel", config.categories["tech"]?.channelId)
        assertEquals(listOf(FeedConfig("https://example.com/rss")), config.categories["tech"]?.feeds)

        file.delete()
    }

    @Test
    fun `missing file throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            ConfigLoader.load("non-existent-file.yaml")
        }
    }

    @Test
    fun `category channelId parsed correctly`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@tech_chan"
                feeds:
                  - https://example.com/rss
              gaming:
                emoji: "🎮"
                channelId: "-1001234567890"
                feeds:
                  - https://example.com/gaming
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        val config = ConfigLoader.load(file.absolutePath)
        assertEquals("@tech_chan", config.categories["tech"]?.channelId)
        assertEquals("-1001234567890", config.categories["gaming"]?.channelId)

        file.delete()
    }

    @Test
    fun `semanticDedup block parsed with custom values`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@ch"
                feeds:
                  - https://example.com/rss
                semanticDedup:
                  enabled: true
                  threshold: 0.85
                  windowDays: 7
                  topK: 3
                  maxRecent: 500
                  model: "text-embedding-3-large"
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        val cfg = ConfigLoader.load(file.absolutePath)
        val sd = cfg.categories["tech"]?.semanticDedup
        assertEquals(true, sd?.enabled)
        assertEquals(0.85, sd?.threshold)
        assertEquals(7L, sd?.windowDays)
        assertEquals(3, sd?.topK)
        assertEquals(500, sd?.maxRecent)
        assertEquals("text-embedding-3-large", sd?.model)

        file.delete()
    }

    @Test
    fun `semanticDedup defaults applied when only enabled is set`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@ch"
                feeds:
                  - https://example.com/rss
                semanticDedup:
                  enabled: true
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        val cfg = ConfigLoader.load(file.absolutePath)
        val sd = cfg.categories["tech"]?.semanticDedup
        assertEquals(true, sd?.enabled)
        assertEquals(0.92, sd?.threshold)
        assertEquals(14L, sd?.windowDays)
        assertEquals(5, sd?.topK)
        assertEquals(2000, sd?.maxRecent)
        assertEquals("text-embedding-3-small", sd?.model)

        file.delete()
    }

    @Test
    fun `semanticDedup absent leaves field null`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@ch"
                feeds:
                  - https://example.com/rss
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        val cfg = ConfigLoader.load(file.absolutePath)
        assertEquals(null, cfg.categories["tech"]?.semanticDedup)

        file.delete()
    }

    @Test
    fun `semanticDedup threshold above 1 throws`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@ch"
                feeds:
                  - https://example.com/rss
                semanticDedup:
                  enabled: true
                  threshold: 1.5
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        assertThrows<IllegalArgumentException> {
            ConfigLoader.load(file.absolutePath)
        }

        file.delete()
    }

    @Test
    fun `semanticDedup hardThreshold parsed correctly`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@ch"
                feeds:
                  - https://example.com/rss
                semanticDedup:
                  enabled: true
                  threshold: 0.75
                  hardThreshold: 0.85
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)
        val cfg = ConfigLoader.load(file.absolutePath)
        val sd = cfg.categories["tech"]?.semanticDedup
        assertEquals(0.85, sd?.hardThreshold)
        assertEquals(0.75, sd?.threshold)
        file.delete()
    }

    @Test
    fun `semanticDedup hardThreshold below threshold throws`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@ch"
                feeds:
                  - https://example.com/rss
                semanticDedup:
                  enabled: true
                  threshold: 0.80
                  hardThreshold: 0.70
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        assertThrows<IllegalArgumentException> {
            ConfigLoader.load(file.absolutePath)
        }

        file.delete()
    }

    @Test
    fun `semanticDedup hardThreshold above 1 throws`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@ch"
                feeds:
                  - https://example.com/rss
                semanticDedup:
                  enabled: true
                  hardThreshold: 1.2
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        assertThrows<IllegalArgumentException> {
            ConfigLoader.load(file.absolutePath)
        }

        file.delete()
    }

    @Test
    fun `semanticDedup zero windowDays throws`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: "@ch"
                feeds:
                  - https://example.com/rss
                semanticDedup:
                  enabled: true
                  windowDays: 0
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        assertThrows<IllegalArgumentException> {
            ConfigLoader.load(file.absolutePath)
        }

        file.delete()
    }

    @Test
    fun `blank channelId throws IllegalArgumentException`() {
        val yaml = """
            telegram:
              botToken: "token"
            openai:
              apiKey: "sk-test"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              tech:
                emoji: "💻"
                channelId: ""
                feeds:
                  - https://example.com/rss
        """.trimIndent()

        val file = File.createTempFile("config-test", ".yaml")
        file.writeText(yaml)

        assertThrows<IllegalArgumentException> {
            ConfigLoader.load(file.absolutePath)
        }

        file.delete()
    }
}
