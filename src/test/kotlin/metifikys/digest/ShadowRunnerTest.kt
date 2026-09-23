package metifikys.digest

import metifikys.config.ShadowConfig
import metifikys.db.ShadowRunRow
import metifikys.db.ShadowRunStore
import metifikys.model.Article
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShadowRunnerTest {

    private class FakeStore : ShadowRunStore {
        val rows = CopyOnWriteArrayList<ShadowRunRow>()
        override fun insertShadowRun(row: ShadowRunRow) { rows += row }
        override fun fetchShadowRuns(since: LocalDateTime): List<ShadowRunRow> = rows.toList()
        override fun deleteOldShadowRuns(days: Long) {}
    }

    private val store = FakeStore()
    private val runner = ShadowRunner(store)
    private val side = ShadowRunner.Side("codexcli", "gpt-a", "p1")
    private val bSide = ShadowRunner.Side("openrouter", "model-b", "p2")
    private val urlsOf: (String) -> ShadowRunner.Outcome = { raw -> ShadowRunner.Outcome(raw, raw.split(",")) }

    @AfterEach
    fun tearDown() = runner.shutdown()

    private fun awaitRows(n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (store.rows.size < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(n, store.rows.size, "rows written")
    }

    @Test
    fun `sampleRate 0 means no B call and no handle`() {
        val calls = AtomicInteger()
        val handle = runner.start("tech", ShadowRunRow.STAGE_EXTRACT, ShadowConfig(sampleRate = 0.0), "h", side, bSide,
            callB = { calls.incrementAndGet(); "x" }, judgeA = urlsOf, judgeB = urlsOf)
        assertNull(handle)
        Thread.sleep(50)
        assertEquals(0, calls.get())
    }

    @Test
    fun `sampleRate below the draw skips the run`() {
        val sampled = ShadowRunner(store, random = { 0.7 })
        try {
            assertNull(sampled.start("tech", "EXTRACT", ShadowConfig(sampleRate = 0.5), "h", side, bSide, { "x" }, urlsOf, urlsOf))
            assertNotNull(sampled.start("tech", "EXTRACT", ShadowConfig(sampleRate = 0.8), "h", side, bSide, { "x" }, urlsOf, urlsOf))
        } finally {
            sampled.shutdown()
        }
    }

    @Test
    fun `row is written once both sides are done, with both outputs`() {
        val handle = assertNotNull(
            runner.start("tech", ShadowRunRow.STAGE_EXTRACT, ShadowConfig(), "in", side, bSide,
                callB = { "u1,u3" }, judgeA = urlsOf, judgeB = urlsOf)
        )
        Thread.sleep(100)
        assertTrue(store.rows.isEmpty(), "nothing before A finishes")
        handle.finish("u1,u2")
        awaitRows(1)
        val row = store.rows.single()
        assertEquals(listOf("u1", "u2"), row.aUrls)
        assertEquals(listOf("u1", "u3"), row.bUrls)
        assertEquals("gpt-a", row.aModel)
        assertEquals("model-b", row.bModel)
        assertNull(row.bError)
        assertNotNull(row.bDurationMs)
    }

    @Test
    fun `a failing B is stored as its error and A's side is kept`() {
        val handle = assertNotNull(
            runner.start("tech", "RENDER", ShadowConfig(), "in", side, bSide,
                callB = { throw IllegalStateException("model gone") }, judgeA = urlsOf, judgeB = urlsOf)
        )
        handle.finish("u1")
        awaitRows(1)
        assertEquals("model gone", store.rows.single().bError)
        assertEquals(listOf("u1"), store.rows.single().aUrls)
    }

    @Test
    fun `a full queue drops the run instead of blocking the caller`() {
        val release = CountDownLatch(1)
        val blocking: () -> String = { release.await(5, TimeUnit.SECONDS); "x" }
        val cfg = ShadowConfig(queueSize = 1)
        val first = runner.start("tech", "EXTRACT", cfg, "h", side, bSide, blocking, urlsOf, urlsOf)
        Thread.sleep(50) // the first one is running now
        val second = runner.start("tech", "EXTRACT", cfg, "h", side, bSide, blocking, urlsOf, urlsOf)
        val startedAt = System.nanoTime()
        val third = runner.start("tech", "EXTRACT", cfg, "h", side, bSide, blocking, urlsOf, urlsOf)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1_000, "start must not wait")
        assertNotNull(first)
        assertNotNull(second)
        assertNull(third, "queue of 1 with one in flight: the third run is dropped")
        release.countDown()
    }

    @Test
    fun `render outcome keeps only whitelisted topics and their links`() {
        val articles = listOf(Article("tech", "T", "https://a.example/1", "d"))
        val out = ShadowRunner.renderOutcome(
            "tech",
            "• **One**\n[Джерело](https://a.example/1)\n• **Two**\n[x](https://evil.example/2)",
            articles
        )
        assertEquals(listOf("https://a.example/1"), out.urls)
        assertTrue(out.output!!.contains("foreign_url"))
        assertNull(out.error)
    }

    @Test
    fun `status line counts runs, B failures and the mean Jaccard`() {
        fun row(a: List<String>, b: List<String>, err: String? = null) = ShadowRunRow(
            "tech", "EXTRACT", LocalDateTime.now(), "h", "p", "m", "x", "p", "m", "y",
            "{}", if (err == null) "{}" else null, err, 10, a, b
        )
        val line = ShadowRunner.statusLine(listOf(row(listOf("1", "2"), listOf("1", "2")), row(listOf("1"), listOf("2")), row(listOf("1"), emptyList(), "boom")))
        assertEquals("🌓 shadow 24h: 3 runs · B fail 1 · Jaccard 0.50", line)
        assertNull(ShadowRunner.statusLine(emptyList()))
    }
}
