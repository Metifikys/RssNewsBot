package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `categories.<name>.trackers`: parsing and the load-time rules. */
class TrackersConfigTest {

    private fun load(yaml: String): AppConfig {
        val file = File.createTempFile("config-trackers-test", ".yaml")
        file.writeText(yaml)
        try {
            return ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    private fun yaml(trackers: String, withDedup: Boolean = true) = buildString {
        appendLine("telegram:")
        appendLine("  botToken: \"tok\"")
        appendLine("openai:")
        appendLine("  apiKey: \"sk-test\"")
        appendLine("database:")
        appendLine("  path: \"test.db\"")
        appendLine("scheduler:")
        appendLine("  intervalMinutes: 30")
        appendLine("categories:")
        appendLine("  gaming:")
        appendLine("    emoji: \"🎮\"")
        appendLine("    channelId: \"@gaming\"")
        appendLine("    feeds:")
        appendLine("      - https://example.com/gaming")
        if (withDedup) appendLine("    dedup:\n      promptFile: \"prompts/games-dedup.yaml\"")
        if (trackers.isNotBlank()) appendLine(trackers.trimIndent().prependIndent("    "))
    }

    private val releases = """
        trackers:
          - id: releases
            title: "📅 Найближчі релізи"
            eventTypes: [release_date, delay]
    """

    @Test
    fun `trackers parse with defaults`() {
        val t = load(yaml(releases)).categories.getValue("gaming").trackers.single()
        assertEquals("releases", t.id)
        assertEquals(listOf("release_date", "delay"), t.eventTypes)
        assertEquals(120L, t.horizonDays)
        assertEquals(20, t.maxEntries)
        assertTrue(t.pinned)
        assertEquals(7L, t.releasedRetentionDays)
        assertTrue(load(yaml("")).categories.getValue("gaming").trackers.isEmpty())
    }

    @Test
    fun `trackers need a dedup block, unique well-formed ids, a title and event types`() {
        assertThrows<IllegalArgumentException> { load(yaml(releases, withDedup = false)) }
        val duplicate = """
            trackers:
              - id: releases
                title: "A"
                eventTypes: [release_date]
              - id: releases
                title: "B"
                eventTypes: [delay]
        """
        assertThrows<IllegalArgumentException> { load(yaml(duplicate)) }
        assertThrows<IllegalArgumentException> { load(yaml(releases.replace("id: releases", "id: \"Releases Cal\""))) }
        assertThrows<IllegalArgumentException> { load(yaml(releases.replace("title: \"📅 Найближчі релізи\"", "title: \"\""))) }
        assertThrows<IllegalArgumentException> { load(yaml(releases.replace("eventTypes: [release_date, delay]", "eventTypes: []"))) }
        val zeroHorizon = """
            trackers:
              - id: releases
                title: "A"
                eventTypes: [release_date]
                horizonDays: 0
        """
        assertThrows<IllegalArgumentException> { load(yaml(zeroHorizon)) }
    }
}
