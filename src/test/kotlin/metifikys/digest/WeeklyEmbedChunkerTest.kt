package metifikys.digest

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyEmbedChunkerTest {

    private fun text(chars: Int): String = "x".repeat(chars)

    @Test
    fun `empty input yields no chunks`() {
        assertEquals(emptyList(), WeeklyEmbedChunker.chunk(emptyList(), 100))
    }

    @Test
    fun `all texts fit in one chunk when under budget`() {
        val texts = listOf(text(30), text(30), text(30))
        val chunks = WeeklyEmbedChunker.chunk(texts, maxChars = 100)
        assertEquals(1, chunks.size)
        assertEquals(texts, chunks[0])
    }

    @Test
    fun `splits into multiple chunks respecting the char budget`() {
        // 5 texts of 40 chars, budget 100 → chunks of [40,40] (80), [40,40] (80), [40].
        val texts = List(5) { text(40) }
        val chunks = WeeklyEmbedChunker.chunk(texts, maxChars = 100)
        assertEquals(listOf(2, 2, 1), chunks.map { it.size })
        // Order preserved and nothing dropped.
        assertEquals(texts, chunks.flatten())
        // Every chunk is within budget (single-text chunks excepted).
        assertTrue(chunks.all { c -> c.size == 1 || c.sumOf { it.length } <= 100 })
    }

    @Test
    fun `a single over-budget text is emitted alone, never dropped`() {
        val texts = listOf(text(50), text(250), text(50))
        val chunks = WeeklyEmbedChunker.chunk(texts, maxChars = 100)
        // 50 alone (next 250 would overflow), 250 alone (over budget), 50 alone.
        assertEquals(listOf(1, 1, 1), chunks.map { it.size })
        assertEquals(texts, chunks.flatten())
    }

    @Test
    fun `exact-boundary text still fits in the current chunk`() {
        // 60 + 40 == 100 == budget → both in one chunk.
        val texts = listOf(text(60), text(40))
        val chunks = WeeklyEmbedChunker.chunk(texts, maxChars = 100)
        assertEquals(1, chunks.size)
        assertEquals(2, chunks[0].size)
    }
}
