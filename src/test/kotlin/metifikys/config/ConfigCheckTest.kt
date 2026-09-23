package metifikys.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigCheckTest {

    @TempDir
    lateinit var tmp: Path

    private val base = """
        telegram:
          botToken: "secret-token-value"
        openai:
          apiKey: "sk-secret-value"
        database:
          path: "news.db"
        scheduler:
          intervalMinutes: 60
        categories:
          gaming:
            emoji: "🎮"
            channelId: "@g"
            feeds:
              - https://example.com/rss
    """.trimIndent()

    private fun write(name: String, text: String): String =
        File(tmp.toFile(), name).also { it.writeText(text) }.absolutePath

    private fun run(config: String, against: String? = null) =
        ConfigCheck.run(config, against, baseDir = tmp.toFile())

    @Test
    fun `valid config passes and lists registered options it does not set`() {
        val r = run(write("c.yaml", base))
        assertTrue(r.ok, r.lines.joinToString("\n"))
        assertEquals("OK", r.lines.last())
        assertTrue(r.lines.any { "new option since" in it && "categories.*.post.longForm" in it }, r.lines.joinToString("\n"))
    }

    @Test
    fun `a set option is not listed as new`() {
        val r = run(write("c.yaml", base + "\n    post:\n      longForm: { enabled: false }\n"))
        assertTrue(r.ok, r.lines.joinToString("\n"))
        assertFalse(r.lines.any { "not set: categories.*.post.longForm " in it }, r.lines.joinToString("\n"))
    }

    @Test
    fun `typo fails the check with a readable message`() {
        val r = run(write("c.yaml", base + "\n    post:\n      hashtag: { enabled: true }\n"))
        assertFalse(r.ok)
        assertEquals("FAILED", r.lines.last())
        val text = r.lines.joinToString("\n")
        assertTrue("categories.gaming.post.hashtag" in text, text)
        assertTrue("hashtags" in text, text)
    }

    @Test
    fun `broken prompt file fails the check`() {
        val r = run(write("c.yaml", base + "\n    dedup:\n      promptFile: prompts/missing.yaml\n"))
        assertFalse(r.ok)
        assertTrue(r.lines.any { it.startsWith("ERROR prompts:") && "missing or unreadable" in it }, r.lines.joinToString("\n"))
    }

    @Test
    fun `against prints key paths both ways and never values`() {
        val prod = write("prod.yaml", base + "\n    intervalMinutes: 30\n")
        val dev = write("dev.yaml", base + "\n    post:\n      longForm: { enabled: true, minComposite: 7.5 }\n")
        val r = run(prod, dev)
        val text = r.lines.joinToString("\n")
        assertTrue(r.ok, text)
        assertTrue("  + categories.*.post.longForm.minComposite" in r.lines, text)
        assertTrue("  - categories.*.intervalMinutes" in r.lines, text)
        for (secret in listOf("secret-token-value", "sk-secret-value", "7.5", "@g")) assertFalse(secret in text, "leaked $secret")
    }

    @Test
    fun `cli exit codes`() {
        assertEquals(0, ConfigCheck.main(listOf("--check-config", write("c.yaml", base))))
        assertEquals(1, ConfigCheck.main(listOf("--check-config", write("bad.yaml", base + "\nnope: 1\n"))))
        assertEquals(2, ConfigCheck.main(listOf("--check-config", "c.yaml", "--against")))
    }

    @Test
    fun `startup load rejects an unknown feed field instead of dropping it`() {
        val e = assertThrows<IllegalArgumentException> {
            ConfigLoader.load(write("c.yaml", base.replace("- https://example.com/rss", "- { url: https://example.com/rss, fetchFullcontent: true }")))
        }
        assertTrue("fetchFullcontent" in e.message!!, e.message)
        assertTrue("did you mean 'fetchFullContent'" in e.message!!, e.message)
    }
}
