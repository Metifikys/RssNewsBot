package metifikys.format

import metifikys.format.FooterLines.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichPostTest {

    private val topic = "🎮 **Xbox і стримінг GTA 6.**\n\nПерший абзац з **жирним**.\n\nДругий абзац.\n\n[Xbox Lands Exclusive Streaming Rights](https://kotaku.com/gta-6)"

    private fun footer() = FooterLines()
        .add(Slot.BADGES, "🎮 Steam: ₴899")
        .add(Slot.STORY, "📢 9 видань · 1 першоджерело")
        .add(Slot.ALSO, "📰 Також: [IGN](https://ign.com/a)")
        .add(Slot.HASHTAGS, "#gaming #gta")

    @Test
    fun `the long post becomes heading, picture, paragraphs, facts, collapsed sources and plain hashtags`() {
        val html = RichPost.longForm(topic, footer(), "https://img.example/1.jpg",
            listOf(RichHtml.Link("IGN — Xbox Reportedly Secures …", "https://ign.com/a"), RichHtml.Link("dup", "https://kotaku.com/gta-6")))
        assertEquals(
            "<h2>🎮 Xbox і стримінг GTA 6</h2>" +
                "<figure><img src=\"https://img.example/1.jpg\"/></figure>" +
                "<p>Перший абзац з <b>жирним</b>.</p>" +
                "<p>Другий абзац.</p>" +
                "<p>🎮 Steam: ₴899</p>" +
                "<p>📢 9 видань · 1 першоджерело</p>" +
                "<details><summary>🔗 Джерела · 2</summary><ol>" +
                "<li><a href=\"https://kotaku.com/gta-6\">Xbox Lands Exclusive Streaming Rights</a></li>" +
                "<li><a href=\"https://ign.com/a\">IGN — Xbox Reportedly Secures …</a></li></ol>" +
                "<tg-button-row><tg-button type=\"url\" url=\"https://kotaku.com/gta-6\">Читати на kotaku.com</tg-button></tg-button-row>" +
                "</details>" +
                "<p>#gaming #gta</p>",
            html
        )
        assertFalse("Також" in html, "the also line is not repeated: its outlets are in the sources")
    }

    @Test
    fun `a topic without a headline or picture still renders, a headline keeps its question mark`() {
        val html = RichPost.longForm("🤖 Mistral представила Large 4.\n\n[Mistral](https://mistral.ai/news)", FooterLines(), null, emptyList())
        assertEquals("<p>🤖 Mistral представила Large 4.</p><details><summary>🔗 Джерела · 1</summary><ol><li><a href=\"https://mistral.ai/news\">Mistral</a></li></ol>" +
            "<tg-button-row><tg-button type=\"url\" url=\"https://mistral.ai/news\">Читати на mistral.ai</tg-button></tg-button-row></details>", html)
        assertEquals("🎮 Чи перенесуть GTA 6?", TopicParts.of("🎮 **Чи перенесуть GTA 6?**\n\nТекст.").headline)
        assertTrue(RichPost.readOn("not a url").startsWith("Читати"))
    }
}
