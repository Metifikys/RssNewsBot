package metifikys.ai.dedup

import metifikys.config.AntiBubbleConfig
import metifikys.config.DigestConfig
import metifikys.model.ShortlistItem
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AntiBubbleTest {

    private fun item(key: String, nw: Int, fit: Int = 6, subject: String = key) = ShortlistItem(
        eventKey = key, subject = subject, coreFact = "f", newsworthiness = nw, digestFit = fit,
        url = "https://a.com/$key", status = "new"
    )

    private val digest = DigestConfig(minDigestFit = 3, newsworthinessOverride = 9)
    private val cfg = AntiBubbleConfig(minNewsworthiness = 8, maxAffinity = -0.05)

    private fun pick(pool: List<ShortlistItem>, kept: List<ShortlistItem>, affinity: Map<String, Double>) =
        AntiBubble.pick(pool, kept, digest, cfg) { affinity[it.eventKey] ?: 0.0 }

    @Test
    fun `picks the most newsworthy left-out event with low affinity`() {
        val kept = listOf(item("top", 9))
        val pool = kept + listOf(item("a", 8), item("b", 9), item("c", 10))
        val p = pick(pool, kept, mapOf("a" to -0.3, "b" to -0.1, "c" to 0.2))
        assertEquals("b", p?.item?.eventKey)
        assertEquals(-0.1, p?.affinity)
    }

    @Test
    fun `below minNewsworthiness, unknown affinity, kept items and liked items are no candidates`() {
        val kept = listOf(item("top", 9))
        val pool = kept + listOf(item("weak", 7), item("unseen", 9), item("liked", 9))
        assertNull(pick(pool, kept, mapOf("top" to -0.5, "weak" to -0.5, "liked" to 0.1)))
    }

    @Test
    fun `an event under the digestFit floor or with a subject already in the digest is never revived`() {
        val kept = listOf(item("top", 9, subject = "Hades II"))
        val floor = item("floor", 8, fit = 2)             // fit 2 < 3 and nw 8 < override 9
        val sameGame = item("same", 9, subject = "hades ii")
        assertNull(pick(kept + listOf(floor, sameGame), kept, mapOf("floor" to -0.5, "same" to -0.5)))
    }

    @Test
    fun `ties go to the higher composite, then the lower affinity`() {
        val kept = listOf(item("top", 10))
        val pool = kept + listOf(item("x", 8, fit = 5), item("y", 8, fit = 7), item("z", 8, fit = 7))
        assertEquals("z", pick(pool, kept, mapOf("x" to -0.9, "y" to -0.1, "z" to -0.2))?.item?.eventKey)
    }

    @Test
    fun `interval counts from the last explore post`() {
        val now = LocalDateTime.of(2026, 9, 23, 12, 0)
        assertTrue(AntiBubble.isDue(null, cfg, now))
        assertFalse(AntiBubble.isDue(now.minusDays(6), cfg, now))
        assertTrue(AntiBubble.isDue(now.minusDays(7), cfg, now))
        assertEquals("antibubble:gaming", AntiBubble.stateKey("gaming"))
    }
}
