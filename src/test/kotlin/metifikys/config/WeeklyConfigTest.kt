package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WeeklyConfigTest {

    private fun load(yaml: String): AppConfig {
        val file = File.createTempFile("weekly-config-test", ".yaml")
        file.writeText(yaml)
        return try {
            ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    /** Minimal valid config, every line at column 0 so an appended `weekly:` block stays valid YAML. */
    private val base: String = buildString {
        appendLine("telegram:")
        appendLine("  botToken: \"t\"")
        appendLine("openai:")
        appendLine("  apiKey: \"sk-test\"")
        appendLine("database:")
        appendLine("  path: \"weekly-cfg-test.db\"")
        appendLine("scheduler:")
        appendLine("  intervalMinutes: 60")
        appendLine("categories:")
        appendLine("  tech:")
        appendLine("    emoji: \"T\"")
        appendLine("    channelId: \"@tech\"")
        appendLine("    feeds:")
        appendLine("      - https://example.com/rss")
    }

    private fun withWeekly(vararg lines: String): String =
        base + "weekly:\n" + lines.joinToString("") { "  $it\n" }

    @Test
    fun `absent weekly block leaves weekly null`() {
        assertNull(load(base).weekly)
    }

    @Test
    fun `valid weekly block parses and exposes day and time`() {
        val cfg = load(
            withWeekly(
                "enabled: true",
                "dayOfWeek: sunday",
                "time: \"18:30\"",
                "topN: 3",
                "candidatePoolSize: 10",
                "categories:",
                "  - tech"
            )
        )
        val w = assertNotNull(cfg.weekly)
        assertEquals(DayOfWeek.SUNDAY, w.dayOfWeekParsed())
        assertEquals(LocalTime.of(18, 30), w.timeParsed())
    }

    @Test
    fun `disabled weekly block skips validation`() {
        // Bad day/time but enabled=false → no validation runs.
        val cfg = load(withWeekly("enabled: false", "dayOfWeek: notaday", "time: \"99:99\""))
        assertNotNull(cfg.weekly)
    }

    @Test
    fun `invalid dayOfWeek throws`() {
        assertThrows<IllegalArgumentException> {
            load(withWeekly("enabled: true", "dayOfWeek: someday"))
        }
    }

    @Test
    fun `invalid time throws`() {
        assertThrows<IllegalArgumentException> {
            load(withWeekly("enabled: true", "time: \"25:00\""))
        }
    }

    @Test
    fun `candidatePoolSize below topN throws`() {
        assertThrows<IllegalArgumentException> {
            load(withWeekly("enabled: true", "topN: 5", "candidatePoolSize: 3"))
        }
    }

    @Test
    fun `unknown category in weekly categories throws`() {
        assertThrows<IllegalArgumentException> {
            load(withWeekly("enabled: true", "categories:", "  - nope"))
        }
    }

    @Test
    fun `clusterThreshold out of range throws`() {
        assertThrows<IllegalArgumentException> {
            load(withWeekly("enabled: true", "clusterThreshold: 1.5"))
        }
    }
}
