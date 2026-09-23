package metifikys.format

import metifikys.config.AlsoConfig
import metifikys.model.ShortlistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlsoLineTest {

    private fun item(url: String, vararg sources: String) =
        ShortlistItem(eventKey = "e", coreFact = "f", url = url, status = "new", sourceLinks = sources.toList())

    @Test
    fun `the post's own host is left out and hosts are deduplicated`() {
        val i = item(
            "https://www.ign.com/a",
            "https://www.ign.com/a", "https://ign.com/other", "https://www.pcgamer.com/b", "https://pcgamer.com/b2",
            "https://www.eurogamer.net/c"
        )
        val sources = AlsoLine.sources(i, AlsoConfig())
        assertEquals(listOf("pcgamer.com", "eurogamer.net"), sources.map { it.host })
        assertEquals(
            "📰 Також: [Pcgamer](https://www.pcgamer.com/b) · [Eurogamer](https://www.eurogamer.net/c)",
            AlsoLine.line(sources)
        )
    }

    @Test
    fun `configured names match the host or a parent domain, then the feed label, then the domain`() {
        val names = mapOf("pcgamer.com" to "PC Gamer", "ign.com" to "IGN")
        assertEquals("PC Gamer", AlsoLine.nameOf("pcgamer.com", names))
        assertEquals("IGN", AlsoLine.nameOf("uk.ign.com", names))
        assertEquals("Rock Paper Shotgun", AlsoLine.nameOf("rockpapershotgun.com", names, feedLabel = "Rock Paper Shotgun"))
        assertEquals("Playstation", AlsoLine.nameOf("blog.playstation.com", names))
        assertEquals("Bbc", AlsoLine.nameOf("bbc.co.uk", names))
        assertEquals("Foo (bar)", AlsoLine.nameOf("x.com", mapOf("x.com" to "Foo [bar]")))
    }

    @Test
    fun `a feed label is used only when the feed lives on the article's host`() {
        val i = item("https://a.com/1", "https://a.com/1", "https://news.google.com/x", "https://b.com/2")
        val labels = mapOf(
            "https://news.google.com/x" to ("https://rsshub.local/google" to "Google News"),
            "https://b.com/2" to ("https://b.com/feed" to "Bee")
        )
        val sources = AlsoLine.sources(i, AlsoConfig()) { labels[it] }
        assertEquals(listOf("Google", "Bee"), sources.map { it.name })
    }

    @Test
    fun `at most max sources, one per name`() {
        val i = item("https://a.com/1", "https://a.com/1", "https://b.com/2", "https://uk.b.com/3", "https://c.com/4", "https://d.com/5")
        val sources = AlsoLine.sources(i, AlsoConfig(max = 2, names = mapOf("b.com" to "Bee")))
        assertEquals(listOf("https://b.com/2", "https://c.com/4"), sources.map { it.url })
    }

    @Test
    fun `a single-article cluster gives no line`() {
        assertNull(AlsoLine.line(AlsoLine.sources(item("https://a.com/1", "https://a.com/1"), AlsoConfig())))
        assertNull(AlsoLine.line(AlsoLine.sources(item("https://a.com/1"), AlsoConfig())))
    }
}
