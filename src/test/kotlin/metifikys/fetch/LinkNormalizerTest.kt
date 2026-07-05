package metifikys.fetch

import kotlin.test.Test
import kotlin.test.assertEquals

/** BUG-020: canonicalize feed links so tracking/casing/slash variants dedup to one row. */
class LinkNormalizerTest {

    @Test
    fun `strips utm and fbclid tracking params`() {
        assertEquals(
            "https://example.com/article",
            LinkNormalizer.normalize("https://example.com/article?utm_source=rss&utm_medium=feed&fbclid=abc")
        )
    }

    @Test
    fun `keeps non-tracking query params`() {
        assertEquals(
            "https://example.com/a?id=42",
            LinkNormalizer.normalize("https://example.com/a?id=42&utm_campaign=x")
        )
    }

    @Test
    fun `lowercases scheme and host but preserves path case`() {
        assertEquals(
            "https://example.com/Path/To",
            LinkNormalizer.normalize("HTTPS://Example.COM/Path/To")
        )
    }

    @Test
    fun `strips trailing slash including bare root`() {
        assertEquals("https://example.com/a", LinkNormalizer.normalize("https://example.com/a/"))
        assertEquals("https://example.com", LinkNormalizer.normalize("https://example.com/"))
    }

    @Test
    fun `drops fragment`() {
        assertEquals("https://example.com/a", LinkNormalizer.normalize("https://example.com/a#comments"))
    }

    @Test
    fun `returns unparseable link trimmed but unchanged`() {
        assertEquals("not a url", LinkNormalizer.normalize("  not a url  "))
    }

    @Test
    fun `two tracking-only variants of the same story normalize equal`() {
        val a = LinkNormalizer.normalize("https://www.site.com/story/?utm_source=a")
        val b = LinkNormalizer.normalize("https://www.site.com/story?utm_source=b&fbclid=z")
        assertEquals(a, b)
        assertEquals("https://www.site.com/story", a)
    }
}
