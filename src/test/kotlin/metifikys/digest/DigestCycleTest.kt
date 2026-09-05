package metifikys.digest

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import metifikys.telegram.StatusPoster
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.DatabaseConfig
import metifikys.config.FeedConfig
import metifikys.config.FeedbackConfig
import metifikys.config.OpenAIConfig
import metifikys.config.ProcessingConfig
import metifikys.config.SchedulerConfig
import metifikys.config.TelegramConfig
import metifikys.db.CoveredEventRow
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

    private fun cfg(
        categories: Map<String, CategoryConfig>,
        feedback: FeedbackConfig = FeedbackConfig()
    ) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = dbFile.absolutePath),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = categories,
        processing = ProcessingConfig(minArticles = 1),
        feedback = feedback
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
        errorLog: CycleErrorLog = CycleErrorLog(),
        articleFetcher: ArticleFetcher = ArticleFetcher(RssFetcher(enforceUrlValidation = false)),
        statusPoster: StatusPoster? = null
    ) = DigestCycle(
        statusPoster = statusPoster,
        config = config,
        fetcher = fetcher,
        articleFetcher = articleFetcher,
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
    fun `mid-pipeline interrupt keeps the insert but skips the digest`() {
        val server = serverRss(rssWithItems("https://example.com/i1"))
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val config = cfg(mapOf("tech" to cat(urlOf(server))))
            val processor = RecordingCategoryProcessor(config, db)
            // Simulate the cycle barrier interrupting the worker during enrichment: the
            // best-effort stages swallow the interrupt and only restore the flag.
            val interruptingFetcher = mockk<ArticleFetcher>()
            every { interruptingFetcher.enrich(any(), any()) } answers {
                Thread.currentThread().interrupt()
                firstArg()
            }
            cycle(config, fetcher, processor, articleFetcher = interruptingFetcher).runCycle()

            assertTrue(Thread.interrupted(), "interrupt flag must survive the pipeline")  // also clears it
            assertTrue(processor.calls.isEmpty(), "digest must not run on a cancelled worker")
            assertEquals(1, db.fetchReadyForDigest("tech").size, "fetched articles must still be inserted")
        } finally {
            server.stop(0)
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
    fun `runCategory runs only the named category's pipeline`() {
        val serverA = serverRss(rssWithItems("https://example.com/a1"))
        val serverB = serverRss(rssWithItems("https://example.com/b1"))
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val config = cfg(mapOf("a" to cat(urlOf(serverA)), "b" to cat(urlOf(serverB))))
            val processor = RecordingCategoryProcessor(config, db)

            cycle(config, fetcher, processor).runCategory("a")

            assertEquals(listOf("a"), processor.calls.map { it.first })
            assertEquals(listOf("https://example.com/a1"), processor.calls.single().second.map { it.link })
            // b's feed was never fetched: nothing of it reached the DB.
            assertTrue(db.findExistingLinks(listOf("https://example.com/b1")).isEmpty())
        } finally {
            serverA.stop(0)
            serverB.stop(0)
            fetcher.shutdown()
        }
    }

    @Test
    fun `runCategory does not throw for a failing pipeline and records the error`() {
        val serverA = serverRss(rssWithItems("https://example.com/a1"))
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val config = cfg(mapOf("boom" to cat(urlOf(serverA))))
            val errorLog = CycleErrorLog()
            val processor = RecordingCategoryProcessor(config, db, throwFor = "boom")

            cycle(config, fetcher, processor, errorLog).runCategory("boom") // must not throw

            assertTrue(errorLog.list().any { it.category == "boom" }, "pipeline failure should be recorded")
        } finally {
            serverA.stop(0)
            fetcher.shutdown()
        }
    }

    @Test
    fun `runIngest inserts fetched articles but never digests`() {
        val serverA = serverRss(rssWithItems("https://example.com/a1", "https://example.com/a2"))
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val config = cfg(mapOf("a" to cat(urlOf(serverA))))
            val processor = RecordingCategoryProcessor(config, db)

            cycle(config, fetcher, processor).runIngest("a")

            assertEquals(
                setOf("https://example.com/a1", "https://example.com/a2"),
                db.findExistingLinks(listOf("https://example.com/a1", "https://example.com/a2"))
            )
            assertTrue(processor.calls.isEmpty(), "ingest must not run Step 1/2")
        } finally {
            serverA.stop(0)
            fetcher.shutdown()
        }
    }

    @Test
    fun `runDigest processes ready articles without fetching anything`() {
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            // A feed URL nothing listens on: a fetch would fail loudly, a digest never tries.
            val config = cfg(mapOf("a" to cat("http://localhost:1/never-fetched")))
            db.insertArticles(
                listOf(
                    Article(
                        category = "a",
                        title = "ingested earlier",
                        link = "https://example.com/ready",
                        description = "d",
                        pubDate = LocalDateTime.now()
                    )
                )
            )
            val processor = RecordingCategoryProcessor(config, db)

            cycle(config, fetcher, processor).runDigest("a")

            assertEquals(listOf("https://example.com/ready"), processor.calls.single().second.map { it.link })
        } finally {
            fetcher.shutdown()
        }
    }

    @Test
    fun `runMaintenance posts a status snapshot without touching any category`() {
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            val config = cfg(mapOf("tech" to cat("http://localhost:1/never-fetched")))
            val processor = RecordingCategoryProcessor(config, db)
            val statusPoster = mockk<StatusPoster>(relaxed = true)

            cycle(config, fetcher, processor, statusPoster = statusPoster).runMaintenance()

            verify(exactly = 1) { statusPoster.post() }
            assertTrue(processor.calls.isEmpty(), "maintenance must not run a category pipeline")
        } finally {
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

    @Test
    fun `covered events survive retention cleanup as long as the affinity lookback needs them`() {
        // covered_events is the join partner of every affinity input, so pruning it at
        // summaryHistory.retentionDays (14) would silently truncate feedback.lookbackDays (90).
        val emptyFeed = serverRss(rssWithItems())
        val fetcher = RssFetcher(enforceUrlValidation = false, maxAttempts = 1, retryDelayMs = 0)
        try {
            fun seedOldEvent() = db.insertCoveredEvents(
                listOf(
                    CoveredEventRow(
                        category = "tech",
                        eventKey = "old-event",
                        subject = "s", franchise = "f", eventType = "e",
                        coreFact = "fact", importance = 5, url = "https://example.com/old",
                        coveredAt = LocalDateTime.now().minusDays(30)
                    )
                )
            )
            fun survivors() = db.fetchRecentEvents("tech", sinceDays = 365, limit = 100).size

            // Feedback off → the 14-day retention applies and the 30-day-old row goes.
            val off = cfg(mapOf("tech" to cat(urlOf(emptyFeed))))
            seedOldEvent()
            cycle(off, fetcher, RecordingCategoryProcessor(off, db)).runCycle()
            assertEquals(0, survivors(), "without feedback the default 14-day retention prunes it")

            // Feedback on with a 90-day lookback → the row must stay reachable.
            val on = cfg(
                mapOf("tech" to cat(urlOf(emptyFeed))),
                feedback = FeedbackConfig(enabled = true, lookbackDays = 90)
            )
            seedOldEvent()
            cycle(on, fetcher, RecordingCategoryProcessor(on, db)).runCycle()
            assertEquals(1, survivors(), "a 90-day lookback must outrank the 14-day retention")
        } finally {
            emptyFeed.stop(0)
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
