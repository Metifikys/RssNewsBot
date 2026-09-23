package metifikys.story

import metifikys.config.VaporwareLineConfig
import metifikys.db.EventHistoryRow
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaporwareTest {

    private val now = LocalDateTime.of(2026, 9, 23, 12, 0)
    private val cfg = VaporwareLineConfig(minAnnouncements = 3, minGapDays = 30)

    private fun h(daysAgo: Long, type: String = "major_announcement", date: String? = null, key: String = "k$daysAgo") = EventHistoryRow(
        category = "gaming", eventKey = key, entityKey = "hollow_knight_silksong", subject = "Hollow Knight: Silksong",
        franchise = "Hollow Knight", eventType = type, coreFact = "f", eventDate = date, newsworthiness = 5,
        coveredAt = now.minusDays(daysAgo)
    )

    @Test
    fun `waves split on gaps of minGapDays, the current announcement closes the last one`() {
        // Waves: 500/495 days ago, 200 ago, 90/80 ago, now → the fourth time.
        val history = listOf(h(500), h(495), h(200), h(90), h(80))
        val r = Vaporware.count("rumor", null, history, cfg, now)!!
        assertEquals(4, r.number)
        assertEquals(now.minusDays(500), r.firstAt)
        assertEquals("🌀 анонсовано вчетверте, дати досі немає (вперше: 05.2025)", StoryLines.vaporware(r))

        // A post a week after the last one is the same wave: still the third.
        assertEquals(3, Vaporware.count("rumor", null, listOf(h(200), h(90), h(7)), cfg, now)?.number)
        assertNull(Vaporware.count("rumor", null, listOf(h(90), h(7)), cfg, now))
    }

    @Test
    fun `a hard date anywhere in the history means it is not vaporware`() {
        val history = listOf(h(500), h(200), h(90, type = "release_date", date = "2026-11"), h(60))
        assertNull(Vaporware.count("major_announcement", null, history, cfg, now))
        assertNull(Vaporware.count("major_announcement", null, listOf(h(500), h(200, date = "2026-02-14")), cfg, now))
    }

    @Test
    fun `a year or quarter stops the count only with softDatesStop`() {
        val history = listOf(h(500, date = "2027"), h(200), h(90))
        assertEquals(4, Vaporware.count("leak", null, history, cfg, now)?.number)
        assertNull(Vaporware.count("leak", null, history, cfg.copy(softDatesStop = true), now))
        assertNull(Vaporware.count("leak", "2027-Q1", listOf(h(500), h(200)), cfg.copy(softDatesStop = true), now))
    }

    @Test
    fun `only announcement types count, and a dated or other-type item gets no line`() {
        val history = listOf(h(500), h(300, type = "industry_news"), h(200), h(90))
        assertEquals(4, Vaporware.count("dev_quote", null, history, cfg, now)?.number)
        assertNull(Vaporware.count("release_date", null, history, cfg, now))
        assertNull(Vaporware.count("rumor", "2026-12-01", history, cfg, now))
    }

    @Test
    fun `ordinals from twice to the tenth time, then a number`() {
        assertEquals(
            listOf("вдруге", "втретє", "вчетверте", "вп'яте", "ушосте", "усьоме", "увосьме", "удев'яте", "удесяте", "уже 11-й раз", "уже 12-й раз"),
            (2..12).map { StoryLines.ordinalTimes(it) }
        )
    }
}
