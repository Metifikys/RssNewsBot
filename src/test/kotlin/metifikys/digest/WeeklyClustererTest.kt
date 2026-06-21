package metifikys.digest

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyClustererTest {

    private val base: LocalDateTime = LocalDateTime.of(2024, 1, 7, 12, 0)

    private fun ev(
        key: String,
        subject: String,
        vector: FloatArray,
        covered: Boolean = true,
        news: Int = 5,
        importance: Int = 5,
        coveredAt: LocalDateTime? = base
    ) = WeeklyEvent(
        eventKey = key,
        subject = subject,
        franchise = "",
        eventType = "",
        coreFact = "fact for $subject",
        url = "https://example.com/$key",
        newsworthiness = news,
        importance = importance,
        digestFit = 5,
        coveredAt = if (covered) coveredAt else null,
        isCovered = covered,
        vector = vector
    )

    // Three roughly-orthogonal directions so "different stories" never merge.
    private val x = floatArrayOf(1f, 0f, 0f)
    private val y = floatArrayOf(0f, 1f, 0f)
    private val z = floatArrayOf(0f, 0f, 1f)
    // Near-identical to x (cosine ≈ 0.9988) so it merges at threshold 0.8.
    private val xNear = floatArrayOf(0.95f, 0.05f, 0f)

    @Test
    fun `near-identical covered events merge into one cluster`() {
        val stories = WeeklyClusterer.cluster(
            listOf(
                ev("a1", "GTA 6 delayed", x, news = 9),
                ev("a2", "Rockstar pushes GTA VI", xNear, news = 4)
            ),
            threshold = 0.8
        )
        assertEquals(1, stories.size)
        assertEquals(2, stories[0].mentionCount)
        // Higher-newsworthiness event is the representative.
        assertEquals("GTA 6 delayed", stories[0].subject)
        assertTrue(stories[0].alsoReportedAs.contains("Rockstar pushes GTA VI"))
    }

    @Test
    fun `duplicate detections bump the mention count of their covered cluster`() {
        val stories = WeeklyClusterer.cluster(
            listOf(
                ev("a1", "GTA 6 delayed", x, news = 9),
                ev("d1", "GTA VI moved to 2026", xNear, covered = false),
                ev("d2", "GTA 6 release slips", x, covered = false)
            ),
            threshold = 0.8
        )
        assertEquals(1, stories.size)
        assertEquals(3, stories[0].mentionCount)
    }

    @Test
    fun `duplicate with no covered cluster is dropped`() {
        val stories = WeeklyClusterer.cluster(
            listOf(
                ev("a1", "GTA 6 delayed", x),
                ev("d1", "Unrelated never-covered story", z, covered = false)
            ),
            threshold = 0.8
        )
        assertEquals(1, stories.size)
        assertEquals(1, stories[0].mentionCount)
        assertEquals("GTA 6 delayed", stories[0].subject)
    }

    @Test
    fun `distinct stories stay separate and sort most-repeated first`() {
        val stories = WeeklyClusterer.cluster(
            listOf(
                ev("a1", "Story X", x, news = 5),
                ev("dx1", "X again", x, covered = false),
                ev("dx2", "X once more", x, covered = false),
                ev("b1", "Story Y", y, news = 8)
            ),
            threshold = 0.8
        )
        assertEquals(2, stories.size)
        // X has 3 mentions, Y has 1 → X ranks first despite Y's higher newsworthiness.
        assertEquals("Story X", stories[0].subject)
        assertEquals(3, stories[0].mentionCount)
        assertEquals("Story Y", stories[1].subject)
        assertEquals(1, stories[1].mentionCount)
    }

    @Test
    fun `empty input yields no stories`() {
        assertEquals(0, WeeklyClusterer.cluster(emptyList(), threshold = 0.8).size)
    }
}
