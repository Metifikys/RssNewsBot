package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Per-category scheduler cadence: `categories.<name>.intervalMinutes` and `scheduler.perCategory`. */
class CategoryScheduleConfigTest {

    private fun load(yaml: String): AppConfig {
        val file = File.createTempFile("config-schedule-test", ".yaml")
        file.writeText(yaml.trimIndent())
        try {
            return ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    private fun yaml(techInterval: String, perCategory: String = "") = """
        telegram:
          botToken: "tok"
        openai:
          apiKey: "sk-test"
        database:
          path: "test.db"
        scheduler:
          intervalMinutes: 30
          $perCategory
        categories:
          tech:
            emoji: "💻"
            channelId: "@tech"
            $techInterval
            feeds:
              - https://example.com/tech
          science:
            emoji: "🔬"
            channelId: "@science"
            feeds:
              - https://example.com/science
    """

    @Test
    fun `per-category interval is parsed and absent interval stays null`() {
        val config = load(yaml(techInterval = "intervalMinutes: 15"))

        assertEquals(15L, config.categories.getValue("tech").intervalMinutes)
        assertNull(config.categories.getValue("science").intervalMinutes)
        assertTrue(config.scheduler.perCategory, "per-category mode is the default")
    }

    @Test
    fun `perCategory can be switched off for the legacy global cycle`() {
        val config = load(yaml(techInterval = "", perCategory = "perCategory: false"))

        assertEquals(false, config.scheduler.perCategory)
    }

    @Test
    fun `zero or negative per-category interval is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { load(yaml(techInterval = "intervalMinutes: 0")) }
        assertTrue(ex.message.orEmpty().contains("categories.tech.intervalMinutes"), "got: ${ex.message}")
    }
}
