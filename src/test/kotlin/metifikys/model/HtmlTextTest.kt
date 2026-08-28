package metifikys.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HtmlTextTest {

    @Test
    fun `strips tags and decodes entities from feed html`() {
        val tme = """<p><span class="emoji">🇺🇸</span> <b>Держдеп США тимчасово </b>""" +
            """<a href="https://example.com" onclick="return confirm('x');"><b>зупинив</b></a>""" +
            """<b> розгляд заяв.</b><br><br>Деталі&#32;уточнюються.</p>"""
        val text = HtmlText.strip(tme)
        assertEquals("🇺🇸 Держдеп США тимчасово зупинив розгляд заяв. Деталі уточнюються.", text)
        assertFalse(text.contains("<"))
        assertFalse(text.contains("confirm"))
    }

    @Test
    fun `strips reddit card markup to its visible text`() {
        val card = """<table> <tr><td> <a href="https://reddit.com/r/x/comments/1/"> """ +
            """<img src="https://preview.redd.it/a.jpg" /> </a> </td><td> &#32; submitted by &#32; """ +
            """<a href="https://reddit.com/user/u"> /u/user </a></td></tr></table>"""
        assertEquals("submitted by /u/user", HtmlText.strip(card))
    }

    @Test
    fun `plain text is returned unchanged and without a parse`() {
        val plain = "Звичайний опис новини без розмітки."
        assertSame(plain, HtmlText.strip(plain))
    }

    @Test
    fun `bare angle brackets and ampersands in prose are not treated as markup`() {
        val prose = "5 < 6 and A & B make no markup"
        assertSame(prose, HtmlText.strip(prose))
    }

    @Test
    fun `decodes entities even without tags`() {
        assertEquals("Rockstar’s reveal — soon", HtmlText.strip("Rockstar&#8217;s reveal &#8212; soon"))
    }

    @Test
    fun `promptText strips legacy html rows and prefers summary untouched`() {
        val htmlRow = Article(
            category = "politics", title = "t", link = "https://x/1",
            description = "<p>Перший <b>факт</b>.</p><p>Другий факт.</p>"
        )
        assertEquals("Перший факт. Другий факт.", htmlRow.promptText())

        val withSummary = htmlRow.copy(summary = "Готове саммарі.")
        assertEquals("Готове саммарі.", withSummary.promptText())
    }

    @Test
    fun `promptText caps after stripping, not before`() {
        // ~700 chars of markup wrapping 14 chars of text: the cap must apply to the text.
        val noisy = "<div>" + "<span class=\"padding-padding-padding\"></span>".repeat(15) + "короткий текст</div>"
        val article = Article(category = "c", title = "t", link = "https://x/2", description = noisy)
        assertTrue(noisy.length > 600)
        assertEquals("короткий текст", article.promptText(maxChars = 600))
    }
}
