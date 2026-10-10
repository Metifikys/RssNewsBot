package metifikys.discussion

import metifikys.config.DiscussionConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscussionTextTest {

    private fun c(id: String, text: String, vararg replies: DiscussionComment) = DiscussionComment(id, text, replies = replies.toList())

    private val long = "A substantial comment with an actual argument in it"

    private fun thread(vararg comments: DiscussionComment) =
        DiscussionThread(DiscussionRef("hn", "1", "https://news.ycombinator.com/item?id=1"), "HN", 401, comments.toList())

    @Test
    fun `top comments in order, short ones skipped, replies capped and indented`() {
        val t = thread(
            c("a", "This.", c("a1", "a reply that would be long enough")),
            c("b", "$long B", c("b1", "first reply to B, long enough"), c("b2", "ok"), c("b3", "second qualifying reply to B"), c("b4", "third reply, over the cap")),
            c("c", "$long C"),
            c("d", "$long D")
        )
        val text = DiscussionText.formatComments(t, DiscussionConfig(topLevel = 2, repliesPerComment = 2))

        assertEquals(
            "[1] (4 відповіді) $long B\n    ↳ first reply to B, long enough\n    ↳ second qualifying reply to B\n\n[2] $long C",
            text
        )
    }

    @Test
    fun `comments are cut and the whole stops at maxChars`() {
        val big = "x".repeat(400)
        val t = thread(c("a", big, c("a1", "y".repeat(400))), c("b", big), c("c", big))
        val text = DiscussionText.formatComments(t, DiscussionConfig(maxCommentChars = 100, maxChars = 250, repliesPerComment = 1))

        assertTrue(text.startsWith("[1] (1 відповідь) " + "x".repeat(99) + "…"), text)
        assertTrue(text.length <= 250, "${text.length}")
        // #1 with its reply (~240 chars) fits; #2 does not even alone.
        assertFalse("[2]" in text)
    }

    @Test
    fun `a top comment without its replies still fits when the replies do not`() {
        val t = thread(c("a", "$long A"), c("b", "$long B", c("b1", "r".repeat(200))))
        val text = DiscussionText.formatComments(t, DiscussionConfig(maxCommentChars = 100, maxChars = 130))
        assertEquals("[1] $long A\n\n[2] (1 відповідь) $long B", text)
    }

    @Test
    fun `points lose links and markup`() {
        assertEquals(
            "Автори вже виправили баг у версії 2.1, див. issue.",
            DiscussionText.cleanPoint("• Автори вже **виправили** баг у версії 2.1, див. [issue](https://evil.example/x).")
        )
        assertEquals("Є форк без телеметрії: на GitHub.", DiscussionText.cleanPoint("Є форк без телеметрії: www.github.com/x/y на GitHub."))
        assertNull(DiscussionText.cleanPoint("https://evil.example/only-a-link"))
        assertNull(DiscussionText.cleanPoint("   "))
        val cut = DiscussionText.cleanPoint("а".repeat(500))!!
        assertEquals(DiscussionText.MAX_POINT_CHARS, cut.length)
        assertTrue(cut.endsWith("…"))
        assertEquals("Два рядки в одному", DiscussionText.cleanPoint("Два рядки\nв одному"))
    }

    @Test
    fun `the reply links only the thread`() {
        val reply = DiscussionText.reply(thread(), listOf("Перший пункт.", "Другий пункт."))
        assertEquals(
            "🗣 Обговорення на [HN](https://news.ycombinator.com/item?id=1) · 401 коментар\n\n• Перший пункт.\n• Другий пункт.",
            reply
        )
    }
}
