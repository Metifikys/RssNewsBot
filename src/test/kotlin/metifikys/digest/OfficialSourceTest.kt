package metifikys.digest

import metifikys.config.OfficialConfig
import metifikys.model.Article
import metifikys.model.ShortlistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class OfficialSourceTest {

    private val cfg = OfficialConfig(domains = listOf("blog.playstation.com", "news.xbox.com", "nintendo.com"))

    private fun item(url: String, vararg sources: String, franchise: String = "", subject: String = "") = ShortlistItem(
        eventKey = "e", subject = subject, franchise = franchise, coreFact = "f", url = url, status = "new",
        sourceLinks = sources.toList()
    )

    private fun links(vararg l: String) = l.toSet()

    @Test
    fun `an official domain in the cluster wins, subdomains included`() {
        val i = item("https://ign.com/a", "https://ign.com/a", "https://eu.blog.playstation.com/p")
        assertEquals("https://eu.blog.playstation.com/p", OfficialSource.pick(i, cfg, links("https://ign.com/a", "https://eu.blog.playstation.com/p")))
    }

    @Test
    fun `the first listed domain wins over cluster order, and a look-alike host does not match`() {
        val i = item("https://ign.com/a", "https://ign.com/a", "https://www.nintendo.com/n", "https://news.xbox.com/x", "https://notnintendo.com/z")
        val batch = links("https://ign.com/a", "https://www.nintendo.com/n", "https://news.xbox.com/x", "https://notnintendo.com/z")
        assertEquals("https://news.xbox.com/x", OfficialSource.pick(i, cfg, batch))
        val lookAlike = item("https://ign.com/a", "https://ign.com/a", "https://notnintendo.com/z")
        assertNull(OfficialSource.pick(lookAlike, cfg, batch))
    }

    @Test
    fun `no match, an already official url, or a link outside the batch keep the url`() {
        assertNull(OfficialSource.pick(item("https://ign.com/a", "https://ign.com/a", "https://pcgamer.com/b"), cfg, links("https://ign.com/a", "https://pcgamer.com/b")))
        assertNull(OfficialSource.pick(item("https://news.xbox.com/x", "https://news.xbox.com/x", "https://nintendo.com/n"), cfg, links("https://news.xbox.com/x", "https://nintendo.com/n")))
        assertNull(OfficialSource.pick(item("https://ign.com/a", "https://ign.com/a", "https://nintendo.com/n"), cfg, links("https://ign.com/a")))
    }

    @Test
    fun `matchEntityDomain accepts a host named after the franchise, after the listed domains`() {
        val on = cfg.copy(matchEntityDomain = true)
        val i = item("https://ign.com/a", "https://ign.com/a", "https://hollowknight.com/news", franchise = "Hollow Knight")
        val batch = links("https://ign.com/a", "https://hollowknight.com/news")
        assertEquals("https://hollowknight.com/news", OfficialSource.pick(i, on, batch))
        assertNull(OfficialSource.pick(i, cfg, batch))
        // Keys under five characters (after dropping "_") never match: "Doom" is not doom.com.
        val short = item("https://ign.com/a", "https://ign.com/a", "https://doom.com/x", franchise = "Doom")
        assertNull(OfficialSource.pick(short, on, links("https://ign.com/a", "https://doom.com/x")))
    }

    @Test
    fun `apply swaps the url and lists the official article among the render indices`() {
        val articles = listOf("https://ign.com/a", "https://pcgamer.com/b", "https://blog.playstation.com/p").map {
            Article(category = "gaming", title = it, link = it, description = "")
        }
        val keep = item("https://pcgamer.com/b", "https://pcgamer.com/b")
        val swap = item("https://ign.com/a", "https://ign.com/a", "https://blog.playstation.com/p").copy(articleIndices = listOf(0))
        val out = OfficialSource.apply("gaming", listOf(keep, swap), articles, cfg)
        assertSame(keep, out[0])
        assertEquals("https://blog.playstation.com/p", out[1].url)
        assertEquals(listOf(0, 2), out[1].articleIndices)
        assertEquals(swap.sourceLinks, out[1].sourceLinks)
        assertSame(swap, OfficialSource.apply("gaming", listOf(swap), articles, cfg.copy(enabled = false)).single())
    }
}
