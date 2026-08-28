package metifikys.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostThrottleTest {

    private var nowNanos = 0L
    private fun advanceMs(ms: Long) { nowNanos += ms * 1_000_000 }

    private fun throttle(
        initialSpacingMs: Long = 6_000,
        maxSpacingMs: Long = 60_000,
        initialCooldownMs: Long = 30_000,
        maxCooldownMs: Long = 300_000
    ) = HostThrottle(initialSpacingMs, maxSpacingMs, initialCooldownMs, maxCooldownMs) { nowNanos }

    @Test
    fun `host that never rate-limited passes with zero delay`() {
        val t = throttle()
        repeat(5) { assertEquals(0, t.acquireDelayMs("example.com:443")) }
    }

    @Test
    fun `429 cools the host down until the cooldown expires`() {
        val t = throttle()
        assertEquals(30_000, t.onRateLimit("reddit.com:443", null))
        assertTrue(t.acquireDelayMs("reddit.com:443") > 29_000)
        advanceMs(30_001)
        assertEquals(0, t.acquireDelayMs("reddit.com:443"))
    }

    @Test
    fun `Retry-After wins when larger than the backoff`() {
        val t = throttle()
        assertEquals(120_000, t.onRateLimit("reddit.com:443", 120_000))
    }

    @Test
    fun `consecutive 429 events double the cooldown and a success resets the ladder`() {
        val t = throttle()
        assertEquals(30_000, t.onRateLimit("h:80", null))
        advanceMs(30_001)
        assertEquals(60_000, t.onRateLimit("h:80", null))
        advanceMs(60_001)
        assertEquals(120_000, t.onRateLimit("h:80", null))
        advanceMs(120_001)
        t.onSuccess("h:80")
        assertEquals(30_000, t.onRateLimit("h:80", null))
    }

    @Test
    fun `a 429 during an active cooldown is the same event and does not escalate`() {
        val t = throttle()
        assertEquals(30_000, t.onRateLimit("h:80", null))
        advanceMs(1_000)
        // A sibling request already in flight reports the same rate-limit event:
        // it gets the remaining cooldown back and the ladder does not climb.
        assertEquals(29_000, t.onRateLimit("h:80", null))
        advanceMs(29_001)
        assertEquals(60_000, t.onRateLimit("h:80", null), "ladder must have climbed exactly once")
    }

    @Test
    fun `429 teaches the host a request spacing that serializes callers`() {
        val t = throttle()
        t.onRateLimit("h:80", null)
        advanceMs(30_001)
        assertEquals(0, t.acquireDelayMs("h:80")) // reserves now + 6s spacing
        assertEquals(6_001, t.acquireDelayMs("h:80"))
        advanceMs(6_001)
        assertEquals(0, t.acquireDelayMs("h:80"))
    }

    @Test
    fun `successes decay the spacing back to zero`() {
        val t = throttle(initialSpacingMs = 600)
        t.onRateLimit("h:80", null)
        advanceMs(30_001)
        t.onSuccess("h:80") // 600 → 540
        t.onSuccess("h:80") // 540 → 486, below the 500ms floor → snaps to 0
        assertEquals(0, t.acquireDelayMs("h:80"))
        assertEquals(0, t.acquireDelayMs("h:80"))
    }
}
