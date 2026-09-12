package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-category `post:` block: reply threading and the hashtag footer. */
class PostConfigTest {

    private fun load(yaml: String): AppConfig {
        val file = File.createTempFile("config-post-test", ".yaml")
        file.writeText(yaml.trimIndent())
        try {
            return ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    private fun yaml(post: String) = buildString {
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
        if (post.isNotBlank()) appendLine(post.trimIndent().prependIndent("    "))
        appendLine("  politics:")
        appendLine("    emoji: \"🏛️\"")
        appendLine("    channelId: \"@politics\"")
        appendLine("    feeds:")
        appendLine("      - https://example.com/politics")
    }

    @Test
    fun `post block is parsed with its defaults`() {
        val config = load(
            yaml(
                """
                post:
                  threads:
                    maxAgeDays: 10
                  hashtags:
                    category: "#gaming #ігри"
                    fromEventType: true
                """.trimIndent()
            )
        )
        val post = config.categories.getValue("gaming").post!!
        assertTrue(post.threads!!.enabled)
        assertEquals(10L, post.threads!!.maxAgeDays)
        assertEquals("#gaming #ігри", post.hashtags!!.category)
        assertTrue(post.hashtags!!.fromFranchise)
        assertTrue(post.hashtags!!.fromEventType)
        assertEquals(false, post.hashtags!!.fromSubject)
        assertEquals(3, post.hashtags!!.max)
        assertNull(config.categories.getValue("politics").post)
    }

    @Test
    fun `badges flags map to provider ids, and the global badges block is range-checked`() {
        val config = load(yaml("post:\n  badges:\n    steam: true\n    github: true"))
        assertEquals(setOf("steam", "github"), config.categories.getValue("gaming").post!!.badges!!.enabledIds)
        assertEquals(5L, config.badges.timeoutSeconds)
        assertEquals("UA", config.badges.steamCountry)

        val withGlobal = load("badges:\n  timeoutSeconds: 2\n  maxTotalSeconds: 8\n  steamCountry: \"PL\"\n" + yaml(""))
        assertEquals(2L, withGlobal.badges.timeoutSeconds)
        assertEquals(8L, withGlobal.badges.maxTotalSeconds)
        assertEquals("PL", withGlobal.badges.steamCountry)

        assertThrows<IllegalArgumentException> { load("badges:\n  timeoutSeconds: 0\n" + yaml("")) }
        assertThrows<IllegalArgumentException> { load("badges:\n  timeoutSeconds: 10\n  maxTotalSeconds: 5\n" + yaml("")) }
    }

    @Test
    fun `absent post block leaves posts unchanged`() {
        val config = load(yaml(""))
        assertNull(config.categories.getValue("gaming").post)
    }

    @Test
    fun `static hashtags must be well-formed`() {
        for (bad in listOf("gaming", "#2024", "#tag-x", "#", "#two words")) {   // NBSP is not a word char either
            val ex = assertThrows<IllegalArgumentException> {
                load(yaml("post:\n  hashtags:\n    category: \"$bad\""))
            }
            assertTrue("post.hashtags.category" in ex.message.orEmpty(), "message for '$bad': ${ex.message}")
        }
    }

    @Test
    fun `threads maxAgeDays and hashtags max are range-checked`() {
        assertThrows<IllegalArgumentException> { load(yaml("post:\n  threads:\n    maxAgeDays: 0")) }
        assertThrows<IllegalArgumentException> { load(yaml("post:\n  hashtags:\n    max: -1")) }
    }
}
