package metifikys.digest

import metifikys.db.BotStateStore
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PauseStoreTest {

    private class MapState : BotStateStore {
        val map = HashMap<String, String>()
        override fun getState(key: String) = map[key]
        override fun setState(key: String, value: String) { map[key] = value }
        override fun deleteState(key: String) = map.remove(key) != null
    }

    @Test
    fun `durations parse in minutes, hours, days and weeks`() {
        assertEquals(Duration.ofMinutes(30), PauseStore.parseDuration("30m"))
        assertEquals(Duration.ofHours(6), PauseStore.parseDuration("6H"))
        assertEquals(Duration.ofDays(2), PauseStore.parseDuration("2d"))
        assertEquals(Duration.ofDays(14), PauseStore.parseDuration("2w"))
        assertNull(PauseStore.parseDuration("0h"))
        assertNull(PauseStore.parseDuration("soon"))
        assertNull(PauseStore.parseDuration("6"))
    }

    @Test
    fun `a timed pause expires and is removed, an open one waits for resume`() {
        var now = LocalDateTime.of(2026, 9, 23, 12, 0)
        val state = MapState()
        val store = PauseStore(state) { now }
        store.pause("tech", now.plusHours(6))
        store.pause("gaming", null)
        assertEquals(now.plusHours(6), store.pausedUntil("tech")?.until)
        assertEquals(PauseStore.Pause(null), store.pausedUntil("gaming"))

        now = now.plusHours(7)
        assertNull(store.pausedUntil("tech"))
        assertFalse("pause:tech" in state.map, "expired pause is cleaned up")
        assertTrue(store.resume("gaming"))
        assertFalse(store.resume("gaming"))
        assertNull(store.pausedUntil("gaming"))
    }

    @Test
    fun `a broken value reads as not paused`() {
        val state = MapState().apply { map["pause:tech"] = "garbage" }
        assertNull(PauseStore(state).pausedUntil("tech"))
        assertTrue(state.map.isEmpty())
    }
}
