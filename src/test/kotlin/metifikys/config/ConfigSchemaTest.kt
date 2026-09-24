package metifikys.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigSchemaTest {

    private val schema = ConfigSchema.APP

    @Test
    fun `known paths include nested map and list levels`() {
        for (p in listOf(
            "telegram.botToken",
            "categories",
            "categories.*",
            "categories.*.dedup.digest.ranker.reactionWeight",
            "categories.*.post.longForm.minComposite",
            "categories.*.feeds[]",
            "categories.*.feeds[].fetchFullContent",
            "categories.*.trackers[].eventTypes",
            "admin.commands.enabled",
            "feedback.valence.*",
            "pricing[].model"
        )) {
            assertTrue(schema.contains(p), "missing $p")
        }
    }

    @Test
    fun `computed properties are not keys`() {
        assertFalse(schema.contains("categories.*.post.badges.enabledIds"))
        assertEquals(setOf("steam", "hn", "github", "trailer", "ukLocalization", "deck", "critic", "cve", "steamTrend"), schema.childrenOf("categories.*.post.badges"))
    }

    @Test
    fun `scan normalizes map keys and list items and reports unknown keys by their real path`() {
        val yaml = ObjectMapper(YAMLFactory()).readTree(
            """
            telegram: { botToken: x }
            categories:
              gaming:
                feeds:
                  - https://a.example/rss
                  - { url: https://b.example/rss, fetchFullContent: true }
                post:
                  hashtag: { enabled: true }
            """.trimIndent()
        )
        val scan = schema.scan(yaml)
        assertTrue("categories.*.feeds[].fetchFullContent" in scan.known, "${scan.known}")
        assertTrue("categories.*.post" in scan.known)
        assertEquals(listOf("categories.gaming.post.hashtag"), scan.unknown)
    }

    @Test
    fun `every changelog path exists in the schema`() {
        for (e in ConfigChangelog.ENTRIES) {
            assertTrue(schema.contains(e.path), "ConfigChangelog path '${e.path}' is not a config key (renamed?)")
            assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").matches(e.since), "bad date ${e.since}")
        }
    }

    @Test
    fun `config example has no unknown keys`() {
        val example = File("config.example.yaml")
        if (!example.exists()) return
        val scan = ConfigCheck.scan(example)
        assertEquals(emptyList(), scan.unknown, "config.example.yaml has keys the code does not know")
    }

    /**
     * The example is a working config, not a pile of commented-out blocks: it loads and validates
     * exactly like `--check-config` (its prompt files are the committed `*.example.yaml`), sets every
     * option ConfigChangelog registers, and switches no optional feature on.
     */
    @Test
    fun `config example loads, validates and keeps every optional feature off`() {
        val example = File("config.example.yaml")
        assertTrue(example.isFile, "config.example.yaml is missing (tests run from the project root)")

        val check = ConfigCheck.run(example.path)
        assertTrue(check.ok, check.lines.joinToString("\n"))
        assertTrue(check.lines.none { it.startsWith("INFO  new option") }, "the example should show every registered option:\n${check.lines.joinToString("\n")}")

        val c = ConfigLoader.load(example.path)
        val off = mutableMapOf(
            "telegram.updatesPolling" to c.telegram.updatesPolling,
            "health" to c.health.enabled,
            "feedHealth" to c.feedHealth.enabled,
            "feedHealth.candidates" to c.feedHealth.candidates.enabled,
            "storyIntel" to c.storyIntel.enabled,
            "factCheck" to c.factCheck.enabled,
            "archive" to (c.archive?.enabled == true),
            "weekly" to (c.weekly?.enabled == true),
            "feedback" to c.feedback.enabled,
            "preferences.mute" to c.preferences.mute.isNotEmpty(),
            "admin.statusChatId" to (c.admin.statusChatId != null),
            "admin.commands" to c.admin.commands.enabled,
            "admin.muteSuggestions" to c.admin.muteSuggestions.enabled,
            "admin.moderation" to c.admin.moderation.enabled,
            "fetcher.privateHostAllowlist" to c.fetcher.privateHostAllowlist.isNotEmpty(),
            "fetcher.pathScopedThrottleHosts" to c.fetcher.pathScopedThrottleHosts.isNotEmpty(),
            "openrouter.models" to (c.openrouter?.models.orEmpty().isNotEmpty())
        )
        for ((name, cat) in c.categories) {
            val p = cat.post
            off["$name.mute"] = cat.mute.isNotEmpty()
            off["$name.post.longForm.telegraph"] = p?.longForm?.telegraph?.enabled == true
            off["$name.post.also"] = p?.also?.enabled == true
            off["$name.post.official"] = p?.official?.enabled == true
            off["$name.post.images"] = p?.images?.enabled == true
            off["$name.post.crossLinks"] = p?.crossLinks?.enabled == true
            off["$name.post.persona"] = p?.persona?.enabled == true
            p?.storyLines?.let { s ->
                off["$name.post.storyLines"] = listOfNotNull(s.hype?.enabled, s.first?.enabled, s.conflicts?.enabled, s.vaporware?.enabled).any { it }
            }
            off["$name.llm.shadow"] = cat.llm?.shadow?.enabled == true
            off["$name.dedup.digest.antiBubble"] = cat.dedup?.digest?.antiBubble?.enabled == true
            off["$name.dedup.digest.breakingNewsworthiness"] = cat.dedup?.digest?.breakingNewsworthiness != null
            off["$name.dedup.digest.maxPostsPerEvent"] = cat.dedup?.digest?.maxPostsPerEvent != null
        }
        assertEquals(emptyList(), off.filterValues { it }.keys.toList(), "config.example.yaml switches these on")
    }
}
