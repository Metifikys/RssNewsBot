package metifikys.fetch

import com.sun.net.httpserver.HttpServer
import metifikys.config.CategoryConfig
import metifikys.config.FeedConfig
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RssFetcherTest {

    // Validation OFF for tests that spin up a local HTTP server for fixture feeds.
    // Short retry delays to keep tests fast.
    private val fetcher = RssFetcher(enforceUrlValidation = false, maxRetries = 1, retryDelayMs = 0)

    @Test
    fun `fetchFeed returns emptyList on unreachable URL`() {
        val result = fetcher.fetchFeed(FeedConfig("https://this-url-does-not-exist-xyz.invalid/rss"), "tech")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchFeed returns emptyList on malformed URL`() {
        val result = fetcher.fetchFeed(FeedConfig("not-a-url"), "politics")
        assertEquals(emptyList(), result)
    }

    @Test
    fun `fetchAll returns emptyList when all feeds are unreachable`() {
        val categories = mapOf(
            "tech" to CategoryConfig(emoji = "💻", feeds = listOf(FeedConfig("https://this-does-not-exist.invalid/rss")), channelId = "@tech"),
            "gaming" to CategoryConfig(emoji = "🎮", feeds = listOf(FeedConfig("https://also-does-not-exist.invalid/rss")), channelId = "@gaming")
        )
        val result = fetcher.fetchAll(categories)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `fetchFeed parses valid entries and assigns correct category`() {
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Feed</title>
                <link>https://example.com</link>
                <item>
                  <title>Article One</title>
                  <link>https://example.com/1</link>
                  <description>Desc one</description>
                </item>
                <item>
                  <title>Article Two</title>
                  <link>https://example.com/2</link>
                  <description>Desc two</description>
                </item>
              </channel>
            </rss>
        """.trimIndent().toByteArray()

        withLocalFeed(rss) { url ->
            val result = fetcher.fetchFeed(FeedConfig(url), "tech")
            assertEquals(2, result.size)
            assertTrue(result.all { it.category == "tech" })
            assertEquals("Article One", result[0].title)
            assertEquals("https://example.com/1", result[0].link)
            assertEquals("Article Two", result[1].title)
        }
    }

    @Test
    fun `fetchFeed extracts image URL from enclosure`() {
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Feed</title>
                <link>https://example.com</link>
                <item>
                  <title>With Image Enclosure</title>
                  <link>https://example.com/with-img</link>
                  <description>Desc</description>
                  <enclosure url="https://cdn.example.com/pic.jpg" length="12345" type="image/jpeg" />
                </item>
              </channel>
            </rss>
        """.trimIndent().toByteArray()

        withLocalFeed(rss) { url ->
            val result = fetcher.fetchFeed(FeedConfig(url), "tech")
            assertEquals(1, result.size)
            assertEquals("https://cdn.example.com/pic.jpg", result[0].imageUrl)
        }
    }

    @Test
    fun `fetchFeed extracts image URL from media RSS thumbnail`() {
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>Test Feed</title>
                <link>https://example.com</link>
                <item>
                  <title>With Media Thumb</title>
                  <link>https://example.com/with-media</link>
                  <description>Desc</description>
                  <media:thumbnail url="https://cdn.example.com/thumb.png" />
                </item>
              </channel>
            </rss>
        """.trimIndent().toByteArray()

        withLocalFeed(rss) { url ->
            val result = fetcher.fetchFeed(FeedConfig(url), "tech")
            assertEquals(1, result.size)
            assertEquals("https://cdn.example.com/thumb.png", result[0].imageUrl)
        }
    }

    @Test
    fun `fetchFeed extracts first img src from description HTML when no enclosure or media tag`() {
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Feed</title>
                <link>https://example.com</link>
                <item>
                  <title>HTML Image</title>
                  <link>https://example.com/html-img</link>
                  <description><![CDATA[<p>hi</p><img src="https://cdn.example.com/inline.jpg" alt="x" /><img src="https://cdn.example.com/second.jpg"/>]]></description>
                </item>
              </channel>
            </rss>
        """.trimIndent().toByteArray()

        withLocalFeed(rss) { url ->
            val result = fetcher.fetchFeed(FeedConfig(url), "tech")
            assertEquals(1, result.size)
            assertEquals("https://cdn.example.com/inline.jpg", result[0].imageUrl)
        }
    }

    @Test
    fun `fetchFeed returns null imageUrl when entry has no image source`() {
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Feed</title>
                <link>https://example.com</link>
                <item>
                  <title>No Image</title>
                  <link>https://example.com/no-img</link>
                  <description>Just text, no images here.</description>
                </item>
              </channel>
            </rss>
        """.trimIndent().toByteArray()

        withLocalFeed(rss) { url ->
            val result = fetcher.fetchFeed(FeedConfig(url), "tech")
            assertEquals(1, result.size)
            assertEquals(null, result[0].imageUrl)
        }
    }

    @Test
    fun `fetchFeed prefers enclosure over media tag and HTML img`() {
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>Test Feed</title>
                <link>https://example.com</link>
                <item>
                  <title>Many Sources</title>
                  <link>https://example.com/many</link>
                  <description><![CDATA[<img src="https://cdn.example.com/inline.jpg" />]]></description>
                  <enclosure url="https://cdn.example.com/enclosure.jpg" type="image/jpeg" />
                  <media:thumbnail url="https://cdn.example.com/media.jpg" />
                </item>
              </channel>
            </rss>
        """.trimIndent().toByteArray()

        withLocalFeed(rss) { url ->
            val result = fetcher.fetchFeed(FeedConfig(url), "tech")
            assertEquals("https://cdn.example.com/enclosure.jpg", result[0].imageUrl)
        }
    }

    @Test
    fun `fetchFeed skips entries with blank links but keeps the rest`() {
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Feed</title>
                <link>https://example.com</link>
                <item>
                  <title>No Link Article</title>
                  <description>This entry has no link</description>
                </item>
                <item>
                  <title>Good Article</title>
                  <link>https://example.com/good</link>
                  <description>This one is fine</description>
                </item>
              </channel>
            </rss>
        """.trimIndent().toByteArray()

        withLocalFeed(rss) { url ->
            val result = fetcher.fetchFeed(FeedConfig(url), "tech")
            assertEquals(1, result.size)
            assertEquals("Good Article", result[0].title)
        }
    }

    @Test
    fun `fetchAll aggregates articles across multiple feeds in same category`() {
        val rssA = rssWithItems("https://example.com/a1", "https://example.com/a2")
        val rssB = rssWithItems("https://example.com/b1")

        withLocalFeed(rssA) { urlA ->
            withLocalFeed(rssB) { urlB ->
                val categories = mapOf(
                    "tech" to CategoryConfig(
                        emoji = "💻",
                        feeds = listOf(FeedConfig(urlA), FeedConfig(urlB)),
                        channelId = "@tech"
                    )
                )
                val result = fetcher.fetchAll(categories)
                assertEquals(3, result.size)
                assertTrue(result.all { it.category == "tech" })
            }
        }
    }

    @Test
    fun `fetchAll continues other feeds when one feed in a category is unreachable`() {
        val rss = rssWithItems("https://example.com/1")
        withLocalFeed(rss) { goodUrl ->
            val categories = mapOf(
                "tech" to CategoryConfig(
                    emoji = "💻",
                    feeds = listOf(FeedConfig("https://does-not-exist.invalid/rss"), FeedConfig(goodUrl)),
                    channelId = "@tech"
                )
            )
            val result = fetcher.fetchAll(categories)
            // Bad feed returns empty, good feed still parsed — total = 1
            assertEquals(1, result.size)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun rssWithItems(vararg links: String): ByteArray {
        val items = links.joinToString("\n") { link ->
            "<item><title>Title $link</title><link>$link</link><description>desc</description></item>"
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Feed</title>
                <link>https://example.com</link>
                $items
              </channel>
            </rss>
        """.trimIndent().toByteArray()
    }

    private fun withLocalFeed(body: ByteArray, block: (url: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val port = server.address.port
        server.createContext("/rss") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            block("http://localhost:$port/rss")
        } finally {
            server.stop(0)
        }
    }
}
