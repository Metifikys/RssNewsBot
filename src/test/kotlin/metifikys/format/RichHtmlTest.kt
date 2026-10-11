package metifikys.format

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RichHtmlTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")

    @Test
    fun `text is escaped and inline markdown rendered, so LLM output never becomes markup`() {
        assertEquals("<p>a &lt;b&gt; &amp; <b>c</b> <a href=\"https://x.y/?a=1&amp;b=2\">l</a></p>",
            RichHtml.paragraph("a <b> & **c** [l](https://x.y/?a=1&b=2)"))
        assertEquals("<h2>🎮 <b>Silksong</b></h2>", RichHtml.heading(2, "🎮 **Silksong**"))
        assertEquals("<p>#gaming #silksong</p>", RichHtml.paragraph("#gaming #silksong"), "a hashtag line stays text")
    }

    @Test
    fun `media take http(s) or an attached file only, a collage needs two pictures`() {
        assertEquals("", RichHtml.figure("javascript:alert(1)"))
        assertEquals("<figure><img src=\"https://i.example/a.jpg\"/><figcaption>Фото</figcaption></figure>",
            RichHtml.figure("https://i.example/a.jpg", "Фото"))
        assertEquals("<figure><img src=\"tg://photo?id=barometer\"/></figure>", RichHtml.figure(RichHtml.attached("barometer")))
        assertEquals(RichHtml.figure("https://i.example/a.jpg"), RichHtml.collage(listOf("https://i.example/a.jpg", "ftp://no")))
        assertEquals("<tg-collage><img src=\"https://i.example/a.jpg\"/><img src=\"https://i.example/b.jpg\"/></tg-collage>",
            RichHtml.collage(listOf("https://i.example/a.jpg", "https://i.example/b.jpg", "https://i.example/a.jpg")))
        assertEquals("", RichHtml.collage(emptyList()))
    }

    @Test
    fun `details, lists, tables and buttons`() {
        assertEquals("<details><summary>🔗 Джерела · 2</summary><ol><li><a href=\"https://a.com/1\">A &amp; B</a></li>" +
            "<li><a href=\"https://b.com/2\">B</a></li></ol></details>",
            RichHtml.details("🔗 Джерела · 2", RichHtml.linkList(listOf(
                RichHtml.Link("A & B", "https://a.com/1"), RichHtml.Link("B", "https://b.com/2"),
                RichHtml.Link("dup", "https://a.com/1"), RichHtml.Link("bad", "tg://user?id=1")))))
        assertTrue(RichHtml.details("x", "y", open = true).startsWith("<details open>"))
        assertEquals("<table bordered striped><tr><th>Коли</th></tr><tr><td>12.10</td></tr></table>",
            RichHtml.table(listOf("Коли"), listOf(listOf("12.10"))))
        assertEquals("", RichHtml.table(listOf("Коли"), emptyList()))
        assertEquals("<tg-button-row><tg-button type=\"url\" url=\"https://a.com/?q=&quot;x&quot;\">Читати</tg-button></tg-button-row>",
            RichHtml.buttonRow(listOf(RichHtml.Link("Читати", "https://a.com/?q=\"x\""), RichHtml.Link("no", "mailto:a@b.c"))))
        assertEquals("", RichHtml.list(emptyList()))
    }

    @Test
    fun `a calendar day is anchored at noon in the bot's zone, a moment keeps its time`() {
        // 19.10.2026 12:00 Kyiv (UTC+3) = 09:00 UTC.
        assertEquals("<tg-time unix=\"1792400400\" format=\"wD\">19.10.2026</tg-time>",
            RichHtml.time(LocalDate.of(2026, 10, 19), "wD", kyiv))
        // After the switch to winter time (25.10): UTC+2.
        assertEquals("<tg-time unix=\"1795082400\" format=\"r\">19.11.2026</tg-time>",
            RichHtml.time(LocalDate.of(2026, 11, 19), "r", kyiv))
        assertEquals("<tg-time unix=\"1792094400\" format=\"wdt\">15.10.2026 23:00</tg-time>",
            RichHtml.time(LocalDateTime.of(2026, 10, 15, 23, 0), "wdt", kyiv))
        assertFailsWith<IllegalArgumentException> { RichHtml.time(0, "rd", "x") }
    }
}
