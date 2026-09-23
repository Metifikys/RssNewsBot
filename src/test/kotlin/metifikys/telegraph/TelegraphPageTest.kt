package metifikys.telegraph

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegraphPageTest {

    private val topic = "🎮 **Silksong gets a date.**\n\n" +
        "Team Cherry **finally** named the day, says [IGN](https://ign.com/x) — `v1.0` \"soon\".\n\n" +
        "Second paragraph <b>raw</b>.\n\n" +
        "[Title 1](https://example.com/1)"

    @Test
    fun `title is the headline without emoji, markers and the final period`() {
        assertEquals("Silksong gets a date", TelegraphPage.title(topic))
        assertEquals("x".repeat(255) + "…", TelegraphPage.title("**${"x".repeat(300)}.**\n\nBody\n\n[a](https://a.b/c)"))
        assertEquals("Новина", TelegraphPage.title("**.**"))
    }

    @Test
    fun `content has the figure, the paragraphs with inline nodes, the sources and the channel link`() {
        val content = TelegraphPage.content(
            topic,
            "https://img.example/1.jpg",
            listOf(
                TelegraphPage.Link("Title 1", "https://example.com/1"),
                TelegraphPage.Link("Eurogamer — Other", "https://eurogamer.net/2"),
                TelegraphPage.Link("dup", "https://example.com/1"),
                TelegraphPage.Link("bad", "javascript:alert(1)")
            ),
            TelegraphPage.Link("@gaming", "https://t.me/gaming")
        )
        val expected = """[
            {"tag":"figure","children":[{"tag":"img","attrs":{"src":"https://img.example/1.jpg"}}]},
            {"tag":"p","children":["Team Cherry ",{"tag":"strong","children":["finally"]}," named the day, says ",
              {"tag":"a","attrs":{"href":"https://ign.com/x"},"children":["IGN"]}," — ",{"tag":"code","children":["v1.0"]}," \"soon\"."]},
            {"tag":"p","children":["Second paragraph <b>raw</b>."]},
            {"tag":"h4","children":["Джерела"]},
            {"tag":"ul","children":[
              {"tag":"li","children":[{"tag":"a","attrs":{"href":"https://example.com/1"},"children":["Title 1"]}]},
              {"tag":"li","children":[{"tag":"a","attrs":{"href":"https://eurogamer.net/2"},"children":["Eurogamer — Other"]}]}
            ]},
            {"tag":"p","children":["Більше новин: ",{"tag":"a","attrs":{"href":"https://t.me/gaming"},"children":["@gaming"]}]}
        ]"""
        assertEquals(Json.parseToJsonElement(expected), content)
    }

    @Test
    fun `no picture and no sources give just the paragraphs`() {
        val content = TelegraphPage.content(topic, null, emptyList(), null)
        assertEquals(2, content.size)
        assertTrue(content.all { it.toString().startsWith("{\"tag\":\"p\"") })
    }

    @Test
    fun `teaser keeps the headline, the first paragraph, the read-more line and the source`() {
        assertEquals(
            "🎮 **Silksong gets a date.**\n\n" +
                "Team Cherry **finally** named the day, says [IGN](https://ign.com/x) — `v1.0` \"soon\".\n\n" +
                "⚡ [Читати повністю](https://telegra.ph/Silksong-09-23)\n\n" +
                "[Title 1](https://example.com/1)",
            TelegraphPage.teaser(topic, "https://telegra.ph/Silksong-09-23")
        )
    }

    @Test
    fun `full mode keeps every paragraph and adds the read-more line before the source`() {
        val full = TelegraphPage.withReadMore(topic, "https://telegra.ph/p")
        assertTrue(full.startsWith("🎮 **Silksong gets a date.**\n\n"))
        assertTrue(full.endsWith("Second paragraph <b>raw</b>.\n\n⚡ [Читати повністю](https://telegra.ph/p)\n\n[Title 1](https://example.com/1)"))
    }

    @Test
    fun `a topic without the strict shape keeps its text in teaser mode`() {
        assertEquals(
            "Just text\n\n⚡ [Читати повністю](https://telegra.ph/p)\n\n[a](https://a.b/c)",
            TelegraphPage.teaser("Just text\n\n[a](https://a.b/c)", "https://telegra.ph/p")
        )
    }
}
