package metifikys.digest

import metifikys.config.DigestConfig
import metifikys.config.LongFormConfig
import metifikys.config.RankerConfig
import metifikys.model.Article
import metifikys.model.ShortlistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LongFormTest {

    private fun item(key: String, news: Int, fit: Int, url: String = "https://x/$key", indices: List<Int> = emptyList()) =
        ShortlistItem(eventKey = key, coreFact = "f", newsworthiness = news, digestFit = fit, url = url, status = "new", articleIndices = indices)

    private val digest = DigestConfig(ranker = RankerConfig(enabled = true, newsworthinessWeight = 0.6))

    private fun longKeys(list: List<ShortlistItem>) = list.filter { it.isLongForm }.map { it.eventKey }

    @Test
    fun `the first items in ranker order that reach minComposite are long, up to the cap`() {
        val shortlist = listOf(item("a", 9, 9), item("b", 7, 7), item("c", 10, 8), item("d", 9, 8))
        assertEquals(listOf("a"), longKeys(LongForm.mark(shortlist, LongFormConfig(maxPerDigest = 1, minComposite = 8.0), digest)))
        // b (7.0) is below the bar and is skipped, not counted.
        assertEquals(listOf("a", "c"), longKeys(LongForm.mark(shortlist, LongFormConfig(maxPerDigest = 2, minComposite = 8.0), digest)))
    }

    @Test
    fun `nothing is long when no item reaches the bar or the block is disabled`() {
        val shortlist = listOf(item("a", 7, 7), item("b", 9, 9))
        assertEquals(emptyList(), longKeys(LongForm.mark(shortlist.take(1), LongFormConfig(minComposite = 8.0), digest)))
        assertEquals(emptyList(), longKeys(LongForm.mark(shortlist, LongFormConfig(enabled = false), digest)))
    }

    @Test
    fun `depth from Step 1 is never trusted`() {
        val forged = item("a", 3, 3).copy(depth = ShortlistItem.DEPTH_LONG)
        assertNull(LongForm.mark(listOf(forged), LongFormConfig(), digest).single().depth)
    }

    @Test
    fun `composite follows the ranker weight`() {
        // news 10, fit 6: 0.6 weight → 8.4 (long); 0.2 weight → 6.8 (short).
        val shortlist = listOf(item("a", 10, 6))
        assertEquals(listOf("a"), longKeys(LongForm.mark(shortlist, LongFormConfig(minComposite = 8.0), digest)))
        val fitHeavy = DigestConfig(ranker = RankerConfig(enabled = true, newsworthinessWeight = 0.2))
        assertEquals(emptyList(), longKeys(LongForm.mark(shortlist, LongFormConfig(minComposite = 8.0), fitHeavy)))
    }

    private fun article(n: Int) = Article(category = "games", title = "T$n", link = "https://x/$n", description = "rss $n")

    @Test
    fun `full text lands on the long item's article, falling back to its other sources`() {
        val articles = listOf(article(0), article(1), article(2))
        val shortlist = listOf(
            item("long", 9, 9, url = "https://x/0", indices = listOf(0, 1)).copy(depth = ShortlistItem.DEPTH_LONG),
            item("short", 9, 9, url = "https://x/2", indices = listOf(2))
        )
        val asked = mutableListOf<Pair<String, Int>>()
        val fetcher = LongForm.TextFetcher { url, max ->
            asked += url to max
            if (url == "https://x/1") "page text of 1" else null
        }

        val out = LongForm.attachFullText("games", shortlist, articles, LongFormConfig(sourceChars = 4000), fetcher)

        assertEquals(listOf("https://x/0" to 4000, "https://x/1" to 4000), asked)
        assertNull(out[0].fullText)
        assertEquals("page text of 1", out[1].fullText)
        assertNull(out[2].fullText)
    }

    @Test
    fun `no long items or no text leaves the articles untouched`() {
        val articles = listOf(article(0))
        val short = listOf(item("a", 9, 9, url = "https://x/0"))
        assertSame(articles, LongForm.attachFullText("games", short, articles, LongFormConfig()) { _, _ -> error("not called") })
        val long = short.map { it.copy(depth = ShortlistItem.DEPTH_LONG) }
        assertSame(articles, LongForm.attachFullText("games", long, articles, LongFormConfig()) { _, _ -> null })
    }
}
