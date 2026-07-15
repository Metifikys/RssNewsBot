package metifikys.ai

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetryPolicyTest {

    @Test
    fun `returns immediately on success`() {
        var calls = 0
        val r = RetryPolicy.retry(maxRetries = 3, delayMillis = { _, _ -> 0 }) { calls++; "ok" }
        assertEquals("ok", r)
        assertEquals(1, calls)
    }

    @Test
    fun `retries then succeeds`() {
        var calls = 0
        val r = RetryPolicy.retry(maxRetries = 3, delayMillis = { _, _ -> 0 }) {
            calls++
            if (calls < 3) throw RuntimeException("boom")
            "ok"
        }
        assertEquals("ok", r)
        assertEquals(3, calls)
    }

    @Test
    fun `gives up after maxRetries and rethrows the last exception`() {
        var calls = 0
        val e = assertFailsWith<IllegalStateException> {
            RetryPolicy.retry(maxRetries = 2, delayMillis = { _, _ -> 0 }) {
                calls++; throw IllegalStateException("nope$calls")
            }
        }
        assertEquals(3, calls)          // initial + 2 retries
        assertEquals("nope3", e.message)
    }

    @Test
    fun `fatal exception propagates without any retry`() {
        var calls = 0
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy.retry(
                maxRetries = 5,
                isFatal = { it is IllegalArgumentException },
                delayMillis = { _, _ -> 0 }
            ) { calls++; throw IllegalArgumentException("fatal") }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `InterruptedException from the block propagates and restores the interrupt flag`() {
        assertFailsWith<InterruptedException> {
            RetryPolicy.retry(maxRetries = 5, delayMillis = { _, _ -> 0 }) {
                throw InterruptedException("stop")
            }
        }
        // Thread.interrupted() returns the flag AND clears it — assert it was set by the helper.
        assertTrue(Thread.interrupted(), "interrupt flag must be restored so shutdownNow works")
    }

    @Test
    fun `interrupt translated into InterruptedIOException aborts instead of retrying`() {
        var calls = 0
        Thread.currentThread().interrupt()   // okhttp/okio throw InterruptedIOException with the flag still set
        assertFailsWith<InterruptedIOException> {
            RetryPolicy.retry(maxRetries = 5, delayMillis = { _, _ -> 0 }) {
                calls++
                throw InterruptedIOException("interrupted")
            }
        }
        assertEquals(1, calls)                // cancellation — no retry
        assertTrue(Thread.interrupted(), "flag must stay set; clear it so it doesn't leak")
    }

    @Test
    fun `plain socket timeout with a clear interrupt flag still retries`() {
        var calls = 0
        assertFailsWith<SocketTimeoutException> {
            RetryPolicy.retry(maxRetries = 2, delayMillis = { _, _ -> 0 }) {
                calls++
                throw SocketTimeoutException("timeout")
            }
        }
        assertEquals(3, calls)                // initial + 2 retries — genuine timeouts keep retrying
    }

    @Test
    fun `failure on an already-interrupted thread propagates as-is without retry`() {
        var calls = 0
        Thread.currentThread().interrupt()
        assertFailsWith<RuntimeException> {
            RetryPolicy.retry(maxRetries = 5, delayMillis = { _, _ -> 10 }) {
                calls++; throw RuntimeException("boom")
            }
        }
        assertEquals(1, calls)                // cancellation — no retry, no backoff sleep
        assertTrue(Thread.interrupted())      // flag preserved; clear it so it doesn't leak
    }

    @Test
    fun `interrupt arriving during the backoff sleep aborts the loop`() {
        var calls = 0
        val worker = Thread.currentThread()
        assertFailsWith<InterruptedException> {
            RetryPolicy.retry(maxRetries = 5, delayMillis = { _, _ -> 10_000 }) {
                calls++
                thread { Thread.sleep(250); worker.interrupt() }   // lands mid-backoff
                throw RuntimeException("boom")
            }
        }
        assertEquals(1, calls)                // interrupted during the first backoff — no 2nd attempt
        assertTrue(Thread.interrupted())      // flag restored; clear it so it doesn't leak
    }
}
