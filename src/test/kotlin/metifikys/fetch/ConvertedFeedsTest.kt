package metifikys.fetch

import com.sun.net.httpserver.HttpServer
import metifikys.config.FeedConfig
import metifikys.model.Article
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `feeds[].kind` / `minTextChars` / `titlePrefix` / `exclude` / `repointLinks` on real feed shapes. */
class ConvertedFeedsTest {

    private fun fetch(xml: String, feed: (String) -> FeedConfig): List<Article> {
        val body = xml.trimIndent().toByteArray()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/feed") { ex ->
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            return fetcher.fetchFeed(feed("http://localhost:${server.address.port}/feed"), "games")
        } finally {
            fetcher.shutdown()
            server.stop(0)
        }
    }

    /** Mastodon's `/@user.rss`: items have no `<title>`, the post is HTML in the description. */
    private val mastodon = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Studio</title><link>https://mastodon.gamedev.place/@studio</link>
        <item><guid>https://mastodon.gamedev.place/@studio/1</guid><link>https://mastodon.gamedev.place/@studio/1</link>
          <description>&lt;p&gt;Our new game ships on March 3rd!&lt;/p&gt;&lt;p&gt;Wishlist it now.&lt;/p&gt;</description></item>
        <item><guid>https://mastodon.gamedev.place/@studio/2</guid><link>https://mastodon.gamedev.place/@studio/2</link>
          <description>&lt;p&gt;🔥&lt;/p&gt;</description></item>
        </channel></rss>
    """

    @Test
    fun `a Mastodon post without a title gets its first line, a plain feed keeps the empty title`() {
        val social = fetch(mastodon) { FeedConfig(it, kind = "social", minTextChars = 5, label = "Studio") }
        assertEquals(listOf("Our new game ships on March 3rd!"), social.map { it.title }, "the 🔥 post is below minTextChars")
        assertEquals("social", social.single().meta()!!.source!!.kind)
        assertEquals("official account: Studio", social.single().meta()!!.source!!.promptHint())

        val plain = fetch(mastodon) { FeedConfig(it) }
        assertEquals(listOf("", ""), plain.map { it.title })
        assertTrue(plain.all { it.metaJson == null })
    }

    @Test
    fun `an RSSHub telegram post keeps its image and a duplicate title is replaced`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>TG</title><link>https://t.me/s/studio</link>
            <item><title>Реліз оновлення 1.5 вже доступний у Steam. У новій версії з'явилися…</title>
              <link>https://t.me/studio/77</link>
              <description>&lt;img src="https://cdn.tg/p.jpg"&gt;&lt;br&gt;Реліз оновлення 1.5 вже доступний у Steam. У новій версії з'явилися нові мапи та режим кооперативу.</description></item>
            </channel></rss>
        """
        val a = fetch(xml) { FeedConfig(it, kind = "social") }.single()
        assertEquals("Реліз оновлення 1.5 вже доступний у Steam.", a.title)
        assertEquals("https://cdn.tg/p.jpg", a.imageUrl)
        assertEquals("t.me/studio", a.meta()!!.source!!.account)
    }

    @Test
    fun `a YouTube entry takes its media group thumbnail only with a kind`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
             <title>NASA</title>
             <entry>
              <id>yt:video:abc123</id>
              <yt:videoId>abc123</yt:videoId>
              <title>Artemis III crew walkout</title>
              <link rel="alternate" href="https://www.youtube.com/watch?v=abc123"/>
              <published>2026-09-20T10:00:00+00:00</published>
              <media:group>
               <media:title>Artemis III crew walkout</media:title>
               <media:content url="https://www.youtube.com/v/abc123?version=3" type="application/x-shockwave-flash" width="640" height="390"/>
               <media:thumbnail url="https://i1.ytimg.com/vi/abc123/hqdefault.jpg" width="480" height="360"/>
               <media:description>Crew walks out to the pad.</media:description>
              </media:group>
             </entry>
            </feed>
        """
        assertEquals("https://i1.ytimg.com/vi/abc123/hqdefault.jpg", fetch(xml) { FeedConfig(it, kind = "video") }.single().imageUrl)
        assertNull(fetch(xml) { FeedConfig(it) }.single().imageUrl)
    }

    @Test
    fun `GitHub releases get the title prefix and lose excluded pre-releases`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Release notes from ollama</title>
             <entry><id>tag:github.com,2008:Repository/1/v0.12.3</id><title>v0.12.3</title>
              <link rel="alternate" href="https://github.com/ollama/ollama/releases/tag/v0.12.3"/><updated>2026-09-20T10:00:00Z</updated>
              <content type="html">&lt;p&gt;Fixes and a new model.&lt;/p&gt;</content></entry>
             <entry><id>tag:github.com,2008:Repository/1/v0.12.4-rc1</id><title>v0.12.4-rc1</title>
              <link rel="alternate" href="https://github.com/ollama/ollama/releases/tag/v0.12.4-rc1"/><updated>2026-09-21T10:00:00Z</updated>
              <content type="html">&lt;p&gt;Release candidate.&lt;/p&gt;</content></entry>
             <entry><id>tag:github.com,2008:Repository/1/ollama-v0.12.2</id><title>Ollama v0.12.2</title>
              <link rel="alternate" href="https://github.com/ollama/ollama/releases/tag/v0.12.2"/><updated>2026-09-19T10:00:00Z</updated>
              <content type="html">&lt;p&gt;Older.&lt;/p&gt;</content></entry>
            </feed>
        """
        val titles = fetch(xml) { FeedConfig(it, kind = "release", titlePrefix = "Ollama ", exclude = listOf("-RC", "nightly")) }.map { it.title }
        assertEquals(listOf("Ollama v0.12.3", "Ollama v0.12.2"), titles)
    }

    @Test
    fun `repointLinks turns a short link post into the article it links to`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>TG</title><link>https://t.me/s/news</link>
            <item><title>Big story</title><link>https://t.me/news/5</link>
              <description>Big story: &lt;a href="https://site.com/story"&gt;site.com/story&lt;/a&gt;</description></item>
            </channel></rss>
        """
        val a = fetch(xml) { FeedConfig(it, kind = "social", repointLinks = true) }.single()
        assertEquals("https://site.com/story", a.link)
        assertTrue(a.fetchFullContent)
        assertEquals("https://t.me/news/5", a.meta()!!.source!!.postUrl)
        assertEquals("https://t.me/news/5", fetch(xml) { FeedConfig(it, kind = "social") }.single().link, "off without repointLinks")
    }
}
