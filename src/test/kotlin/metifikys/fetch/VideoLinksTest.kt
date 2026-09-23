package metifikys.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoLinksTest {

    @Test
    fun `the embed in the article body wins over the site promo in the aside`() {
        val html = """
            <html><head><meta property="og:video" content="https://www.youtube.com/embed/PROMO000000"></head><body>
              <header><iframe src="https://www.youtube.com/embed/HEADER00000"></iframe></header>
              <aside class="sidebar"><iframe src="https://www.youtube.com/embed/SIDEBAR0000"></iframe></aside>
              <article>
                <p>Team Cherry showed the release date today.</p>
                <iframe data-src="https://www.youtube-nocookie.com/embed/aBcDeFgHiJ0?rel=0"></iframe>
                <div class="related-videos"><iframe src="https://www.youtube.com/embed/RELATED0000"></iframe></div>
              </article>
            </body></html>
        """.trimIndent()
        assertEquals("aBcDeFgHiJ0", VideoLinks.fromPage(html, "https://ign.com/a"))
    }

    @Test
    fun `lite-youtube and data-youtube-id embeds are read, links come after embeds`() {
        val lite = """<html><body><main><p>See <a href="https://youtu.be/LINK0000000">this</a></p><lite-youtube videoid="LITE0000000"></lite-youtube></main></body></html>"""
        assertEquals("LITE0000000", VideoLinks.fromPage(lite))
        val data = """<html><body><article><div data-youtube-id="DATA0000000"></div></article></body></html>"""
        assertEquals("DATA0000000", VideoLinks.fromPage(data))
        val link = """<html><body><article><a href="https://www.youtube.com/watch?feature=share&amp;v=WATCH000000">watch</a></article></body></html>"""
        assertEquals("WATCH000000", VideoLinks.fromPage(link))
    }

    @Test
    fun `a page without an article body falls back to og video only`() {
        val html = """<html><head><meta property="og:video:url" content="https://www.youtube.com/watch?v=OGVIDEO0000"></head>
            <body><div><iframe src="https://www.youtube.com/embed/BODY0000000"></iframe></div></body></html>"""
        assertEquals("OGVIDEO0000", VideoLinks.fromPage(html))
        assertNull(VideoLinks.fromPage("<html><body><article><p>No video.</p></article></body></html>"))
    }

    @Test
    fun `a feed description is searched whole`() {
        assertEquals("FRAG0000000", VideoLinks.fromFragment("""<p>Trailer:</p><iframe src="https://www.youtube.com/embed/FRAG0000000"></iframe>"""))
        assertNull(VideoLinks.fromFragment("<p>No video here.</p>"))
    }

    @Test
    fun `ids are read from every YouTube URL shape`() {
        assertEquals("abcdefghijk", VideoLinks.idInUrl("https://youtu.be/abcdefghijk?t=10"))
        assertEquals("abcdefghijk", VideoLinks.idInUrl("https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals("abcdefghijk", VideoLinks.idInUrl("https://m.youtube.com/shorts/abcdefghijk"))
        assertEquals("abcdefghijk", VideoLinks.idInUrl("https://www.youtube.com/embed/abcdefghijk"))
        assertNull(VideoLinks.idInUrl("https://vimeo.com/12345"))
    }
}
