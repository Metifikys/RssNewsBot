package metifikys.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedditLinkExtractorTest {

    private val permalink = "https://www.reddit.com/r/technology/comments/1vovlwd/chinese_magnetic_sensor_breakthrough"

    /** Verbatim shape of a reddit link post's `<description>`, trimmed of the long image query. */
    private val linkPostHtml = """
        <table> <tr><td> <a href="$permalink/">
        <img src="https://external-preview.redd.it/57pf.jpeg?width=640" alt="t" title="t" /> </a> </td><td>
        &#32; submitted by &#32; <a href="https://www.reddit.com/user/malcolm58"> /u/malcolm58 </a> <br/>
        <span><a href="https://www.scmp.com/news/china/science/article/3363975/chinese-magnetic-sensor">[link]</a></span>
        &#32; <span><a href="$permalink/">[comments]</a></span> </td></tr></table>
    """.trimIndent()

    /** A self post: the same footer, but `[link]` points back at the permalink. */
    private val selfPostHtml = """
        <!-- SC_OFF --><div class="md"><p>hey again! I run a EU PC hardware price tracker.</p></div><!-- SC_ON -->
        &#32; submitted by &#32; <a href="https://www.reddit.com/user/egudegi"> /u/egudegi </a> <br/>
        <span><a href="$permalink/">[link]</a></span>
        &#32; <span><a href="$permalink/">[comments]</a></span>
    """.trimIndent()

    @Test
    fun `link post is repointed at its outbound article`() {
        val rewrite = RedditLinkExtractor.rewrite(permalink, linkPostHtml)
        assertEquals(
            "https://www.scmp.com/news/china/science/article/3363975/chinese-magnetic-sensor",
            rewrite?.link
        )
    }

    @Test
    fun `link post loses the boilerplate card it had instead of a description`() {
        // The card is pure markup — dropping it keeps reddit's own URLs out of the render prompt.
        assertEquals("", RedditLinkExtractor.rewrite(permalink, linkPostHtml)?.description)
    }

    @Test
    fun `self post is left alone because its link anchor is the permalink`() {
        assertNull(RedditLinkExtractor.rewrite(permalink, selfPostHtml))
    }

    @Test
    fun `a self post that also cites a source keeps its body text`() {
        val html = selfPostHtml.replace(
            "<span><a href=\"$permalink/\">[link]</a></span>",
            "<span><a href=\"https://arxiv.org/abs/2608.09888\">[link]</a></span>"
        )
        val rewrite = RedditLinkExtractor.rewrite(permalink, html)
        assertEquals("https://arxiv.org/abs/2608.09888", rewrite?.link)
        assertTrue(
            rewrite!!.description.contains("EU PC hardware price tracker"),
            "the OP's own text is the article body and must survive: '${rewrite.description}'"
        )
    }

    @Test
    fun `reddit-hosted media is not a better link than the permalink`() {
        for (target in listOf("https://i.redd.it/abc.png", "https://v.redd.it/xyz", "https://reddit.com/r/x/comments/y")) {
            val html = linkPostHtml.replace(
                "https://www.scmp.com/news/china/science/article/3363975/chinese-magnetic-sensor",
                target
            )
            assertNull(RedditLinkExtractor.rewrite(permalink, html), "must not repoint at $target")
        }
    }

    @Test
    fun `non-http targets are rejected`() {
        val html = linkPostHtml.replace(
            "https://www.scmp.com/news/china/science/article/3363975/chinese-magnetic-sensor",
            "javascript:alert(1)"
        )
        assertNull(RedditLinkExtractor.rewrite(permalink, html))
    }

    @Test
    fun `the outbound link is canonicalized like any other article link`() {
        val html = linkPostHtml.replace(
            "https://www.scmp.com/news/china/science/article/3363975/chinese-magnetic-sensor",
            "https://www.scmp.com/news/article/?utm_source=reddit&amp;id=7"
        )
        assertEquals("https://www.scmp.com/news/article?id=7", RedditLinkExtractor.rewrite(permalink, html)?.link)
    }

    @Test
    fun `non-reddit feeds are never touched`() {
        val html = """<p>Read more</p> <a href="https://elsewhere.example/x">[link]</a>"""
        assertNull(RedditLinkExtractor.rewrite("https://www.theverge.com/1", html))
        assertFalse(RedditLinkExtractor.isRedditUrl("https://www.theverge.com/1"))
        assertFalse(RedditLinkExtractor.isRedditUrl("https://notreddit.com/1"))
        assertTrue(RedditLinkExtractor.isRedditUrl("https://old.reddit.com/r/x"))
    }

    @Test
    fun `a description without a link anchor is left alone`() {
        assertNull(RedditLinkExtractor.rewrite(permalink, "<p>no anchors here</p>"))
        assertNull(RedditLinkExtractor.rewrite(permalink, ""))
    }
}
