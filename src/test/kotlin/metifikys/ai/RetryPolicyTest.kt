package metifikys.ai

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
    fun `InterruptedException during backoff sleep aborts the loop`() {
        var calls = 0
        Thread.currentThread().interrupt()   // makes the backoff Thread.sleep throw immediately
        assertFailsWith<InterruptedException> {
            RetryPolicy.retry(maxRetries = 5, delayMillis = { _, _ -> 10 }) {
                calls++; throw RuntimeException("boom")
            }
        }
        assertEquals(1, calls)                // interrupted during the first backoff — no 2nd attempt
        assertTrue(Thread.interrupted())      // flag restored; clear it so it doesn't leak
    }
}
