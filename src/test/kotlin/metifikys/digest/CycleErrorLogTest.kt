package metifikys.digest

import metifikys.ai.BillingException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CycleErrorLogTest {

    @Test
    fun `recordBilling appends a BILLING entry with monotonic seq`() {
        val log = CycleErrorLog()
        log.recordBilling("tech", "[chunk 1/2]")
        log.recordBilling("news", "[Dedup]")

        val entries = log.list()
        assertEquals(2, entries.size)
        assertEquals(CycleErrorLog.Kind.BILLING, entries[0].kind)
        assertEquals("tech", entries[0].category)
        assertEquals("[chunk 1/2]", entries[0].context)
        assertTrue(entries[1].seq > entries[0].seq, "seq must be monotonic")
    }

    @Test
    fun `recordError buckets BillingException as BILLING after unwrapping CompletionException`() {
        val log = CycleErrorLog()
        val billing = BillingException("hard limit reached")
        val wrapped = CompletionException(ExecutionException(billing))

        log.recordError("tech", "[chunk 4/5]", wrapped)

        val entries = log.list()
        assertEquals(1, entries.size)
        assertEquals(CycleErrorLog.Kind.BILLING, entries[0].kind)
    }

    @Test
    fun `recordError buckets non-billing exceptions as OTHER and truncates message`() {
        val log = CycleErrorLog()
        val longMsg = "x".repeat(500)

        log.recordError("tech", "[submit]", RuntimeException(longMsg))

        val entry = log.list().single()
        assertEquals(CycleErrorLog.Kind.OTHER, entry.kind)
        assertTrue(entry.message.length <= 140, "message must be truncated to <=140 chars")
    }

    @Test
    fun `overflow drops oldest entries beyond cap`() {
        val log = CycleErrorLog(maxEntries = 5)
        repeat(20) { log.recordBilling("tech", "[$it]") }

        val entries = log.list()
        assertEquals(5, entries.size)
        // Oldest 15 dropped; remaining contexts should be the last 5.
        assertEquals("[15]", entries.first().context)
        assertEquals("[19]", entries.last().context)
    }

    @Test
    fun `clearUpTo drops only entries with seq lte token`() {
        val log = CycleErrorLog()
        log.recordBilling("a", "[1]")
        log.recordBilling("a", "[2]")
        val snapshot = log.list()
        val token = snapshot.last().seq

        // New entry arrives after the snapshot — should survive the clear.
        log.recordBilling("a", "[3]")

        log.clearUpTo(token)

        val remaining = log.list()
        assertEquals(1, remaining.size)
        assertEquals("[3]", remaining.single().context)
    }

    @Test
    fun `clearUpTo with negative token is a no-op`() {
        val log = CycleErrorLog()
        log.recordBilling("a", "[1]")
        log.clearUpTo(-1)
        assertEquals(1, log.list().size)
    }

    @Test
    fun `concurrent recording from multiple threads yields correct total and unique seqs`() {
        val log = CycleErrorLog(maxEntries = 10_000)
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        try {
            repeat(threads) { t ->
                pool.submit {
                    ready.countDown()
                    start.await()
                    repeat(perThread) { i -> log.recordBilling("t$t", "[$i]") }
                    done.countDown()
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS), "writers should finish")
        } finally {
            pool.shutdownNow()
        }

        val entries = log.list()
        assertEquals(threads * perThread, entries.size)
        val seqs = entries.map { it.seq }.toSet()
        assertEquals(entries.size, seqs.size, "all seqs must be unique")
    }
}
