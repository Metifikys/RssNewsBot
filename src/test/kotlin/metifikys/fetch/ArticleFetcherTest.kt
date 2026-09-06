package metifikys.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArticleFetcherTest {

    // Validation OFF — these tests exercise pure HTML parsing, no network calls.
    private val fetcher = ArticleFetcher(
        RssFetcher(enforceUrlValidation = false),
        enforceUrlValidation = false
    )

    private val base = "https://example.com/story"

    @Test
    fun `extractOgImage returns og image when present`() {
        val html = """
            <html><head>
              <meta property="og:image" content="https://cdn.example.com/og.jpg">
            </head><body></body></html>
        """.trimIndent()

        assertEquals("https://cdn.example.com/og.jpg", fetcher.extractOgImage(html, base))
    }

    @Test
    fun `extractOgImage prefers og image over twitter image`() {
        val html = """
            <html><head>
              <meta name="twitter:image" content="https://cdn.example.com/twitter.jpg">
              <meta property="og:image" content="https://cdn.example.com/og.jpg">
            </head><body></body></html>
        """.trimIndent()

        assertEquals("https://cdn.example.com/og.jpg", fetcher.extractOgImage(html, base))
    }

    @Test
    fun `extractOgImage falls back to twitter image when no og image`() {
        val html = """
            <html><head>
              <meta name="twitter:image" content="https://cdn.example.com/twitter.jpg">
            </head><body></body></html>
        """.trimIndent()

        assertEquals("https://cdn.example.com/twitter.jpg", fetcher.extractOgImage(html, base))
    }

    @Test
    fun `extractOgImage resolves relative url against base`() {
        val html = """
            <html><head>
              <meta property="og:image" content="/media/pic.jpg">
            </head><body></body></html>
        """.trimIndent()

        assertEquals("https://example.com/media/pic.jpg", fetcher.extractOgImage(html, base))
    }

    @Test
    fun `extractOgImage returns null when no preview meta tags present`() {
        val html = "<html><head><title>No image here</title></head><body><p>text</p></body></html>"

        assertNull(fetcher.extractOgImage(html, base))
    }

    // ── extractMetaDescription: og-description fallback ──────────────────────

    @Test
    fun `extractMetaDescription prefers og-description over meta description`() {
        val html = """
            <html><head>
              <meta name="description" content="Generic site meta description, long enough to pass the stub filter." />
              <meta property="og:description" content="The real article lede lives here and is clearly long enough." />
            </head><body></body></html>
        """.trimIndent()
        assertEquals(
            "The real article lede lives here and is clearly long enough.",
            fetcher.extractMetaDescription(html, base)
        )
    }

    @Test
    fun `extractMetaDescription falls back to plain meta description`() {
        val html = """
            <html><head>
              <meta name="description" content="A perfectly serviceable page description of adequate length." />
            </head><body><p>short</p></body></html>
        """.trimIndent()
        assertEquals(
            "A perfectly serviceable page description of adequate length.",
            fetcher.extractMetaDescription(html, base)
        )
    }

    @Test
    fun `extractMetaDescription rejects stub descriptions and absent tags`() {
        val stub = """<html><head><meta property="og:description" content="Read more…" /></head><body></body></html>"""
        assertNull(fetcher.extractMetaDescription(stub, base))
        assertNull(fetcher.extractMetaDescription("<html><head></head><body></body></html>", base))
    }
}
