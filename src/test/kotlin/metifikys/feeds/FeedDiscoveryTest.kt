package metifikys.feeds

import com.sun.net.httpserver.HttpServer
import metifikys.fetch.UrlGuard
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeedDiscoveryTest {

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>Blog</title><link>https://blog.example</link>
        <item><title>A</title><link>https://blog.example/a?utm_source=rss</link><pubDate>Mon, 21 Sep 2026 10:00:00 GMT</pubDate></item>
        <item><title>B</title><link>https://blog.example/b</link><pubDate>Wed, 23 Sep 2026 10:00:00 GMT</pubDate></item>
        </channel></rss>
    """.trimIndent()

    private val atom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"><title>A</title>
         <entry><id>1</id><title>x</title><link href="https://blog.example/x"/><updated>2026-09-20T10:00:00Z</updated></entry>
        </feed>
    """.trimIndent()

    private class Route(val status: Int, val type: String = "text/html", val body: String = "", val location: String? = null)

    private fun withServer(routes: Map<String, Route>, block: (host: String, hits: AtomicInteger) -> Unit) {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            val r = routes[ex.requestURI.path] ?: Route(404)
            r.location?.let { ex.responseHeaders.add("Location", it) }
            ex.responseHeaders.add("Content-Type", r.type)
            val bytes = r.body.toByteArray()
            ex.sendResponseHeaders(r.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
        try {
            block("localhost:${server.address.port}", hits)
        } finally {
            server.stop(0)
        }
    }

    private fun discovery() = FeedDiscovery(guard = UrlGuard.OFF, scheme = "http")

    @Test
    fun `link rel alternate with a relative href is followed and validated`() {
        val home = """<html><head><link rel="alternate" type="application/rss+xml" href="/posts/index.xml"></head></html>"""
        withServer(mapOf("/" to Route(200, body = home), "/posts/index.xml" to Route(200, "application/rss+xml", rss))) { host, _ ->
            val r = assertIs<FeedDiscovery.Result.Found>(discovery().discover(host, FeedDiscovery.Budget(10)))
            assertEquals("http://$host/posts/index.xml", r.feedUrl)
            assertEquals(2, r.entries)
            assertEquals(1.0, r.itemsPerDay)
            assertEquals(listOf("https://blog.example/a", "https://blog.example/b"), r.links)
        }
    }

    @Test
    fun `an Atom alternate works too`() {
        val home = """<link rel="alternate" type="application/atom+xml" href="/atom">"""
        withServer(mapOf("/" to Route(200, body = home), "/atom" to Route(200, "application/atom+xml", atom))) { host, _ ->
            assertEquals("http://$host/atom", assertIs<FeedDiscovery.Result.Found>(discovery().discover(host, FeedDiscovery.Budget(10))).feedUrl)
        }
    }

    @Test
    fun `without an alternate the fallback paths are probed, following a redirect`() {
        withServer(mapOf(
            "/" to Route(200, body = "<html>no feed link</html>"),
            "/feed" to Route(301, location = "/feed/"),
            "/feed/" to Route(200, "application/rss+xml", rss)
        )) { host, _ ->
            assertEquals("http://$host/feed/", assertIs<FeedDiscovery.Result.Found>(discovery().discover(host, FeedDiscovery.Budget(10))).feedUrl)
        }
    }

    @Test
    fun `HTML served as a feed is no feed, and the probes stop at the budget`() {
        val routes = FeedDiscovery.FALLBACK_PATHS.associateWith { Route(200, body = "<html>soft 404</html>") } + ("/" to Route(200, body = "<html/>"))
        withServer(routes) { host, hits ->
            assertIs<FeedDiscovery.Result.None>(discovery().discover(host, FeedDiscovery.Budget(20)))
            assertEquals(1 + FeedDiscovery.FALLBACK_PATHS.size, hits.get())
        }
        withServer(routes) { host, hits ->
            assertIs<FeedDiscovery.Result.Error>(discovery().discover(host, FeedDiscovery.Budget(3)))
            assertEquals(3, hits.get())
        }
    }

    @Test
    fun `a private host is refused before any request`() {
        val hit = AtomicInteger()
        val d = FeedDiscovery(http = { hit.incrementAndGet(); null })
        for (host in listOf("192.168.77.7:1200", "10.1.2.3", "localhost:8080")) {
            val r = assertIs<FeedDiscovery.Result.Error>(d.discover(host, FeedDiscovery.Budget(10)))
            assertTrue("Private" in r.reason, r.reason)
        }
        assertEquals(0, hit.get())
    }

    @Test
    fun `a redirect into the LAN is refused`() {
        val requested = mutableListOf<String>()
        // public.example "resolves" to a public address, so only the redirect target is judged.
        val publicDns = UrlGuard(resolve = { h ->
            if (h == "public.example") arrayOf(InetAddress.getByAddress(h, byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
            else InetAddress.getAllByName(h)
        })
        val d = FeedDiscovery(guard = publicDns, http = { url ->
            requested += url
            FeedDiscovery.Response(302, "text/html", "http://192.168.1.1/admin", ByteArray(0))
        })
        d.discover("public.example", FeedDiscovery.Budget(10))
        assertTrue(requested.none { "192.168" in it }, requested.toString())
    }
}
