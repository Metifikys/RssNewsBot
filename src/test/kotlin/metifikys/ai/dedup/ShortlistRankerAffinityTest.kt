package metifikys.ai.dedup

import metifikys.config.DigestConfig
import metifikys.config.RankerConfig
import metifikys.model.ShortlistItem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortlistRankerAffinityTest {

    private fun item(
        key: String,
        franchise: String = key,
        news: Int = 6,
        fit: Int = 6,
        eventType: String = "major_announcement"
    ) = ShortlistItem(
        eventKey = key,
        subject = "subject $key",
        franchise = franchise,
        eventType = eventType,
        coreFact = "fact",
        importance = news,
        newsworthiness = news,
        digestFit = fit,
        url = "https://example.com/$key",
        status = "new"
    )

    private fun config(
        weight: Double,
        boost: Double = 1.0,
        maxItems: Int = 6
    ) = DigestConfig(
        ranker = RankerConfig(
            enabled = true,
            newsworthinessWeight = 0.6,
            reactionWeight = weight,
            maxAffinityBoost = boost
        ),
        maxDigestItems = maxItems
    )

    @Test
    fun `null scorer keeps pre-phase-2 ranking bit-for-bit`() {
        val items = listOf(item("a", news = 9), item("b", news = 5), item("c", news = 7))
        val cfg = config(weight = 0.15)
        assertEquals(
            ShortlistRanker.rank(items, cfg).kept,
            ShortlistRanker.rank(items, cfg, affinityScorer = null).kept
        )
    }

    @Test
    fun `zero weight ignores the scorer entirely`() {
        val items = listOf(item("a"), item("b"))
        val cfg = config(weight = 0.0)
        val biased = ShortlistRanker.rank(items, cfg) { if (it.eventKey == "b") 10.0 else -10.0 }
        assertEquals(ShortlistRanker.rank(items, cfg).kept, biased.kept)
    }

    @Test
    fun `affinity breaks a near-tie in favor of the liked item`() {
        // b slightly behind a on composite (0.6·6+0.4·6=6.0 vs 0.6·6+0.4·7=6.4),
        // but audience loves b's franchise: +0.4 raw × 0.15 × 10 = +0.6pts → b wins.
        val a = item("a", fit = 7)
        val b = item("b", fit = 6)
        val cfg = config(weight = 0.15)
        val scorer: (ShortlistItem) -> Double = { if (it.eventKey == "b") 0.4 else 0.0 }

        val baseline = ShortlistRanker.rank(listOf(a, b), cfg)
        assertEquals(listOf("a", "b"), baseline.kept.map { it.eventKey })

        val ranked = ShortlistRanker.rank(listOf(a, b), cfg, scorer)
        assertEquals(listOf("b", "a"), ranked.kept.map { it.eventKey })
    }

    @Test
    fun `negative affinity pushes an item out of a full digest`() {
        // 3 slots, 4 equal candidates; the audience dislikes "d" → it must be the one dropped.
        val items = listOf(item("a"), item("b"), item("c"), item("d"))
        val cfg = config(weight = 0.15, maxItems = 3)
        val scorer: (ShortlistItem) -> Double = { if (it.eventKey == "d") -0.4 else 0.0 }

        val ranked = ShortlistRanker.rank(items, cfg, scorer)
        assertEquals(3, ranked.kept.size)
        assertTrue(ranked.kept.none { it.eventKey == "d" })
        assertTrue(ranked.dropped.any { it.item.eventKey == "d" })
    }

    @Test
    fun `contribution is clamped to maxAffinityBoost`() {
        val cfg = config(weight = 1.0, boost = 0.5)
        val up = ShortlistRanker.affinityContribution(item("x"), cfg) { 100.0 }
        val down = ShortlistRanker.affinityContribution(item("x"), cfg) { -100.0 }
        assertEquals(0.5, up, 1e-9)
        assertEquals(-0.5, down, 1e-9)
    }

    @Test
    fun `clamped affinity cannot override a composite gap above twice the boost`() {
        // a: news 9, b: news 5 — composite gap 2.4pts at wNews=0.6. Max relative swing is
        // 2×boost = 2.0 (b clamped up, a clamped down) — not enough to flip.
        val a = item("a", news = 9)
        val b = item("b", news = 5)
        val cfg = config(weight = 1.0, boost = 1.0)
        val scorer: (ShortlistItem) -> Double = { if (it.eventKey == "b") 100.0 else -100.0 }

        val ranked = ShortlistRanker.rank(listOf(a, b), cfg, scorer)
        assertEquals("a", ranked.kept.first().eventKey, "strong news must survive max audience bias")
    }

    @Test
    fun `within twice the boost audience taste CAN flip the order`() {
        // Same setup but a smaller gap (1.2pts < 2×boost) — documents the flip boundary.
        val a = item("a", news = 8)
        val b = item("b", news = 6)
        val cfg = config(weight = 1.0, boost = 1.0)
        val scorer: (ShortlistItem) -> Double = { if (it.eventKey == "b") 100.0 else -100.0 }

        val ranked = ShortlistRanker.rank(listOf(a, b), cfg, scorer)
        assertEquals("b", ranked.kept.first().eventKey)
    }

    @Test
    fun `floor filter is unaffected by affinity`() {
        // Affinity re-ranks; it must never rescue an item the floor filter rejects.
        val weak = item("weak", news = 4, fit = 1)
        val cfg = config(weight = 1.0, boost = 1.0)
        val ranked = ShortlistRanker.rank(listOf(weak), cfg) { 100.0 }
        assertTrue(ranked.kept.isEmpty())
        assertEquals(1, ranked.dropped.size)
        assertTrue(abs(ShortlistRanker.affinityContribution(weak, cfg) { 100.0 }) <= 1.0)
    }
}
