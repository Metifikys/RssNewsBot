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

    @Test
    fun `conflicts line defaults to log mode and checks its mode`() {
        val c = load(yaml(top = "storyIntel:\n  enabled: true", post = "storyLines:\n  conflicts: { minSupport: 2 }"))
        val conflicts = c.categories.getValue("gaming").post?.storyLines?.conflicts
        assertEquals(ConflictLineConfig.MODE_LOG, conflicts?.mode)
        assertEquals(2, conflicts?.minSupport)
        assertEquals(1, conflicts?.dateToleranceDays)

        val badMode = assertThrows<IllegalArgumentException> {
            load(yaml(top = "storyIntel:\n  enabled: true", post = "storyLines:\n  conflicts: { mode: shout }"))
        }
        assertTrue("conflicts.mode" in badMode.message!!, badMode.message)
        val noStory = assertThrows<IllegalArgumentException> { load(yaml(post = "storyLines:\n  conflicts: { mode: post }")) }
        assertTrue("storyIntel.enabled" in noStory.message!!, noStory.message)
    }

    @Test
    fun `vaporware line is read and checked`() {
        val c = load(yaml(top = "storyIntel:\n  enabled: true", post = "storyLines:\n  vaporware: { minAnnouncements: 4, announceTypes: [rumor, leak] }"))
        val v = c.categories.getValue("gaming").post?.storyLines?.vaporware
        assertEquals(4, v?.minAnnouncements)
        assertEquals(listOf("rumor", "leak"), v?.announceTypes)
        assertEquals(30, v?.minGapDays)
        assertEquals(1095, c.storyIntel.historyRetentionDays)

        val tooFew = assertThrows<IllegalArgumentException> {
            load(yaml(top = "storyIntel:\n  enabled: true", post = "storyLines:\n  vaporware: { minAnnouncements: 1 }"))
        }
        assertTrue("minAnnouncements" in tooFew.message!!, tooFew.message)
        val noStory = assertThrows<IllegalArgumentException> { load(yaml(post = "storyLines:\n  vaporware: { enabled: true }")) }
        assertTrue("storyIntel.enabled" in noStory.message!!, noStory.message)
    }

    @Test
    fun `barometer defaults, overrides and checks`() {
        val c = load(yaml(top = "storyIntel:\n  enabled: true\nweekly:\n  enabled: true\n  barometer: { enabled: true, format: text, weeks: 8 }"))
        val b = c.weekly!!.barometer
        assertEquals(BarometerConfig.FORMAT_TEXT, b.format)
        assertEquals(8, b.weeks)
        assertEquals(listOf("layoffs", "releases"), b.series.map { it.id })
        assertFalse(b.series[1].byEventDate) // release news by the week it was posted

        val custom = load(yaml(top = "storyIntel:\n  enabled: true\nweekly:\n  enabled: true\n  barometer:\n    enabled: true\n    series:\n      - { id: closures, label: Закриття, eventTypes: [studio_closure] }"))
        assertEquals(listOf("closures"), custom.weekly!!.barometer.series.map { it.id })

        val noStory = assertThrows<IllegalArgumentException> { load(yaml(top = "weekly:\n  enabled: true\n  barometer: { enabled: true }")) }
        assertTrue("weekly.barometer" in noStory.message!!, noStory.message)
        val badFormat = assertThrows<IllegalArgumentException> {
            load(yaml(top = "storyIntel:\n  enabled: true\nweekly:\n  enabled: true\n  barometer: { enabled: true, format: svg }"))
        }
        assertTrue("format" in badFormat.message!!, badFormat.message)
        val threeSeries = assertThrows<IllegalArgumentException> {
            load(yaml(top = "storyIntel:\n  enabled: true\nweekly:\n  enabled: true\n  barometer:\n    enabled: true\n    series:\n" +
                "      - { id: a, label: A, eventTypes: [x] }\n      - { id: b, label: B, eventTypes: [y] }\n      - { id: c, label: C, eventTypes: [z] }"))
        }
        assertTrue("one or two" in threeSeries.message!!, threeSeries.message)
    }
}
