package metifikys.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedTitlesTest {

    @Test
    fun `a real title is kept`() {
        assertEquals("Real title", FeedTitles.derive("Real title", "<p>Some body text here</p>"))
    }

    @Test
    fun `an empty title becomes the first line of the raw description`() {
        val html = "<p>Studio announces a sequel</p><p>It launches in spring.</p>"
        assertEquals("Studio announces a sequel", FeedTitles.derive(null, html))
        assertEquals("Studio announces a sequel", FeedTitles.derive("  ", html))
    }

    @Test
    fun `br is a line boundary, as strip would glue the lines`() {
        assertEquals("Перший рядок поста з новиною", FeedTitles.derive("", "Перший рядок поста з новиною<br>Другий рядок<br/>Третій"))
    }

    @Test
    fun `a duplicate title is rewritten only when asked`() {
        val html = "Valve confirms Half-Life 3 is in development. More details at the event next week, with a trailer and dates."
        val truncated = "Valve confirms Half-Life 3 is in development. More details at the…"
        assertEquals(truncated, FeedTitles.derive(truncated, html, rewriteDuplicate = false))
        assertEquals("Valve confirms Half-Life 3 is in development.", FeedTitles.derive(truncated, html, rewriteDuplicate = true))
        assertEquals("Different headline", FeedTitles.derive("Different headline", html, rewriteDuplicate = true))
    }

    @Test
    fun `a long first line is cut at a word with an ellipsis, never inside an emoji`() {
        val line = "🎮 " + "слово ".repeat(40)
        val title = FeedTitles.firstLine(line, maxChars = 50)!!
        assertTrue(title.length <= 50, title)
        assertTrue(title.endsWith("слово…"), title)
        val emojis = "😀".repeat(40)
        val cut = FeedTitles.shorten(emojis, 21)
        assertTrue(!Character.isHighSurrogate(cut[cut.length - 2]), "no dangling high surrogate: $cut")
    }

    @Test
    fun `a too-short first line takes the next one along`() {
        assertEquals("Анонс! Нова гра від студії вийде восени", FeedTitles.derive(null, "Анонс!<br>Нова гра від студії вийде восени"))
    }

    @Test
    fun `no text anywhere keeps the raw title`() {
        assertEquals("", FeedTitles.derive(null, "<img src='x.jpg'>"))
    }
}
