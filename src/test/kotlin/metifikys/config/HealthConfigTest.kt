package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthConfigTest {

    private fun load(extra: String): AppConfig {
        val yaml = """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              gaming:
                emoji: "🎮"
                channelId: "@g"
                feeds:
                  - https://example.com/rss
        """.trimIndent() + "\n" + extra
        val file = File.createTempFile("health-config-test", ".yaml")
        file.writeText(yaml)
        return try {
            ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `off by default`() {
        val c = load("")
        assertFalse(c.health.enabled)
        assertTrue(c.health.cliProbe.enabled)
        assertEquals(6, c.health.cliProbe.intervalHours)
    }

    @Test
    fun `enabled with an admin chat loads`() {
        val c = load("admin:\n  statusChatId: \"42\"\nhealth:\n  enabled: true\n  claudeTokenExpires: \"2027-08-01\"\n  cliProbe: { intervalHours: 12, claudeModel: haiku }\n")
        assertTrue(c.health.enabled)
        assertEquals(12, c.health.cliProbe.intervalHours)
        assertEquals("haiku", c.health.cliProbe.claudeModel)
    }

    @Test
    fun `enabled without an admin chat is rejected`() {
        val e = assertThrows<IllegalArgumentException> { load("health:\n  enabled: true\n") }
        assertTrue("admin.statusChatId" in e.message!!, e.message)
    }

    @Test
    fun `bad expiry date and intervals are rejected`() {
        assertThrows<IllegalArgumentException> { load("health:\n  claudeTokenExpires: \"01.08.2027\"\n") }
        assertThrows<IllegalArgumentException> { load("health:\n  cliProbe: { intervalHours: 0 }\n") }
        assertThrows<IllegalArgumentException> { load("health:\n  cliProbe: { timeoutSeconds: 1 }\n") }
    }
}
