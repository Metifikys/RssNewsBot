package metifikys.digest

import metifikys.config.PostImagesConfig
import metifikys.enrich.MemoryBadgeCache
import metifikys.fetch.ImageCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PostImagesTest {

    private val cfg = PostImagesConfig(minWidth = 400, timeoutSeconds = 1, maxTotalSeconds = 5)

    private fun images(
        og: (String) -> ImageCandidate? = { null },
        probe: (String) -> Pair<Int, Int>? = { null },
        cache: MemoryBadgeCache? = null
    ) = PostImages(og, probe, cache)

    private fun choose(p: PostImages, feed: List<String>, c: PostImagesConfig = cfg) =
        p.choose("[t]", "https://site.com/a", feed, c, p.newDeadline(c))

    @Test
    fun `og image wins over the feed image`() {
        val p = images(og = { ImageCandidate("https://site.com/og.jpg", 1200, 630) })
        assertEquals("https://site.com/og.jpg", choose(p, listOf("https://site.com/rss.jpg")))
        assertEquals("https://site.com/rss.jpg", choose(p, listOf("https://site.com/rss.jpg"), cfg.copy(ogFirst = false)))
    }

    @Test
    fun `a rejected og image and a logo fall back to the next feed image`() {
        val p = images(og = { ImageCandidate("https://site.com/og.jpg", 200, 200) })
        assertEquals("https://other.com/hero.jpg", choose(p, listOf("https://site.com/logo.png", "https://other.com/hero.jpg")))
        assertNull(choose(p, listOf("https://site.com/logo.png")))
    }

    @Test
    fun `a slow og lookup times out and the feed image is used`() {
        val p = images(og = { Thread.sleep(3_000); ImageCandidate("https://site.com/og.jpg") })
        val started = System.nanoTime()
        assertEquals("https://site.com/rss.jpg", choose(p, listOf("https://site.com/rss.jpg")))
        assert(System.nanoTime() - started < 2_500_000_000L)
    }

    @Test
    fun `probe sizes unknown candidates and skips a small one`() {
        val sizes = mapOf("https://site.com/small.jpg" to (150 to 150), "https://site.com/big.jpg" to (1280 to 720))
        val p = images(probe = { sizes[it] })
        val on = cfg.copy(probe = true, ogFirst = false)
        assertEquals("https://site.com/big.jpg", choose(p, listOf("https://site.com/small.jpg", "https://site.com/big.jpg"), on))
        assertEquals("https://site.com/small.jpg", choose(p, listOf("https://site.com/small.jpg"), cfg.copy(ogFirst = false)))
    }

    @Test
    fun `og results are cached by page url`() {
        var calls = 0
        val cache = MemoryBadgeCache()
        val p = images(og = { calls++; ImageCandidate("https://site.com/og.jpg", 1200, 630) }, cache = cache)
        choose(p, emptyList())
        assertEquals("https://site.com/og.jpg", choose(p, emptyList()))
        assertEquals(1, calls)
    }
}
