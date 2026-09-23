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

    @Test
    fun `story lines need storyIntel`() {
        val e = assertThrows<IllegalArgumentException> { load(yaml(post = "storyLines:\n  hype: { minOutlets: 4 }")) }
        assertTrue("storyIntel.enabled" in e.message!!, e.message)
        val c = load(yaml(top = "storyIntel:\n  enabled: true", post = "storyLines:\n  hype: { minOutlets: 3, reprintSimilarity: 0.9 }"))
        assertEquals(3, c.categories.getValue("gaming").post?.storyLines?.hype?.minOutlets)
    }

    @Test
    fun `hype minOutlets below two is refused`() {
        val e = assertThrows<IllegalArgumentException> {
            load(yaml(top = "storyIntel:\n  enabled: true", post = "storyLines:\n  hype: { minOutlets: 1 }"))
        }
        assertTrue("minOutlets" in e.message!!, e.message)
    }

    @Test
    fun `weekly showOutlets needs storyIntel`() {
        val e = assertThrows<IllegalArgumentException> { load(yaml(top = "weekly:\n  enabled: true\n  showOutlets: true")) }
        assertTrue("weekly.showOutlets" in e.message!!, e.message)
    }

    @Test
    fun `first line and weekly leaderboard are read and checked`() {
        val c = load(yaml(
            top = "storyIntel:\n  enabled: true\nweekly:\n  enabled: true\n  sourceLeaderboard: { enabled: true, lookbackDays: 30, minEvents: 5, top: 3 }",
            post = "storyLines:\n  first: { minOutlets: 3, minLeadMinutes: 45 }"
        ))
        assertEquals(45, c.categories.getValue("gaming").post?.storyLines?.first?.minLeadMinutes)
        assertEquals(5, c.weekly?.sourceLeaderboard?.minEvents)

        val noStory = assertThrows<IllegalArgumentException> {
            load(yaml(top = "weekly:\n  enabled: true\n  sourceLeaderboard: { enabled: true }"))
        }
        assertTrue("weekly.sourceLeaderboard" in noStory.message!!, noStory.message)
        val tooLong = assertThrows<IllegalArgumentException> {
            load(yaml(top = "storyIntel:\n  enabled: true\n  retentionDays: 20\nweekly:\n  enabled: true\n  sourceLeaderboard: { enabled: true, lookbackDays: 30 }"))
        }
        assertTrue("retentionDays" in tooLong.message!!, tooLong.message)
    }
}
