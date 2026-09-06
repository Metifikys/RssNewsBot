package metifikys.fetch

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextScrubRulesTest {

    @Test
    fun `rules scrub their matches and collapse the whitespace left behind`() {
        val rules = TextScrubRules.parse(
            """
            rules:
              - name: sign-in
                regex: 'Sign in to your ACME account\s*'
              - name: prefix
                regex: '(?:Save story\s*)+'
            """.trimIndent(),
            "inline"
        )

        assertEquals(
            "A writer. The body is great.",
            rules.clean("Save story Save story A writer. Sign in to your ACME account The body is great.")
        )
        assertEquals("Untouched text.", rules.clean("Untouched text."))
    }

    @Test
    fun `host-scoped rule applies to that host and its subdomains only`() {
        val rules = TextScrubRules.parse(
            """
            rules:
              - name: plug
                host: example.com
                regex: 'Follow us on X\s*'
            """.trimIndent(),
            "inline"
        )
        val text = "Follow us on X Real content."

        assertEquals("Real content.", rules.clean(text, "https://www.example.com/story"))
        assertEquals("Real content.", rules.clean(text, "https://example.com/story"))
        assertEquals("Follow us on X Real content.", rules.clean(text, "https://other.org/story"))
        // No URL known → every rule applies.
        assertEquals("Real content.", rules.clean(text))
    }

    @Test
    fun `dotAll lets a rule span lines`() {
        val rules = TextScrubRules.parse(
            """
            rules:
              - name: share-bar
                regex: 'Share this:.{0,50}?Print\b'
                dotAll: true
            """.trimIndent(),
            "inline"
        )
        assertEquals("Lede. Body.", rules.clean("Lede. Share this: Email\nFacebook Print Body."))
    }

    @Test
    fun `invalid regex and duplicate names are rejected with the rule name`() {
        val bad = assertThrows<IllegalArgumentException> {
            TextScrubRules.parse("rules:\n  - name: broken\n    regex: '(unclosed'\n", "inline")
        }
        assertTrue(bad.message.orEmpty().contains("broken"), "got: ${bad.message}")

        val dup = assertThrows<IllegalArgumentException> {
            TextScrubRules.parse(
                "rules:\n  - name: a\n    regex: 'x'\n  - name: a\n    regex: 'y'\n",
                "inline"
            )
        }
        assertTrue(dup.message.orEmpty().contains("Duplicate"), "got: ${dup.message}")
    }

    @Test
    fun `load reads a file and null means nothing is scrubbed`() {
        val file = File.createTempFile("scrub-rules", ".yaml")
        try {
            file.writeText("rules:\n  - name: only\n    regex: 'REMOVE ME\\s*'\n", Charsets.UTF_8)
            val fromFile = TextScrubRules.load(file.absolutePath)

            assertEquals(1, fromFile.rules.size)
            assertEquals("kept", fromFile.clean("REMOVE ME kept"))
            assertTrue(TextScrubRules.load(null).rules.isEmpty())
            assertEquals("REMOVE ME kept", TextScrubRules.load(null).clean("REMOVE ME kept"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `the example file in the repo parses`() {
        val example = File("scrub-rules.example.yaml")
        assertTrue(example.isFile, "scrub-rules.example.yaml must ship with the repo")
        TextScrubRules.load(example.path) // must not throw
    }
}
