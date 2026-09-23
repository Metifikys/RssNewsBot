package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryIntelConfigTest {

    private fun load(yaml: String): AppConfig {
        val file = File.createTempFile("config-story-test", ".yaml")
        file.writeText(yaml)
        return try {
            ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    private fun yaml(top: String = "", post: String = "") = buildString {
        append(
            """
            telegram:
              botToken: "t"
            openai:
              apiKey: "sk"
            database:
              path: "/tmp/test.db"
            scheduler:
              intervalMinutes: 60
            categories:
              gaming:
                emoji: "🎮"
                channelId: "@g"
                feeds:
                  - https://example.com/rss
                dedup:
                  promptFile: prompts/games-dedup.example.yaml
            """.trimIndent()
        )
        append('\n')
        if (post.isNotBlank()) append("    post:\n").append(post.trimIndent().prependIndent("      ")).append('\n')
        if (top.isNotBlank()) append(top.trimIndent()).append('\n')
    }

    @Test
    fun `storyIntel is off by default`() {
        val c = load(yaml())
        assertFalse(c.storyIntel.enabled)
        assertEquals(180, c.storyIntel.retentionDays)
    }

    @Test
    fun `retention shorter than the weekly lookback is refused`() {
        val e = assertThrows<IllegalArgumentException> {
            load(yaml(top = "storyIntel:\n  enabled: true\n  retentionDays: 5\nweekly:\n  enabled: true\n  lookbackDays: 7"))
        }
        assertTrue("storyIntel.retentionDays" in e.message!!, e.message)
    }

    @Test
    fun `domain aliases are read`() {
        val c = load(yaml(top = "storyIntel:\n  enabled: true\n  domainAliases:\n    eurogamer.de: eurogamer.net"))
        assertEquals(mapOf("eurogamer.de" to "eurogamer.net"), c.storyIntel.domainAliases)
    }
}
