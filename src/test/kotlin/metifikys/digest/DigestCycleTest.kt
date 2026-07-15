package metifikys.digest

import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.OpenAIConfig
import metifikys.config.ProcessingConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.db.NewsDatabase
import metifikys.fetch.ArticleFetcher
import metifikys.fetch.ArticleSummarizer
import metifikys.fetch.RssFetcher
import metifikys.model.Article
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the per-category digest pipeline: real SQLite DB on a temp file,
 * real RssFetcher against local HTTP fixture feeds, LLM never touched (a recording
 * CategoryProcessor stands in for Step 1/2).
 */
class DigestCycleTest {

    private lateinit var dbFile: File
    private lateinit var db: NewsDatabase

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("digest-cycle-test", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun tearDown() {
        dbFile.delete()
    }

    // ── wiring helpers ────────────────────────────────────────────────────────

    private fun cfg(categories: Map<String, CategoryConfig>) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = dbFile.absolutePath),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = categories,
        processing = ProcessingConfig(minArticles = 1)
    )

    private fun cat(vararg urls: String) = CategoryConfig(
        emoji = "📰",
        feeds = urls.map { FeedConfig(it) },
        channelId = "@c"
    )

    private fun cycle(
        config: AppConfig,
        fetcher: RssFetcher,
        processor: CategoryProcessor,
        errorLog: CycleErrorLog = CycleErrorLog()
    ) = DigestCycle(
        config = config,
        fetcher = fetcher,
        articleFetcher = ArticleFetcher(RssFetcher(enforceUrlValidation = false)),
        articleSummarizer = ArticleSummarizer(config, config.categories, mockk()),
        db = db,
        llmClientsFactory = mockk(),
        categoryProcessor = processor,
        deliverer = mockk(relaxed = true),
        promptLoader = mockk(),
        errorLog = errorLog
    )

    /** Records processSingle invocations instead of running the LLM pipeline. */
    private class RecordingCategoryProcessor(
        config: AppConfig,
        db: NewsDatabase,
        private val throwFor: String? = null
    ) : CategoryProcessor(
        config = config,
        db = db,
        llmClientsFactory = mockk(),
        promptLoader = mockk(),
        deliverer = mockk(relaxed = true)
    ) {
        val calls: MutableList<Pair<String, List<Article>>> = Collections.synchronizedList(mutableListOf())
        val callTimes = ConcurrentHashMap<String, Long>()

        override fun processSingle(name: String, articles: List<Article>) {
            callTimes.putIfAbsent(name, System.nanoTime())
            calls.add(name to articles)
            if (name == throwFor) throw RuntimeException("boom in $name")
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `slow failing category does not delay the healthy category`() {
        val badServer = server503()
        val goodServer = serverRss(rssWithItems("https://example.com/b1"))
        // Slow category: 503 forever, retry after 3s → its fetch finishes no sooner than ~3s.
        val fetcher = RssFetcher(
            enforceUrlValidation = false, maxAttempts = 2, retryDelayMs = 3_000,
            fetchDeadlineMs = 10_000, maxConcurrentFetches = 3
        )
        try {
            val config = cfg(
                mapOf(
                    "slow" to cat(urlOf(badServer)),
                    "fast" to cat(urlOf(goodServer))
                )
            )
            val processor = RecordingCategoryProcessor(config, db)
            val start = System.nanoTime()
            cycle(config, fetcher, processor).runCycle()

            val fastAt = processor.callTimes["fast"]
            assertTrue(fastAt != null, "fast category should have been processed")
            val fastDelayMs = (fastAt!! - start) / 1_000_000
            assertTrue(
                fastDelayMs < 2_500,
                "fast category should be processed before the slow one's 3s retry completes (took ${fastDelayMs}ms)"
            )
            assertTrue(processor.calls.none { it.first == "slow" }, "slow category fetched nothing — no digest expected")
        } finally {
            badServer.stop(0)
            goodServer.stop(0)
            fetcher.shutdown()
        }
    }

    @Test
    fun `failure in one category pipeline does not abort the others`() {
        val serverA = serverRss(rssWithItems("https://example.com/a1"))
        val serverB = serverRss(rssWithItems("https://example.com/b1"))
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val config = cfg(
                mapOf(
                    "boom" to cat(urlOf(serverA)),
                    "ok" to cat(urlOf(serverB))
                )
            )
            val errorLog = CycleErrorLog()
            val processor = RecordingCategoryProcessor(config, db, throwFor = "boom")
            cycle(config, fetcher, processor, errorLog).runCycle() // must not throw

            assertTrue(processor.calls.any { it.first == "ok" }, "ok category should still be processed")
            assertTrue(
                errorLog.list().any { it.category == "boom" },
                "boom category failure should be recorded in the error log"
            )
        } finally {
            serverA.stop(0)
            serverB.stop(0)
            fetcher.shutdown()
        }
    }

    @Test
    fun `category with no new fetched articles still picks up ready articles from previous cycles`() {
        val emptyFeed = serverRss(rssWithItems()) // valid RSS, zero items
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val config = cfg(mapOf("tech" to cat(urlOf(emptyFeed))))
            db.insertArticles(
                listOf(
                    Article(
                        category = "tech",
                        title = "left over from last cycle",
                        link = "https://example.com/old",
                        description = "d",
                        pubDate = LocalDateTime.now()
                    )
                )
            )
            val processor = RecordingCategoryProcessor(config, db)
            cycle(config, fetcher, processor).runCycle()

            val techCall = processor.calls.singleOrNull { it.first == "tech" }
            assertTrue(techCall != null, "tech should be processed from the leftover article")
            assertEquals(listOf("https://example.com/old"), techCall!!.second.map { it.link })
        } finally {
            emptyFeed.stop(0)
            fetcher.shutdown()
        }
    }

    @Test
    fun `link dedup prevents re-inserting the same articles across cycles`() {
        val server = serverRss(rssWithItems("https://example.com/1"))
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val config = cfg(mapOf("tech" to cat(urlOf(server))))
            val processor = RecordingCategoryProcessor(config, db)
            val digestCycle = cycle(config, fetcher, processor)
            digestCycle.runCycle()
            digestCycle.runCycle()

            // The recording processor never marks articles processed, so the single row stays
            // ready — but it must remain a SINGLE row after two cycles over the same feed.
            assertEquals(1, db.fetchReadyForDigest("tech").size)
            assertEquals(2, processor.calls.count { it.first == "tech" })
        } finally {
            server.stop(0)
            fetcher.shutdown()
        }
    }

    // ── local feed fixtures (same pattern as RssFetcherTest) ─────────────────

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

    private fun serverRss(body: ByteArray): HttpServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/rss") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    private fun server503(): HttpServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/rss") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        return server
    }

    private fun urlOf(server: HttpServer) = "http://localhost:${server.address.port}/rss"
}
