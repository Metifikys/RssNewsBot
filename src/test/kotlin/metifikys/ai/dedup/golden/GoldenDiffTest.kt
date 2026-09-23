package metifikys.ai.dedup.golden

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoldenDiffTest {

    private fun item(url: String, status: String = "new", nw: Int = 5) = GoldenShortlistItem(url, status, nw, 5)

    @Test
    fun `report lists shortlist moves, score changes, new drops and a changed prompt`() {
        val old = GoldenExpected(false, listOf(item("a"), item("b")), drops = listOf(GoldenDrop("x", "cooldown")))
        val new = GoldenExpected(
            false, listOf(item("a", status = "meaningful_update", nw = 7), item("c")),
            drops = listOf(GoldenDrop("x", "cooldown"), GoldenDrop("b", "ranker: maxDigestItems=1 reached"))
        )
        val report = GoldenDiff.report("case-1", old, new, promptChanged = true)

        assertTrue("prompt changed since record" in report)
        assertTrue("Jaccard 0.33" in report, report)
        assertTrue("+ c" in report && "- b" in report, report)
        assertTrue("status new->meaningful_update, nw 5->7" in report, report)
        assertTrue("new drop ranker: maxDigestItems=1 reached: b" in report, report)
        assertTrue("new drop cooldown" !in report, report)
    }

    @Test
    fun `jaccard of two empty shortlists is 1`() {
        assertEquals(1.0, GoldenDiff.jaccard(emptySet(), emptySet()))
    }
}
