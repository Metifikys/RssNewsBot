package metifikys.fetch

import com.sun.net.httpserver.HttpServer
import metifikys.config.CategoryConfig
import metifikys.config.FeedConfig
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RssFetcherTest {

    // Validation OFF for tests that spin up a local HTTP server for fixture feeds.
    // Short retry delays to keep tests fast.
    private val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)

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

    // ── BUG-006: transient-error retries ───────────────────────────────────────

    @Test
    fun `isRetryableStatus covers 408 429 and all 5xx but not other 4xx or 2xx`() {
        assertTrue(RssFetcher.isRetryableStatus(408))
        assertTrue(RssFetcher.isRetryableStatus(429))
        assertTrue(RssFetcher.isRetryableStatus(500))
        assertTrue(RssFetcher.isRetryableStatus(502))
        assertTrue(RssFetcher.isRetryableStatus(503))
        assertTrue(RssFetcher.isRetryableStatus(599))
        assertFalse(RssFetcher.isRetryableStatus(200))
        assertFalse(RssFetcher.isRetryableStatus(400))
        assertFalse(RssFetcher.isRetryableStatus(404))
    }

    @Test
    fun `parseRetryAfterMs parses delta-seconds, clamps, and ignores non-numeric`() {
        assertEquals(5_000L, RssFetcher.parseRetryAfterMs("5"))
        assertEquals(0L, RssFetcher.parseRetryAfterMs("0"))
        assertEquals(300_000L, RssFetcher.parseRetryAfterMs("100000")) // clamped to MAX_RETRY_AFTER_MS
        assertEquals(null, RssFetcher.parseRetryAfterMs(null))
        assertEquals(null, RssFetcher.parseRetryAfterMs("  "))
        assertEquals(null, RssFetcher.parseRetryAfterMs("Wed, 21 Oct 2025 07:28:00 GMT")) // HTTP-date form
    }

    @Test
    fun `fetchFeed retries on 503 then parses the feed`() {
        val rss = rssWithItems("https://example.com/1")
        val retryFetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 3, retryDelayMs = 0)
        withFlakyFeed(rss, failStatus = 503, failTimes = 2) { url ->
            val result = retryFetcher.fetchFeed(FeedConfig(url), "tech")
            assertEquals(1, result.size)
            assertEquals("https://example.com/1", result[0].link)
        }
    }

    @Test
    fun `fetchFeed retries on 429 honoring Retry-After then parses the feed`() {
        val rss = rssWithItems("https://example.com/1")
        // Zeroed host throttle: this test exercises the per-feed Retry-After path, not the
        // host-level cooldown (which has its own tests below).
        val retryFetcher = RssFetcher(
            enforceUrlValidation = false, maxAttempts = 3, retryDelayMs = 0,
            hostThrottle = HostThrottle(initialSpacingMs = 0, initialCooldownMs = 0)
        )
        // Retry-After: 0 keeps the test instant while still exercising the header-parse path.
        withFlakyFeed(rss, failStatus = 429, failTimes = 1, retryAfter = "0") { url ->
            val result = retryFetcher.fetchFeed(FeedConfig(url), "tech")
            assertEquals(1, result.size)
        }
    }

    @Test
    fun `fetchFeed gives up after maxAttempts of 503 and returns emptyList`() {
        val rss = rssWithItems("https://example.com/1")
        val retryFetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 2, retryDelayMs = 0)
        withFlakyFeed(rss, failStatus = 503, failTimes = 99) { url ->
            val result = retryFetcher.fetchFeed(FeedConfig(url), "tech")
            assertTrue(result.isEmpty())
        }
    }

    // ── queue model: non-blocking failures, deadline, concurrency cap ────────

    @Test
    fun `failing feed does not block other feeds in the queue`() {
        val rss = rssWithItems("https://example.com/1")
        val queueFetcher = RssFetcher(
            enforceUrlValidation = false, maxAttempts = 3, retryDelayMs = 300,
            maxConcurrentFetches = 1
        )
        val badAttemptTimes = mutableListOf<Long>()
        val goodHitTime = AtomicLong(0)

        val badServer = HttpServer.create(InetSocketAddress(0), 0)
        badServer.createContext("/rss") { exchange ->
            synchronized(badAttemptTimes) { badAttemptTimes.add(System.nanoTime()) }
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        badServer.start()
        val goodServer = HttpServer.create(InetSocketAddress(0), 0)
        goodServer.createContext("/rss") { exchange ->
            goodHitTime.compareAndSet(0, System.nanoTime())
            exchange.sendResponseHeaders(200, rss.size.toLong())
            exchange.responseBody.use { it.write(rss) }
        }
        goodServer.start()
        try {
            val categories = mapOf(
                "tech" to CategoryConfig(
                    emoji = "💻",
                    feeds = listOf(
                        FeedConfig("http://localhost:${badServer.address.port}/rss"),
                        FeedConfig("http://localhost:${goodServer.address.port}/rss")
                    ),
                    channelId = "@tech"
                )
            )
            val result = queueFetcher.fetchAll(categories)
            // Bad feed keeps failing but the good one still comes through.
            assertEquals(1, result.size)
            // The good feed must be served BEFORE the bad feed's requeued second attempt —
            // i.e. the failure freed the (single) pool slot instead of blocking it.
            val secondBadAttempt = synchronized(badAttemptTimes) { badAttemptTimes.getOrNull(1) }
            assertTrue(
                secondBadAttempt == null || goodHitTime.get() < secondBadAttempt,
                "good feed should be fetched before the bad feed's second attempt"
            )
        } finally {
            badServer.stop(0)
            goodServer.stop(0)
            queueFetcher.shutdown()
        }
    }

    @Test
    fun `fetch deadline defers still-failing feeds instead of retrying past it`() {
        val rss = rssWithItems("https://example.com/1")
        val deadlineFetcher = RssFetcher(
            enforceUrlValidation = false, maxAttempts = 99, retryDelayMs = 500, fetchDeadlineMs = 300
        )
        withFlakyFeed(rss, failStatus = 503, failTimes = 99) { url ->
            val start = System.nanoTime()
            val result = deadlineFetcher.fetchFeed(FeedConfig(url), "tech")
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertTrue(result.isEmpty())
            assertTrue(elapsedMs < 5_000, "deadline should cap the fetch stage, took ${elapsedMs}ms")
        }
        deadlineFetcher.shutdown()
    }

    @Test
    fun `feed is attempted exactly maxAttempts times`() {
        val calls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/rss") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        val cappedFetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 2, retryDelayMs = 0)
        try {
            val result = cappedFetcher.fetchFeed(FeedConfig("http://localhost:${server.address.port}/rss"), "tech")
            assertTrue(result.isEmpty())
            assertEquals(2, calls.get())
        } finally {
            server.stop(0)
            cappedFetcher.shutdown()
        }
    }

    @Test
    fun `maxConcurrentFetches bounds simultaneous requests`() {
        val rss = rssWithItems("https://example.com/1")
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        // The default HttpServer executor is single-threaded and would serialize requests,
        // masking the very concurrency this test measures.
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/rss") { exchange ->
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { max -> maxOf(max, now) }
            Thread.sleep(200)
            inFlight.decrementAndGet()
            exchange.sendResponseHeaders(200, rss.size.toLong())
            exchange.responseBody.use { it.write(rss) }
        }
        server.start()
        val boundedFetcher = RssFetcher(
            enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0, maxConcurrentFetches = 2
        )
        try {
            val url = "http://localhost:${server.address.port}/rss"
            val categories = mapOf(
                "tech" to CategoryConfig(
                    emoji = "💻",
                    feeds = List(6) { FeedConfig(url) },
                    channelId = "@tech"
                )
            )
            val result = boundedFetcher.fetchAll(categories)
            assertEquals(6, result.size)
            assertTrue(maxInFlight.get() <= 2, "observed ${maxInFlight.get()} concurrent requests, expected <= 2")
        } finally {
            server.stop(0)
            boundedFetcher.shutdown()
        }
    }

    // ── per-host 429 throttling ──────────────────────────────────────────────

    @Test
    fun `429 on one feed cools down the whole host so sibling feeds skip the network`() {
        val rss = rssWithItems("https://example.com/1")
        val rateLimitedCalls = AtomicInteger(0)
        val healthyCalls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/limited") { exchange ->
            rateLimitedCalls.incrementAndGet()
            exchange.sendResponseHeaders(429, -1)
            exchange.close()
        }
        server.createContext("/healthy") { exchange ->
            healthyCalls.incrementAndGet()
            exchange.sendResponseHeaders(200, rss.size.toLong())
            exchange.responseBody.use { it.write(rss) }
        }
        server.start()
        // Cooldown far beyond the stage deadline: after /limited's 429 the host is closed,
        // so /healthy must be deferred to the next cycle WITHOUT ever hitting the server.
        val throttledFetcher = RssFetcher(
            enforceUrlValidation = false, maxAttempts = 3, retryDelayMs = 0,
            maxConcurrentFetches = 1, fetchDeadlineMs = 2_000,
            hostThrottle = HostThrottle(initialSpacingMs = 0, initialCooldownMs = 600_000)
        )
        try {
            val base = "http://localhost:${server.address.port}"
            val categories = mapOf(
                "tech" to CategoryConfig(
                    emoji = "💻",
                    feeds = listOf(FeedConfig("$base/limited"), FeedConfig("$base/healthy")),
                    channelId = "@tech"
                )
            )
            val result = throttledFetcher.fetchAll(categories)
            assertTrue(result.isEmpty())
            assertEquals(1, rateLimitedCalls.get(), "one 429 must be enough — no per-feed retries against a cooling host")
            assertEquals(0, healthyCalls.get(), "sibling feed on the same host must not hit the network during cooldown")
        } finally {
            server.stop(0)
            throttledFetcher.shutdown()
        }
    }

    @Test
    fun `feeds on a rate-limited host recover after the host cooldown expires`() {
        val rss = rssWithItems("https://example.com/1")
        val limitedCalls = AtomicInteger(0)
        val healthyCalls = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/limited") { exchange ->
            if (limitedCalls.getAndIncrement() == 0) {
                exchange.sendResponseHeaders(429, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, rss.size.toLong())
                exchange.responseBody.use { it.write(rss) }
            }
        }
        server.createContext("/healthy") { exchange ->
            healthyCalls.incrementAndGet()
            exchange.sendResponseHeaders(200, rss.size.toLong())
            exchange.responseBody.use { it.write(rss) }
        }
        server.start()
        val recoveringFetcher = RssFetcher(
            enforceUrlValidation = false, maxAttempts = 3, retryDelayMs = 0,
            maxConcurrentFetches = 1, fetchDeadlineMs = 10_000,
            hostThrottle = HostThrottle(initialSpacingMs = 0, initialCooldownMs = 300)
        )
        try {
            val base = "http://localhost:${server.address.port}"
            val categories = mapOf(
                "tech" to CategoryConfig(
                    emoji = "💻",
                    feeds = listOf(FeedConfig("$base/limited"), FeedConfig("$base/healthy")),
                    channelId = "@tech"
                )
            )
            val result = recoveringFetcher.fetchAll(categories)
            assertEquals(2, result.size, "both feeds should succeed once the cooldown expires")
            assertEquals(2, limitedCalls.get())
            assertEquals(1, healthyCalls.get(), "healthy feed must wait out the cooldown, not burn attempts against it")
        } finally {
            server.stop(0)
            recoveringFetcher.shutdown()
        }
    }

    @Test
    fun `nextRetryDelayMs prefers Retry-After, clamps, and defers when past deadline`() {
        assertEquals(5_000L, RssFetcher.nextRetryDelayMs(retryAfterMs = 5_000L, fallbackDelayMs = 15_000L, remainingMs = 100_000L))
        assertEquals(15_000L, RssFetcher.nextRetryDelayMs(retryAfterMs = null, fallbackDelayMs = 15_000L, remainingMs = 100_000L))
        assertEquals(300_000L, RssFetcher.nextRetryDelayMs(retryAfterMs = 999_999L, fallbackDelayMs = 15_000L, remainingMs = 1_000_000L))
        assertEquals(null, RssFetcher.nextRetryDelayMs(retryAfterMs = null, fallbackDelayMs = 15_000L, remainingMs = 10_000L))
    }

    @Test
    fun `interrupting the waiting thread returns promptly and restores the flag`() {
        val rss = rssWithItems("https://example.com/1")
        val slowFetcher = RssFetcher(
            enforceUrlValidation = false, maxAttempts = 10, retryDelayMs = 60_000, fetchDeadlineMs = 600_000
        )
        withFlakyFeed(rss, failStatus = 503, failTimes = 99) { url ->
            val flagRestored = AtomicBoolean(false)
            val returned = CountDownLatch(1)
            val t = Thread {
                slowFetcher.fetchFeed(FeedConfig(url), "tech")
                flagRestored.set(Thread.currentThread().isInterrupted)
                returned.countDown()
            }
            t.start()
            Thread.sleep(300) // let the first attempt fail and the 60s retry get queued
            t.interrupt()
            assertTrue(returned.await(5, TimeUnit.SECONDS), "fetchFeed should return promptly after interrupt")
            assertTrue(flagRestored.get(), "interrupt flag should be restored")
        }
        slowFetcher.shutdown()
    }

    @Test
    fun `a reddit link post is ingested as the article it points at`() {
        val permalink = "https://www.reddit.com/r/technology/comments/1vovlwd/chinese_magnetic_sensor"
        val rss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>r/technology</title>
                <link>https://www.reddit.com/r/technology</link>
                <item>
                  <title>Chinese magnetic sensor breakthrough</title>
                  <link>$permalink</link>
                  <description><![CDATA[
                    <table><tr><td><a href="$permalink/"><img src="https://external-preview.redd.it/57pf.jpeg" /></a></td>
                    <td> submitted by <a href="https://www.reddit.com/user/malcolm58">/u/malcolm58</a> <br/>
                    <span><a href="https://www.scmp.com/news/china/science/article/3363975/sensor">[link]</a></span>
                    <span><a href="$permalink/">[comments]</a></span></td></tr></table>
                  ]]></description>
                </item>
              </channel>
            </rss>
        """.trimIndent().toByteArray()

        withLocalFeed(rss) { url ->
            val article = fetcher.fetchFeed(FeedConfig(url, fetchFullContent = false), "tech").single()

            assertEquals("https://www.scmp.com/news/china/science/article/3363975/sensor", article.link)
            assertEquals("", article.description, "the boilerplate card must not survive as the description")
            assertTrue(
                article.fetchFullContent,
                "the outbound page is the only source of text, so enrichment must be forced on"
            )
            // Reddit's preview thumbnail is the one useful part of the card — read off the
            // ORIGINAL description, so blanking it must not cost us the image.
            assertEquals("https://external-preview.redd.it/57pf.jpeg", article.imageUrl)
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

    @Test
    fun `second fetch replays ETag and treats 304 as no new articles`() {
        val rss = rssWithItems("https://example.com/1")
        val seenIfNoneMatch = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/rss") { exchange ->
            val ifNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            synchronized(seenIfNoneMatch) { seenIfNoneMatch.add(ifNoneMatch) }
            if (ifNoneMatch == "\"v1\"") {
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
            } else {
                exchange.responseHeaders.add("ETag", "\"v1\"")
                exchange.sendResponseHeaders(200, rss.size.toLong())
                exchange.responseBody.use { it.write(rss) }
            }
        }
        server.start()
        val cachingFetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val feed = FeedConfig("http://localhost:${server.address.port}/rss")

            val first = cachingFetcher.fetchFeed(feed, "tech")
            assertEquals(1, first.size, "cold fetch must parse the feed")

            val second = cachingFetcher.fetchFeed(feed, "tech")
            assertTrue(second.isEmpty(), "304 must yield no articles — they are already in the DB")

            assertEquals(listOf(null, "\"v1\""), synchronized(seenIfNoneMatch) { seenIfNoneMatch.toList() })
        } finally {
            server.stop(0)
            cachingFetcher.shutdown()
        }
    }

    @Test
    fun `a response without validators clears the cached pair`() {
        val rss = rssWithItems("https://example.com/1")
        val sentETag = AtomicBoolean(true)
        val seenIfNoneMatch = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/rss") { exchange ->
            synchronized(seenIfNoneMatch) { seenIfNoneMatch.add(exchange.requestHeaders.getFirst("If-None-Match")) }
            // First response carries an ETag, the second deliberately drops it.
            if (sentETag.getAndSet(false)) exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.sendResponseHeaders(200, rss.size.toLong())
            exchange.responseBody.use { it.write(rss) }
        }
        server.start()
        val cachingFetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val feed = FeedConfig("http://localhost:${server.address.port}/rss")
            cachingFetcher.fetchFeed(feed, "tech")
            cachingFetcher.fetchFeed(feed, "tech")   // sends If-None-Match, gets 200 with no ETag
            cachingFetcher.fetchFeed(feed, "tech")   // must NOT replay the stale validator

            assertEquals(
                listOf(null, "\"v1\"", null),
                synchronized(seenIfNoneMatch) { seenIfNoneMatch.toList() }
            )
        } finally {
            server.stop(0)
            cachingFetcher.shutdown()
        }
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

    /**
     * Serves [failStatus] (optionally with a `Retry-After` header) for the first [failTimes]
     * requests, then serves [body] with 200. Lets a test assert the fetcher retries transient
     * failures instead of giving up on the first one.
     */
    private fun withFlakyFeed(
        body: ByteArray,
        failStatus: Int,
        failTimes: Int,
        retryAfter: String? = null,
        block: (url: String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val port = server.address.port
        val calls = AtomicInteger(0)
        server.createContext("/rss") { exchange ->
            if (calls.getAndIncrement() < failTimes) {
                if (retryAfter != null) exchange.responseHeaders.add("Retry-After", retryAfter)
                exchange.sendResponseHeaders(failStatus, -1)
                exchange.close()
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        try {
            block("http://localhost:$port/rss")
        } finally {
            server.stop(0)
        }
    }
}
