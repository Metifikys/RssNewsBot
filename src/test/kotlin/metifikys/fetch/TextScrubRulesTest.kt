package metifikys.fetch

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextScrubRulesTest {

    @Test
    fun `built-in rules load from the classpath and scrub known chrome`() {
        val rules = TextScrubRules.DEFAULT
        assertTrue(rules.rules.isNotEmpty(), "default resource should carry rules")
        assertEquals(
            "Ayush Pande is a PC hardware writer. Proxmox is great.",
            rules.clean("Ayush Pande is a PC hardware writer. Sign in to your XDA account Proxmox is great.")
        )
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
        // No URL known → every rule applies (the pre-file behaviour).
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
    fun `load reads a file and null falls back to the built-in list`() {
        val file = File.createTempFile("scrub-rules", ".yaml")
        try {
            file.writeText("rules:\n  - name: only\n    regex: 'REMOVE ME\\s*'\n", Charsets.UTF_8)
            val fromFile = TextScrubRules.load(file.absolutePath)

            assertEquals(1, fromFile.rules.size)
            assertEquals("kept", fromFile.clean("REMOVE ME kept"))
            // The file replaces the built-in list: the XDA rule is gone.
            assertEquals("Sign in to your XDA account kept", fromFile.clean("Sign in to your XDA account kept"))
            assertTrue(TextScrubRules.load(null).rules.size > 1)
        } finally {
            file.delete()
        }
    }
}
