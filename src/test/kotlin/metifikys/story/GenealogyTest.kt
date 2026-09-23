package metifikys.story

import metifikys.config.FirstLineConfig
import metifikys.config.HypeLineConfig
import metifikys.config.SourceLeaderboardConfig
import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GenealogyTest {

    private val t0 = LocalDateTime.of(2026, 9, 1, 12, 0)
    private var nextId = 1

    private fun row(domain: String, pubMin: Long, seenMin: Long? = pubMin, key: String = "k") = EventArticleRow(
        category = "games", articleId = nextId++, eventKey = key, link = "https://$domain/${nextId}", domain = domain,
        pubDate = t0.plusMinutes(pubMin), seenAt = seenMin?.let { t0.plusMinutes(it) },
        role = EventArticleRole.STEP1, step1Status = "new", recordedAt = t0
    )

    private fun cluster(vararg rows: EventArticleRow, key: String = "k") = EventCluster(key, rows.toList())

    @Test
    fun `the outlet with the earliest article is first, with its lead over the second`() {
        val r = Genealogy.first(cluster(row("b.com", 150), row("a.com", 30), row("a.com", 400), row("c.com", 200)))!!
        assertEquals("a.com", r.firstDomain)
        assertEquals(120, r.leadMinutes)
        assertEquals(3, r.outlets)
    }

    @Test
    fun `a feed date from the future does not make an outlet first`() {
        // A feed date before our first sighting (12:00 vs seen 15:00) is the normal case and stands…
        val honest = Genealogy.first(cluster(row("early.com", 0, seenMin = 180), row("late.com", 60)))!!
        assertEquals("early.com", honest.firstDomain)
        // …but a claim of 14:00 for an article we saw at 11:30 is a future date: seen_at is used.
        val future = Genealogy.first(cluster(row("future.com", pubMin = 120, seenMin = -30), row("real.com", -10)))!!
        assertEquals("future.com", future.firstDomain)
        assertEquals(20, future.leadMinutes)
    }

    @Test
    fun `a lead within minLeadMinutes is a tie and shows nothing`() {
        val cfg = FirstLineConfig(minOutlets = 3, minLeadMinutes = 30)
        val tie = Genealogy.first(cluster(row("a.com", 0), row("b.com", 20), row("c.com", 90)))
        assertNull(StoryLines.first(tie, cfg))
        val scoop = Genealogy.first(cluster(row("a.com", 0), row("b.com", 45), row("c.com", 90)))
        assertEquals("🥇 першим був a.com (+45 хв)", StoryLines.line(null, null, scoop, cfg))
        val small = Genealogy.first(cluster(row("a.com", 0), row("b.com", 300)))
        assertNull(StoryLines.first(small, cfg))
    }

    @Test
    fun `hype and first share one line`() {
        val hype = Hype.Result(outlets = 9, originals = 1, commonSource = null, reprints = emptySet())
        val first = Genealogy.Result("insider-gaming.com", t0, 130, 9)
        assertEquals(
            "📢 9 видань · 1 першоджерело · 🥇 insider-gaming.com (+2 год)",
            StoryLines.line(hype, HypeLineConfig(minOutlets = 4), first, FirstLineConfig())
        )
        assertEquals("📢 9 видань · 1 першоджерело", StoryLines.line(hype, HypeLineConfig(minOutlets = 4), first, null))
        assertEquals("+3 дн.", StoryLines.lead(3 * 24 * 60 + 5))
        assertEquals("+47 год", StoryLines.lead(47 * 60 + 59))
    }

    @Test
    fun `leaderboard - minEvents, ties, median lag and one outlet with several articles`() {
        val clusters = listOf(
            cluster(row("a.com", 0), row("a.com", 5), row("b.com", 60), row("c.com", 120), key = "e1"),
            cluster(row("b.com", 0), row("a.com", 30), row("c.com", 90), key = "e2"),
            cluster(row("a.com", 0), row("c.com", 0), row("b.com", 10), key = "e3"),
            cluster(row("a.com", 0), row("d.com", 5), key = "e4")   // two outlets: not counted
        )
        val rows = SourceLeaderboard.compute(clusters, minOutlets = 3, minEvents = 3)
        assertEquals(listOf("a.com", "b.com", "c.com"), rows.map { it.domain })
        val a = rows[0]
        assertEquals(3, a.events)
        assertEquals(2, a.firsts)
        assertEquals(30, a.medianLagMinutes)
        val c = rows[2]
        assertEquals(1, c.firsts)             // the tie in e3 counts for both
        assertEquals(120, c.medianLagMinutes) // lags 120, 90 → upper median
        assertEquals(emptyList(), SourceLeaderboard.compute(clusters, minOutlets = 3, minEvents = 4))
    }

    @Test
    fun `leaderboard reprint share and the weekly block`() {
        val r1 = row("a.com", 0); val r2 = row("b.com", 60); val r3 = row("c.com", 120); val r4 = row("c.com", 130)
        val rows = SourceLeaderboard.compute(listOf(cluster(r1, r2, r3, r4)), reprints = setOf(r3.articleId))
        assertEquals(0.5, rows.single { it.domain == "c.com" }.reprintShare)
        assertEquals(0.0, rows.single { it.domain == "a.com" }.reprintShare)

        val cfg = SourceLeaderboardConfig(enabled = true, top = 1, showCopiers = true)
        assertEquals(
            "🥇 Хто пише першим (30 дн.): a.com — 1 з 1\n🐢 Частіше доганяють: b.com (+1 год, передруків 0%)",
            StoryLines.leaderboard(rows, cfg)
        )
        assertEquals("🥇 Хто пише першим (30 дн.): a.com — 1 з 1", StoryLines.leaderboard(rows, cfg.copy(showCopiers = false)))
        assertNull(StoryLines.leaderboard(emptyList(), cfg))
    }
}
