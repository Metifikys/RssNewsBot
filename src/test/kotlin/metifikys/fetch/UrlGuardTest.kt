package metifikys.fetch

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.FeedConfig
import metifikys.config.FetcherConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlGuardTest {

    private fun config(feeds: List<String>, allowlist: List<String> = emptyList()): AppConfig {
        val cfg = mockk<AppConfig>(relaxed = true)
        every { cfg.categories } returns mapOf(
            "tech" to CategoryConfig(emoji = "x", feeds = feeds.map { FeedConfig(it) }, channelId = "@t")
        )
        every { cfg.fetcher } returns FetcherConfig(privateHostAllowlist = allowlist)
        return cfg
    }

    /** Fake DNS: [names] map to fixed addresses, everything else resolves for real (literals only in these tests). */
    private fun dns(vararg names: Pair<String, String>): (String) -> Array<InetAddress> = { host ->
        names.toMap()[host]?.let { arrayOf(InetAddress.getByName(it)) } ?: InetAddress.getAllByName(host)
    }

    private fun UrlGuard.accepts(url: String): Boolean = runCatching { check(url) }.isSuccess

    // ── the rule ──────────────────────────────────────────────────────────────

    @Test
    fun `a configured private feed host is allowed, per host and port`() {
        val guard = UrlGuard.forConfig(config(listOf("http://192.168.77.7:1200/telegram/channel/foo", "https://www.reddit.com/r/x/.rss")))
        assertTrue(guard.accepts("http://192.168.77.7:1200/twitter/user/bar"))
        assertFalse(guard.accepts("http://192.168.77.7:8080/admin"), "another port of the LAN box is not a feed host")
        assertFalse(guard.accepts("http://192.168.50.9:1200/"), "another LAN host")
        assertFalse(guard.accepts("https://192.168.77.7/"), "default port 443 is not 1200")
    }

    @Test
    fun `private, loopback, link-local and unique-local targets are refused, public ones pass`() {
        val guard = UrlGuard()
        for (url in listOf(
            "http://127.0.0.1/", "http://localhost:8080/", "http://10.1.2.3/", "http://172.16.0.1/", "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/", "http://0.0.0.0/", "http://224.0.0.1/",
            "http://[::1]:8080/", "http://[::]/", "http://[fd00::1]/", "http://[fe80::1]/", "http://[fec0::1]/",
            "http://[::ffff:10.0.0.1]/", "http://[::ffff:127.0.0.1]/", "http://[64:ff9b::a00:1]/", "http://[2002:a00:1::]/"
        )) {
            assertFalse(guard.accepts(url), url)
        }
        for (url in listOf("http://8.8.8.8/", "https://1.1.1.1:8443/x", "http://[2606:4700:4700::1111]/", "http://[2002:808:808::]/")) {
            assertTrue(guard.accepts(url), url)
        }
    }

    @Test
    fun `the check is on the resolved address, not the spelling`() {
        val guard = UrlGuard(resolve = dns("evil.example" to "10.0.0.5", "news.example" to "93.184.216.34"))
        assertFalse(guard.accepts("https://evil.example/page"))
        assertTrue(guard.accepts("https://news.example/page"))
    }

    @Test
    fun `an IPv4-mapped IPv6 address is judged by its IPv4 part even as an Inet6Address`() {
        val mapped = ByteArray(16).also { it[10] = 0xff.toByte(); it[11] = 0xff.toByte(); it[12] = 10; it[15] = 1 }
        assertTrue(UrlGuard.isNonPublic(Inet6Address.getByAddress(null, mapped, -1)))
        val mappedPublic = mapped.copyOf().also { it[12] = 8; it[13] = 8; it[14] = 8; it[15] = 8 }
        assertFalse(UrlGuard.isNonPublic(Inet6Address.getByAddress(null, mappedPublic, -1)))
    }

    @Test
    fun `only http and https`() {
        val guard = UrlGuard()
        assertFalse(guard.accepts("file:///etc/passwd"))
        assertFalse(guard.accepts("ftp://8.8.8.8/"))
        assertFalse(guard.accepts("not a url"))
    }

    @Test
    fun `privateHostAllowlist adds hosts, a bare host for any port`() {
        val guard = UrlGuard(
            listOf("nas.lan", "rsshub.lan:1200", "[fd00::5]:80"),
            resolve = dns("nas.lan" to "192.168.1.10", "rsshub.lan" to "192.168.1.11")
        )
        assertTrue(guard.accepts("http://nas.lan:5000/feed"))
        assertTrue(guard.accepts("https://nas.lan/feed"))
        assertTrue(guard.accepts("http://rsshub.lan:1200/x"))
        assertFalse(guard.accepts("http://rsshub.lan:8080/x"))
        assertTrue(guard.accepts("http://[fd00::5]/"))
        assertFalse(guard.accepts("http://[fd00::6]/"))

        val fromConfig = UrlGuard.forConfig(config(emptyList(), allowlist = listOf("192.168.1.20:8080")))
        assertTrue(fromConfig.accepts("http://192.168.1.20:8080/"))
        assertFalse(fromConfig.accepts("http://192.168.1.20:8081/"))
    }

    @Test
    fun `trust entries parse every documented form and refuse garbage`() {
        assertEquals(UrlGuard.Trust("rsshub.lan", 1200), UrlGuard.parseTrustEntry(" RSSHub.lan:1200 "))
        assertEquals(UrlGuard.Trust("nas.lan", null), UrlGuard.parseTrustEntry("nas.lan"))
        assertEquals(UrlGuard.Trust("fd00::5", 8080), UrlGuard.parseTrustEntry("[fd00::5]:8080"))
        assertEquals(UrlGuard.Trust("fd00::5", null), UrlGuard.parseTrustEntry("fd00::5"))
        assertEquals(UrlGuard.Trust("192.168.77.7", 1200), UrlGuard.parseTrustEntry("http://192.168.77.7:1200/rss"))
        assertEquals(UrlGuard.Trust("example.com", 443), UrlGuard.parseTrustEntry("https://example.com/feed"))
        for (bad in listOf("", "host:0", "host:99999", "host:abc", "a b", "host/path")) {
            assertThrows<IllegalArgumentException>(bad) { UrlGuard.parseTrustEntry(bad) }
        }
    }

    @Test
    fun `privateHostAllowlist is read from config and a malformed entry fails the load`() {
        fun load(allowlist: String): AppConfig {
            val file = java.io.File.createTempFile("config-urlguard-test", ".yaml")
            file.writeText(
                """
                telegram:
                  botToken: "tok"
                openai:
                  apiKey: "sk-test"
                database:
                  path: "test.db"
                scheduler:
                  intervalMinutes: 30
                fetcher:
                  privateHostAllowlist: $allowlist
                categories:
                  tech:
                    emoji: "x"
                    channelId: "@tech"
                    feeds:
                      - http://192.168.77.7:1200/telegram/channel/foo
                """.trimIndent()
            )
            try {
                return metifikys.config.ConfigLoader.load(file.absolutePath)
            } finally {
                file.delete()
            }
        }
        val guard = UrlGuard.forConfig(load("[\"192.168.1.20:8080\"]"))
        assertTrue(guard.accepts("http://192.168.77.7:1200/x"), "feed host")
        assertTrue(guard.accepts("http://192.168.1.20:8080/x"), "allowlisted")
        assertFalse(guard.accepts("http://192.168.1.20:8081/x"))
        val ex = assertThrows<IllegalArgumentException> { load("[\"nas.lan:99999\"]") }
        assertTrue("fetcher.privateHostAllowlist" in ex.message.orEmpty(), ex.message)
    }

    // ── wired into the fetchers ───────────────────────────────────────────────

    private class Server(handler: (String) -> Triple<Int, String?, String>) : AutoCloseable {
        val hits = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                hits.incrementAndGet()
                val (status, location, body) = handler(ex.requestURI.path)
                location?.let { ex.responseHeaders.add("Location", it) }
                ex.responseHeaders.add("Content-Type", if (body.startsWith("<?xml")) "application/rss+xml" else "text/html")
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    /** Preview-image path only: it fetches the page directly (the text paths ask markdown.new first). */
    private val page = """<html><head><meta property="og:image" content="https://cdn.example/p.jpg"></head><body></body></html>"""

    private fun previewImage(fetcher: ArticleFetcher, link: String): String? =
        fetcher.enrich(listOf(metifikys.model.Article("tech", "t", link, "d"))) { true }.single().imageUrl

    private val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>t</title><link>https://e.x</link>
        <item><title>One</title><link>https://example.com/1</link><description>d</description></item></channel></rss>"""

    @Test
    fun `article link to the configured feed host is fetched, to any other private host it is not`() {
        Server { Triple(200, null, page) }.use { feedHost ->
            Server { Triple(200, null, page) }.use { other ->
                val guard = UrlGuard(listOf("${feedHost.base}/rss"))
                val fetcher = ArticleFetcher(guard)
                assertEquals("https://cdn.example/p.jpg", previewImage(fetcher, "${feedHost.base}/article"))
                assertNull(previewImage(fetcher, "${other.base}/article"))
                assertEquals(0, other.hits.get(), "a refused link never reaches the network")
            }
        }
    }

    @Test
    fun `an article redirect into a private host is refused at the hop`() {
        Server { Triple(200, null, page) }.use { lan ->
            // The first server stands in for a public page: trusted, so only the hop is judged.
            Server { Triple(302, "${lan.base}/admin", "") }.use { origin ->
                val fetcher = ArticleFetcher(UrlGuard(listOf(origin.base)))
                assertNull(previewImage(fetcher, "${origin.base}/story"))
                assertEquals(1, origin.hits.get())
                assertEquals(0, lan.hits.get(), "the redirect target must not receive a request")
            }
        }
    }

    @Test
    fun `a trusted feed host serves feeds, and its redirect into another private host is refused`() {
        Server { Triple(200, null, rss) }.use { lan ->
            Server { path -> if (path == "/moved") Triple(301, "${lan.base}/rss", "") else Triple(200, null, rss) }.use { feedHost ->
                val recorder = FeedHealthRecorder()
                val fetcher = RssFetcher(urlGuard = UrlGuard(listOf("${feedHost.base}/rss")), maxAttempts = 1, retryDelayMs = 0, healthRecorder = recorder)
                try {
                    assertEquals(1, fetcher.fetchFeed(FeedConfig("${feedHost.base}/rss"), "tech").size)
                    assertTrue(fetcher.fetchFeed(FeedConfig("${feedHost.base}/moved"), "tech").isEmpty())
                    assertEquals(0, lan.hits.get())
                    assertTrue(recorder.drain().any { it.lastError.orEmpty().startsWith("rejected redirect") })
                } finally {
                    fetcher.shutdown()
                }
            }
        }
    }

    @Test
    fun `feed redirects are still followed within the trusted host,`() {
        Server { path -> if (path == "/old") Triple(302, "/rss", "") else Triple(200, null, rss) }.use { feedHost ->
            val fetcher = RssFetcher(urlGuard = UrlGuard(listOf(feedHost.base)), maxAttempts = 1, retryDelayMs = 0)
            try {
                assertEquals(1, fetcher.fetchFeed(FeedConfig("${feedHost.base}/old"), "tech").size)
                assertEquals(2, feedHost.hits.get())
            } finally {
                fetcher.shutdown()
            }
        }
    }
}
