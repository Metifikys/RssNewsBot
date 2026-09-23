package metifikys.telegram

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatPacerTest {

    /** Fake clock whose sleeper advances time instead of blocking. */
    private class FakeTime {
        var nanos = 1_000_000_000L
        val slept = mutableListOf<Long>()
        val sleeper: (Long) -> Unit = { ms -> slept += ms; nanos += ms * 1_000_000 }
    }

    private fun pacer(t: FakeTime) = ChatPacer(minIntervalMs = 1_000, nanos = { t.nanos }, sleeper = t.sleeper)

    @Test
    fun `two requests to one chat are spaced at least the minimum interval apart`() {
        val t = FakeTime()
        val p = pacer(t)
        p.acquire("-100")
        val first = t.nanos
        p.acquire("-100")
        assertTrue(t.nanos - first >= 1_000_000_000L, "second request started after ${(t.nanos - first) / 1_000_000} ms")
    }

    @Test
    fun `the gap already elapsed costs nothing`() {
        val t = FakeTime()
        val p = pacer(t)
        p.acquire("-100")
        t.nanos += 2_000_000_000L
        p.acquire("-100")
        assertEquals(emptyList(), t.slept)
    }

    @Test
    fun `different chats do not block each other`() {
        val t = FakeTime()
        val p = pacer(t)
        p.acquire("-100")
        p.acquire("-200")
        p.acquire("@status")
        assertEquals(emptyList(), t.slept)
    }

    @Test
    fun `a cooldown holds every caller for that chat and only that chat`() {
        val t = FakeTime()
        val p = pacer(t)
        p.cooldown("-100", 5_000)
        assertTrue(p.acquireDelayMs("-100") > 4_000, "another thread sees the cooldown too")
        assertEquals(0, p.acquireDelayMs("-200"))

        val start = t.nanos
        p.acquire("-100")
        assertTrue(t.nanos - start >= 5_000_000_000L)
    }

    @Test
    fun `a shorter cooldown never cuts an active longer one`() {
        val t = FakeTime()
        val p = pacer(t)
        p.cooldown("-100", 10_000)
        p.cooldown("-100", 1_000)
        assertTrue(p.acquireDelayMs("-100") > 9_000)
    }

    @Test
    fun `an interrupt during the wait propagates`() {
        val p = ChatPacer(minIntervalMs = 60_000)
        p.acquire("-100")
        Thread.currentThread().interrupt()
        try {
            assertThrows<InterruptedException> { p.acquire("-100") }
        } finally {
            Thread.interrupted()
        }
    }
}
