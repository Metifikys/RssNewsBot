package metifikys.enrich

import metifikys.model.Article
import metifikys.model.ShortlistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BadgeResolverTest {

    private class Fixed(override val id: String, private val text: String?, private val delayMs: Long = 0, private val fail: Boolean = false) : BadgeProvider {
        override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
            if (delayMs > 0) Thread.sleep(delayMs)
            if (fail) throw IllegalStateException("boom")
            return text?.let(::Badge)
        }
    }

    @Test
    fun `runs only the enabled providers and keeps provider order`() {
        val r = BadgeResolver(listOf(Fixed("a", "A"), Fixed("b", "B"), Fixed("c", "C")))
        assertEquals(listOf("A", "C"), r.resolve(setOf("a", "c"), null, emptyList(), r.newDeadline()))
        assertEquals(emptyList(), r.resolve(emptySet(), null, emptyList(), r.newDeadline()))
        assertEquals(setOf("a", "b", "c"), r.providerIds)
    }

    @Test
    fun `a slow provider is dropped on its timeout while the others are kept`() {
        val r = BadgeResolver(listOf(Fixed("slow", "S", delayMs = 3_000), Fixed("fast", "F")), timeoutSeconds = 1, maxTotalSeconds = 10)
        val start = System.nanoTime()
        val out = r.resolve(setOf("slow", "fast"), null, emptyList(), r.newDeadline())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(listOf("F"), out)
        assertTrue(elapsedMs < 2_500, "took ${elapsedMs}ms")
    }

    @Test
    fun `a failing provider and an empty one contribute nothing`() {
        val r = BadgeResolver(listOf(Fixed("x", null), Fixed("y", "Y", fail = true), Fixed("z", "Z")))
        assertEquals(listOf("Z"), r.resolve(setOf("x", "y", "z"), null, emptyList(), r.newDeadline()))
    }

    @Test
    fun `an exhausted per-digest budget skips lookups entirely`() {
        val r = BadgeResolver(listOf(Fixed("a", "A")), timeoutSeconds = 1, maxTotalSeconds = 1)
        val expired = System.nanoTime() - 1
        assertEquals(emptyList(), r.resolve(setOf("a"), null, emptyList(), expired))
    }
}
