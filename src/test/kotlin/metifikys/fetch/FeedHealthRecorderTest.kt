package metifikys.fetch

import com.sun.net.httpserver.HttpServer
import metifikys.config.FeedConfig
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedHealthRecorderTest {

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>F</title><link>https://e.com</link>
        <item><title>A</title><link>https://e.com/1</link><description>d</description></item>
        <item><title>B</title><link>https://e.com/2</link><description>d</description></item>
        </channel></rss>
    """.trimIndent().toByteArray()

    /** Serves [statuses] in order (200 = the feed with an ETag; anything else bodiless), then 200s. */
    private fun withServer(vararg statuses: Int, body: ByteArray = rss, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val calls = AtomicInteger(0)
        server.createContext("/rss") { ex ->
            val status = statuses.getOrElse(calls.getAndIncrement()) { 200 }
            if (status == 200) {
                ex.responseHeaders.add("ETag", "\"v1\"")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            } else {
                ex.sendResponseHeaders(status, -1)
                ex.close()
            }
        }
        server.start()
        try {
            block("http://localhost:${server.address.port}/rss")
        } finally {
            server.stop(0)
        }
    }

    private fun fetcher(recorder: FeedHealthRecorder, attempts: Int = 1) =
        RssFetcher(enforceUrlValidation = false, maxAttempts = attempts, retryDelayMs = 0, healthRecorder = recorder)

    @Test
    fun `a 500 after the retries is one failed pass and grows the streak`() {
        val rec = FeedHealthRecorder()
        withServer(500, 500, 500, 500) { url ->
            val f = fetcher(rec, attempts = 2)
            f.fetchFeed(FeedConfig(url), "tech")
            f.fetchFeed(FeedConfig(url), "tech")
            f.shutdown()
        }
        val d = rec.drain().single()
        assertEquals(2, d.passes)
        assertEquals(2, d.failures)
        assertEquals(2, d.failuresSinceSuccess)
        assertNull(d.lastSuccessAt)
        assertEquals("Got HTTP 500", d.lastError)
        assertTrue(rec.drain().isEmpty(), "drain resets")
    }

    @Test
    fun `a retry that succeeds is a success`() {
        val rec = FeedHealthRecorder()
        withServer(503, 200) { url ->
            val f = fetcher(rec, attempts = 2)
            assertEquals(2, f.fetchFeed(FeedConfig(url), "tech").size)
            f.shutdown()
        }
        val d = rec.drain().single()
        assertEquals(1, d.passes)
        assertEquals(0, d.failures)
        assertEquals(2, d.lastEntries)
        assertNotNull(d.lastSuccessAt)
    }

    @Test
    fun `304 counts as a healthy pass and resets the streak`() {
        val rec = FeedHealthRecorder()
        withServer(500, 200, 304) { url ->
            val f = fetcher(rec)
            f.fetchFeed(FeedConfig(url), "tech")
            f.fetchFeed(FeedConfig(url), "tech")
            assertTrue(f.fetchFeed(FeedConfig(url), "tech").isEmpty())
            f.shutdown()
        }
        val d = rec.drain().single()
        assertEquals(3, d.passes)
        assertEquals(1, d.failures)
        assertEquals(0, d.failuresSinceSuccess)
    }

    @Test
    fun `a parse failure is a failed pass`() {
        val rec = FeedHealthRecorder()
        withServer(200, body = "<html>not a feed".toByteArray()) { url ->
            val f = fetcher(rec)
            f.fetchFeed(FeedConfig(url), "tech")
            f.shutdown()
        }
        assertEquals("parse failure", rec.drain().single().lastError)
    }

    @Test
    fun `a URL the SSRF guard rejects is recorded as a failure`() {
        val rec = FeedHealthRecorder()
        val f = RssFetcher(enforceUrlValidation = true, healthRecorder = rec)
        f.fetchFeed(FeedConfig("http://10.0.0.1/rss"), "tech")
        f.shutdown()
        assertTrue(rec.drain().single().lastError!!.startsWith("rejected URL"))
    }
}
