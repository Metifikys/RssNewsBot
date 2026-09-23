package metifikys.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SocialLinkExtractorTest {

    private val feed = "http://192.168.77.7:1200/telegram/channel/studio"
    private val post = "https://t.me/studio/123"

    @Test
    fun `a short post with one outbound link is repointed at it`() {
        val r = SocialLinkExtractor.rewrite(post, """Our devlog is out: <a href="https://studio.com/blog/devlog-7?utm_source=tg">https://studio.com/blog/devlog-7…</a>""", feed)!!
        assertEquals("https://studio.com/blog/devlog-7", r.link)
        assertEquals(post, r.postUrl)
        assertEquals("t.me/studio", r.account)
    }

    @Test
    fun `a bare URL in the text counts as the link`() {
        assertEquals("https://news.example/a", SocialLinkExtractor.rewrite(post, "Read this https://news.example/a.", feed)?.link)
    }

    @Test
    fun `two different outbound links are ambiguous`() {
        assertNull(SocialLinkExtractor.rewrite(post, """<a href="https://a.com/1">a</a> and <a href="https://b.com/2">b</a>""", feed))
    }

    @Test
    fun `social, store, converter and private hosts are never targets`() {
        for (href in listOf(
            "https://t.me/other/5", "https://x.com/user/status/1", "https://www.youtube.com/watch?v=x",
            "https://store.steampowered.com/app/1/", "http://192.168.77.7:1200/other", "http://10.0.0.5/admin"
        )) {
            assertNull(SocialLinkExtractor.rewrite(post, """Look <a href="$href">here</a>""", feed), href)
        }
    }

    @Test
    fun `a social link beside the article link does not block the rewrite`() {
        val html = """New trailer! <a href="https://t.me/studio?q=%23news">#news</a> <a href="https://site.com/trailer">watch</a>"""
        assertEquals("https://site.com/trailer", SocialLinkExtractor.rewrite(post, html, feed)?.link)
    }

    @Test
    fun `a long post is its own source`() {
        val long = "Big announcement. " + "Details ".repeat(40) + """<a href="https://site.com/x">more</a>"""
        assertNull(SocialLinkExtractor.rewrite(post, long, feed))
    }

    @Test
    fun `accounts from post URLs`() {
        assertEquals("t.me/chan", SocialLinkExtractor.accountOf("https://t.me/s/chan/9"))
        assertEquals("x.com/dev", SocialLinkExtractor.accountOf("https://x.com/dev/status/1"))
        assertEquals("bsky.app/a.bsky.social", SocialLinkExtractor.accountOf("https://bsky.app/profile/a.bsky.social/post/3k"))
        assertEquals("mastodon.gamedev.place/studio", SocialLinkExtractor.accountOf("https://mastodon.gamedev.place/@studio/1133"))
        assertNull(SocialLinkExtractor.accountOf("https://example.com/news/1"))
    }
}
