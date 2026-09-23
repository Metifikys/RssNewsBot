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
        assertEquals(setOf("steam", "hn", "github", "trailer", "ukLocalization", "deck", "critic"), schema.childrenOf("categories.*.post.badges"))
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
}
