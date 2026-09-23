package metifikys.story

import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import metifikys.story.FactSlots.Slot
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictDetectorTest {

    private val t0 = LocalDateTime.of(2026, 9, 20, 10, 0)

    private fun row(id: Int, domain: String, minutes: Long = id.toLong()) = EventArticleRow(
        category = "gaming", articleId = id, eventKey = "evt", link = "https://$domain/$id", domain = domain,
        pubDate = t0.plusMinutes(minutes), seenAt = null, role = EventArticleRole.STEP1, step1Status = "new",
        recordedAt = t0
    )

    /** A cluster of (domain, text) members and the texts keyed by link. */
    private fun cluster(vararg members: Pair<String, String>): Pair<EventCluster, Map<String, String>> {
        val rows = members.mapIndexed { i, (d, _) -> row(i + 1, d) }
        return EventCluster("evt", rows) to rows.zip(members).associate { (r, m) -> r.link to m.second }
    }

    private fun detect(vararg members: Pair<String, String>, minSupport: Int = 1, tol: Int = 1): List<ConflictDetector.Conflict> {
        val (c, texts) = cluster(*members)
        return ConflictDetector.detect(c, texts, minSupport, tol)
    }

    @Test
    fun `two release dates from different outlets conflict and read as one footer line`() {
        val found = detect(
            "ign.com" to "Silksong launches October 14",
            "eurogamer.net" to "Silksong is out on October 14",
            "pcgamer.com" to "Silksong release date set for October 21"
        )
        assertEquals(1, found.size)
        assertEquals(Slot.DATE, found[0].slot)
        assertEquals(listOf("2026-10-14", "2026-10-21"), found[0].values.map { it.fact.value })
        assertEquals(listOf("ign.com", "eurogamer.net"), found[0].values[0].domains)
        assertEquals(
            "⚖️ Джерела розходяться: дата 14.10.2026 (ign.com, eurogamer.net) чи 21.10.2026 (pcgamer.com)",
            StoryLines.conflict(found)
        )
    }

    @Test
    fun `a quarter agrees with a day inside it, a vague value does not hide a real clash`() {
        assertEquals(emptyList(), detect("a.com" to "coming Q4 2026", "b.com" to "out October 14"))
        val found = detect("a.com" to "coming Q4 2026", "x.com" to "also Q4 2026", "b.com" to "out October 14", "c.com" to "out October 21")
        assertEquals(listOf("2026-10-14", "2026-10-21"), found.single().values.map { it.fact.value })
    }

    @Test
    fun `dates within the tolerance agree (time zones)`() {
        assertEquals(emptyList(), detect("a.com" to "out October 14", "b.com" to "out October 15", tol = 1))
        assertEquals(1, detect("a.com" to "out October 14", "b.com" to "out October 15", tol = 0).size)
    }

    @Test
    fun `an article with standard and deluxe prices says nothing about the price`() {
        assertEquals(emptyList(), detect("a.com" to "\$69.99, deluxe \$99.99", "b.com" to "costs \$79.99"))
        assertEquals(1, detect("a.com" to "costs \$69.99", "b.com" to "costs \$79.99").size)
    }

    @Test
    fun `prices in different currencies are never compared, near-equal amounts agree`() {
        assertEquals(emptyList(), detect("a.com" to "costs \$69.99", "b.com" to "costs €79.99"))
        assertEquals(emptyList(), detect("a.com" to "\$1.5 billion deal", "b.com" to "\$1,500 million deal"))
    }

    @Test
    fun `support counts distinct outlets, not articles`() {
        val (c, texts) = cluster(
            "a.com" to "300 layoffs at the studio",
            "a.com" to "studio cuts 300 jobs",
            "b.com" to "200 layoffs at the studio"
        )
        assertEquals(emptyList(), ConflictDetector.detect(c, texts, minSupport = 2, dateToleranceDays = 1))
        val found = ConflictDetector.detect(c, texts, minSupport = 1, dateToleranceDays = 1).single()
        assertEquals(listOf(listOf("a.com"), listOf("b.com")), found.values.map { it.domains })
        assertEquals("⚖️ Джерела розходяться: звільнення 300 (a.com) чи 200 (b.com)", StoryLines.conflict(listOf(found)))
    }

    @Test
    fun `a late reprint clashing with the whole cluster is reported, an agreeing one is not`() {
        val (c, texts) = cluster(
            "ign.com" to "Silksong launches October 14",
            "pcgamer.com" to "Silksong release date: October 21",
            "kotaku.com" to "Silksong is out October 14"
        )
        val late = ConflictDetector.late(c, texts, lateArticleId = 2, dateToleranceDays = 1).single()
        assertEquals("2026-10-21", late.first.fact.value)
        assertEquals(listOf("2026-10-14"), late.second.map { it.fact.value })
        assertTrue(ConflictDetector.late(c, texts, lateArticleId = 3, dateToleranceDays = 1).isEmpty())
    }

    @Test
    fun `no conflict, no line`() {
        assertNull(StoryLines.conflict(emptyList()))
        assertEquals("€1.5 млрд", StoryLines.factValue(FactSlots.Fact(Slot.PRICE, "EUR", "1500000000", 0..1)))
        assertEquals("70 CAD", StoryLines.factValue(FactSlots.Fact(Slot.PRICE, "CAD", "70", 0..1)))
    }
}
