package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminConfigTest {

    private fun load(telegramExtra: String = "", admin: String = ""): AppConfig {
        val yaml = "telegram:\n  botToken: \"t\"\n$telegramExtra" + """
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
        """.trimIndent() + "\n" + admin
        val file = File.createTempFile("admin-config-test", ".yaml")
        file.writeText(yaml)
        return try {
            ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `admin commands are off by default`() {
        val c = load()
        assertFalse(c.admin.commands.enabled)
        assertTrue(c.admin.userIds.isEmpty())
    }

    @Test
    fun `commands with polling and admins load`() {
        val c = load(
            telegramExtra = "  updatesPolling: true\n",
            admin = "admin:\n  statusChatId: \"123\"\n  userIds: [123, 456]\n  commands:\n    enabled: true\n"
        )
        assertTrue(c.admin.commands.enabled)
        assertEquals(listOf(123L, 456L), c.admin.userIds)
    }

    @Test
    fun `the post inspector is on by default and can be switched off`() {
        assertTrue(load().admin.inspect.enabled)
        val off = load(
            telegramExtra = "  updatesPolling: true\n",
            admin = "admin:\n  statusChatId: \"123\"\n  userIds: [123]\n  commands:\n    enabled: true\n  inspect:\n    enabled: false\n"
        )
        assertFalse(off.admin.inspect.enabled)
    }

    @Test
    fun `commands without updatesPolling are rejected`() {
        val e = assertThrows<IllegalArgumentException> {
            load(admin = "admin:\n  userIds: [123]\n  commands:\n    enabled: true\n")
        }
        assertTrue("updatesPolling" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `mute suggestions need the commands and an admin chat`() {
        val e = assertThrows<IllegalArgumentException> {
            load(admin = "admin:\n  statusChatId: \"1\"\n  muteSuggestions:\n    enabled: true\n")
        }
        assertTrue("admin.commands.enabled" in e.message.orEmpty(), e.message)
        val c = load(
            telegramExtra = "  updatesPolling: true\n",
            admin = "admin:\n  statusChatId: \"1\"\n  userIds: [1]\n  commands:\n    enabled: true\n" +
                "  muteSuggestions:\n    enabled: true\n    everyDays: 3\n"
        )
        assertEquals(3L, c.admin.muteSuggestions.everyDays)
        assertEquals(5, c.admin.muteSuggestions.maxPerRun)
    }

    @Test
    fun `moderation needs the commands and known categories`() {
        assertThrows<IllegalArgumentException> {
            load(admin = "admin:\n  statusChatId: \"1\"\n  moderation:\n    enabled: true\n")
        }
        val commands = "admin:\n  statusChatId: \"1\"\n  userIds: [1]\n  commands:\n    enabled: true\n"
        val e = assertThrows<IllegalArgumentException> {
            load(telegramExtra = "  updatesPolling: true\n", admin = commands + "  moderation:\n    enabled: true\n    categories: [nope]\n")
        }
        assertTrue("nope" in e.message.orEmpty(), e.message)
        val c = load(telegramExtra = "  updatesPolling: true\n", admin = commands + "  moderation:\n    enabled: true\n    categories: [gaming]\n")
        assertEquals(listOf("gaming"), c.admin.moderation.categories)
        assertEquals(3.0, c.feedback.explicitWeight)
    }

    @Test
    fun `commands without userIds are rejected`() {
        val e = assertThrows<IllegalArgumentException> {
            load(telegramExtra = "  updatesPolling: true\n", admin = "admin:\n  commands:\n    enabled: true\n")
        }
        assertTrue("userIds" in e.message.orEmpty(), e.message)
    }
}
