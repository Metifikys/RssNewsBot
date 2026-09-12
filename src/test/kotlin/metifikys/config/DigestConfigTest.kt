package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `dedup.digest.breakingNewsworthiness` and the per-category `minArticles` override. */
class DigestConfigTest {

    private fun load(yaml: String): AppConfig {
        val file = File.createTempFile("config-digest-test", ".yaml")
        file.writeText(yaml)
        try {
            return ConfigLoader.load(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    private fun yaml(digestExtra: String = "", categoryExtra: String = "") = buildString {
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
        if (categoryExtra.isNotBlank()) appendLine(categoryExtra.trimIndent().prependIndent("    "))
        appendLine("    feeds:")
        appendLine("      - https://example.com/gaming")
        appendLine("    dedup:")
        appendLine("      promptFile: \"prompts/games-dedup.yaml\"")
        appendLine("      digest:")
        appendLine("        ranker:")
        appendLine("          enabled: true")
        if (digestExtra.isNotBlank()) appendLine(digestExtra.trimIndent().prependIndent("        "))
    }

    @Test
    fun `breakingNewsworthiness is parsed and off by default`() {
        assertNull(load(yaml()).categories.getValue("gaming").dedup!!.digest!!.breakingNewsworthiness)
        assertEquals(9, load(yaml("breakingNewsworthiness: 9")).categories.getValue("gaming").dedup!!.digest!!.breakingNewsworthiness)
    }

    @Test
    fun `breakingNewsworthiness must be within 1 to 10`() {
        for (bad in listOf(0, 11, -3)) {
            val ex = assertThrows<IllegalArgumentException> { load(yaml("breakingNewsworthiness: $bad")) }
            assertTrue("breakingNewsworthiness" in ex.message.orEmpty(), ex.message)
        }
    }

    @Test
    fun `per-category minArticles is parsed and must be positive`() {
        assertNull(load(yaml()).categories.getValue("gaming").minArticles)
        assertEquals(2, load(yaml(categoryExtra = "minArticles: 2")).categories.getValue("gaming").minArticles)
        val ex = assertThrows<IllegalArgumentException> { load(yaml(categoryExtra = "minArticles: 0")) }
        assertTrue("minArticles" in ex.message.orEmpty(), ex.message)
    }
}
