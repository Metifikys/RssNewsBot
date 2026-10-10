package metifikys.ai

import metifikys.config.LlmOverride
import metifikys.db.BotStateStore
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmSplitterTest {

    private class MapStore : BotStateStore {
        val map = HashMap<String, String>()
        override fun getState(key: String) = map[key]
        override fun setState(key: String, value: String) { map[key] = value }
        override fun deleteState(key: String) = map.remove(key) != null
    }

    private val openai = LlmOverride("openai", "gpt-mini", percent = 20)
    private val anthropic = LlmOverride("anthropic", "claude-haiku", percent = 10)
    private val openrouter = LlmOverride("openrouter", percent = 70)
    private val legs = listOf(openai, anthropic, openrouter)

    @Test
    fun `20-10-70 gives exactly 2-1-7 over ten calls, spread out`() {
        val splitter = LlmSplitter(null)
        val picks = (1..10).map { splitter.pick("games/summarize", legs).percent }
        assertEquals(listOf(70, 20, 70, 70, 10, 70, 70, 20, 70, 70), picks)
    }

    @Test
    fun `shares hold over a long run`() {
        val splitter = LlmSplitter(null)
        val picks = (1..1000).map { splitter.pick("s", legs) }
        assertEquals(200, picks.count { it === openai })
        assertEquals(100, picks.count { it === anthropic })
        assertEquals(700, picks.count { it === openrouter })
    }

    @Test
    fun `tally survives a restart through the store`() {
        val store = MapStore()
        val day = LocalDate.of(2026, 10, 10)
        val first = LlmSplitter(store) { day }
        val before = (1..4).map { first.pick("games/summarize", legs).percent }
        val restarted = LlmSplitter(store) { day }
        val after = (1..6).map { restarted.pick("games/summarize", legs).percent }
        assertEquals(listOf(70, 20, 70, 70, 10, 70, 70, 20, 70, 70), before + after)
    }

    @Test
    fun `tally starts over at midnight`() {
        val store = MapStore()
        var day = LocalDate.of(2026, 10, 10)
        val splitter = LlmSplitter(store) { day }
        repeat(3) { splitter.pick("s", legs) }
        day = day.plusDays(1)
        assertEquals(70, splitter.pick("s", legs).percent)
        assertEquals(mapOf("openrouter/<default>" to 1L), splitter.snapshot()["s"])
        // A restart on the new day continues the fresh tally, not yesterday's.
        val restarted = LlmSplitter(store) { day }
        assertEquals(20, restarted.pick("s", legs).percent)
        assertEquals(mapOf("openrouter/<default>" to 1L, "openai/gpt-mini" to 1L), restarted.snapshot()["s"])
    }

    @Test
    fun `scopes are independent`() {
        val splitter = LlmSplitter(null)
        assertEquals(70, splitter.pick("games/summarize", legs).percent)
        assertEquals(70, splitter.pick("tech/summarize", legs).percent)
        assertEquals(20, splitter.pick("games/summarize", legs).percent)
    }

    @Test
    fun `an entry added mid-day starts from zero and catches up`() {
        val splitter = LlmSplitter(null)
        val half = listOf(LlmOverride("openai", "a", percent = 50), LlmOverride("openai", "b", percent = 50))
        repeat(4) { splitter.pick("s", half) }
        val third = half.map { it.copy(percent = 25) } + LlmOverride("openai", "c", percent = 50)
        val next = (1..4).map { splitter.pick("s", third).model }
        assertTrue(next.count { it == "c" } >= 3, "new entry should catch up to its share, got $next")
    }

    @Test
    fun `a broken store never blocks a pick`() {
        val broken = object : BotStateStore {
            override fun getState(key: String): String = throw IllegalStateException("db down")
            override fun setState(key: String, value: String) = throw IllegalStateException("db down")
            override fun deleteState(key: String) = false
        }
        val splitter = LlmSplitter(broken)
        assertEquals(listOf(70, 20), (1..2).map { splitter.pick("s", legs).percent })
    }

    @Test
    fun `garbage in the store is treated as an empty tally`() {
        val store = MapStore().apply { map[LlmSplitter.STATE_PREFIX + "s"] = "not json" }
        assertEquals(70, LlmSplitter(store).pick("s", legs).percent)
    }
}
