package metifikys.feedback

import metifikys.db.AudienceAffinityRow
import metifikys.model.ShortlistItem
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AffinityScoresTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 7, 14, 12, 0)

    private fun row(dimension: String, key: String, score: Double, nTone: Double = 5.0) = AudienceAffinityRow(
        category = "games", dimension = dimension, key = key,
        n = 5.0, nTone = nTone, engagementZ = 0.0, sentiment = score, score = score, updatedAt = now
    )

    private fun item(
        franchise: String = "",
        eventType: String = "",
        subject: String = "",
        url: String = "https://example.com/x"
    ) = ShortlistItem(
        eventKey = "k", subject = subject, franchise = franchise, eventType = eventType,
        coreFact = "f", url = url, status = "new"
    )

    @Test
    fun `itemScore mixes dimensions with fixed weights`() {
        val scores = AffinityScores.of(listOf(
            row(AffinityMath.DIM_FRANCHISE, "silksong", 0.4),
            row(AffinityMath.DIM_EVENT_TYPE, "rumor", 0.2),
            row(AffinityMath.DIM_SOURCE, "example.com", -0.1)
        ))
        val s = scores.itemScore(item(franchise = "Silksong", eventType = "rumor"))
        val expected = 0.35 * 0.4 + 0.30 * 0.2 + 0.20 * (-0.1)
        assertEquals(expected, s, 1e-9)
    }

    @Test
    fun `unknown keys are neutral`() {
        val scores = AffinityScores.of(listOf(row(AffinityMath.DIM_FRANCHISE, "silksong", 0.4)))
        assertEquals(0.0, scores.itemScore(item(franchise = "never seen", eventType = "patch")), 1e-9)
    }

    @Test
    fun `empty lookup reports empty and scores zero`() {
        assertTrue(AffinityScores.EMPTY.isEmpty())
        assertEquals(0.0, AffinityScores.EMPTY.itemScore(item(franchise = "x")), 1e-9)
    }

    @Test
    fun `audience signals block lists liked and disliked, skips noise and non-content dims`() {
        val scores = AffinityScores.of(listOf(
            row(AffinityMath.DIM_FRANCHISE, "persona 4", 0.26),
            row(AffinityMath.DIM_EVENT_TYPE, "patch", -0.18),
            row(AffinityMath.DIM_FRANCHISE, "quiet key", 0.01),        // below threshold → out
            row(AffinityMath.DIM_SOURCE, "gamesradar.com", 0.43),      // source dim → never in prompt
            row(AffinityMath.DIM_SUBJECT, "some subject", 0.30)        // subject dim → never in prompt
        ))
        val block = scores.buildAudienceSignals()
        assertTrue("persona 4" in block)
        assertTrue("patch" in block)
        assertTrue("quiet key" !in block)
        assertTrue("gamesradar" !in block)
        assertTrue("some subject" !in block)
        assertTrue("newsworthiness" in block, "guardrail sentence must be present")
    }

    @Test
    fun `thin keys stay out of the prompt block but still count in itemScore`() {
        // Production case: 'business_move' -0.13 rested on TWO rated posts — a strong-looking
        // score with almost no evidence behind it must not become "responds POORLY" in the
        // Step-1 prompt. The numeric path is deliberately ungated (epsilon floor covers it).
        val scores = AffinityScores.of(listOf(
            row(AffinityMath.DIM_EVENT_TYPE, "business_move", -0.13, nTone = 1.7),
            row(AffinityMath.DIM_EVENT_TYPE, "product_launch", -0.19, nTone = 13.7)
        ))
        val block = scores.buildAudienceSignals()
        assertTrue("product_launch" in block)
        assertTrue("business_move" !in block)
        assertEquals(0.30 * -0.13, scores.itemScore(item(eventType = "business_move")), 1e-9)
    }

    @Test
    fun `audience signals block is empty when nothing clears the threshold`() {
        val scores = AffinityScores.of(listOf(
            row(AffinityMath.DIM_FRANCHISE, "a", 0.01),
            row(AffinityMath.DIM_EVENT_TYPE, "b", -0.02)
        ))
        assertEquals("", scores.buildAudienceSignals())
    }
}

class AffinityImpactTest {

    private fun item(key: String) = ShortlistItem(
        eventKey = key, coreFact = "f", url = "https://x.com/$key", status = "new"
    )

    @Test
    fun `identical lists mean no impact`() {
        val list = listOf(item("a"), item("b"))
        assertNull(AffinityImpact.describe(list, list))
    }

    @Test
    fun `reorder is described with positions`() {
        val impact = AffinityImpact.describe(
            listOf(item("a"), item("b")),
            listOf(item("b"), item("a"))
        )
        assertEquals("reorders 'b' #2→#1, 'a' #1→#2", impact)
    }

    @Test
    fun `swap in and out is described as drops and adds`() {
        val impact = AffinityImpact.describe(
            listOf(item("a"), item("b")),
            listOf(item("a"), item("c"))
        )!!
        assertTrue("drops 'b'" in impact)
        assertTrue("adds 'c'" in impact)
    }
}
