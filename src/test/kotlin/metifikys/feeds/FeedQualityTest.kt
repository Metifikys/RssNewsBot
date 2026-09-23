package metifikys.feeds

import metifikys.config.FeedHealthThresholds
import metifikys.db.FeedArticleOutcome
import metifikys.db.FeedSighting
import metifikys.db.FeedStatsRow
import metifikys.feeds.FeedQuality.Hint
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedQualityTest {

    private val now = LocalDateTime.of(2026, 9, 23, 12, 0)
    private val t = FeedHealthThresholds()
    private var nextId = 1

    private fun art(
        feed: String, link: String, status: String = "PROCESSED", at: LocalDateTime = now.minusDays(1),
        drop: String? = null, dupOf: Int? = null, step1: String? = null, id: Int = nextId++, junk: Double? = null
    ) = FeedArticleOutcome(id, feed, "tech", link, status, drop, at, dupOf, step1, junk)

    private fun stats(feed: String, success: LocalDateTime? = now.minusHours(1), streak: Int = 0, err: String? = null) =
        FeedStatsRow(feed, "tech", now.minusHours(1), success, streak, 10, streak, err, 5)

    private fun compute(
        feeds: List<String>, outcomes: List<FeedArticleOutcome>, groups: List<Set<String>> = emptyList(),
        sightings: List<FeedSighting> = emptyList(), stats: List<FeedStatsRow> = emptyList(),
        lastNew: Map<String, LocalDateTime> = emptyMap(), trackingSince: LocalDateTime? = now.minusDays(60)
    ) = FeedQuality.compute(
        FeedQuality.Input(feeds.associateWith { "tech" }, outcomes, groups, sightings, stats, lastNew, trackingSince, now), t
    ).associateBy { it.feedUrl }

    @Test
    fun `outcome shares, pending not in the denominator`() {
        val rows = listOf(
            art("A", "p1"), art("A", "p2"),                         // published
            art("A", "m", drop = "mute"),                           // muted
            art("A", "d", status = "DUPLICATE", dupOf = 999),       // L2
            art("A", "r", step1 = "rejected"),
            art("A", "s", step1 = "duplicate"),
            art("A", "u"),                                           // unselected
            art("A", "q", status = "UNPROCESSED"), art("A", "q2", status = "PROCESSING")
        )
        val m = compute(listOf("A"), rows, groups = listOf(setOf("p1"), setOf("p2"))).getValue("A")
        assertEquals(9, m.total); assertEquals(2, m.pending); assertEquals(7, m.decided)
        assertEquals(listOf(2, 1, 1, 1, 1, 1), listOf(m.published, m.muted, m.dupL2, m.step1Rejected, m.step1Duplicate, m.unselected))
        assertEquals(2.0 / 7, m.publishRate, 1e-9)
        assertEquals(3.0 / 7, m.junkRate, 1e-9)
    }

    @Test
    fun `a cluster of three feeds credits the earliest arrival, the others echo with a lag`() {
        val first = art("A", "x1", at = now.minusHours(5))
        val rows = listOf(
            first,
            art("B", "x2", status = "DUPLICATE", dupOf = first.id, at = now.minusHours(4))
        )
        val sightings = listOf(FeedSighting("x1", "C", "tech", now.minusHours(3)))
        val ms = compute(listOf("A", "B", "C"), rows, groups = listOf(setOf("x1")), sightings = sightings)
        assertEquals(1 to 0, ms.getValue("A").firstSource to ms.getValue("A").echo)
        assertEquals(0 to 1, ms.getValue("B").firstSource to ms.getValue("B").echo)
        assertEquals(60L, ms.getValue("B").medianLagMinutes)
        assertEquals(120L, ms.getValue("C").medianLagMinutes)
        assertEquals(1, ms.getValue("A").published)
        assertEquals(0, ms.getValue("C").total, "a sighting is not an article of that feed")
    }

    @Test
    fun `dead after failStreak or an old last success, never on a feed without stats`() {
        val ms = compute(
            listOf("A", "B", "C", "D"), emptyList(),
            stats = listOf(
                stats("A", streak = t.deadFailStreak),
                stats("B", success = now.minusDays(t.deadDays).minusMinutes(1), streak = 2, err = "HTTP 403"),
                stats("C", streak = t.deadFailStreak - 1)
            ),
            lastNew = mapOf("C" to now.minusHours(2))
        )
        assertTrue(Hint.DEAD in ms.getValue("A").hints)
        assertTrue(Hint.DEAD in ms.getValue("B").hints)
        assertTrue(ms.getValue("C").hints.isEmpty())
        assertTrue(ms.getValue("D").hints.isEmpty())
    }

    @Test
    fun `stale only when fetching fine and tracking is older than the stale window`() {
        val old = now.minusDays(t.staleNewDays).minusHours(1)
        val ms = compute(listOf("A", "B"), emptyList(), stats = listOf(stats("A"), stats("B")), lastNew = mapOf("A" to old))
        assertEquals(listOf(Hint.STALE), ms.getValue("A").hints)
        assertEquals(listOf(Hint.STALE), ms.getValue("B").hints, "never brought anything since tracking began")
        val young = compute(listOf("B"), emptyList(), stats = listOf(stats("B")), trackingSince = now.minusDays(2))
        assertTrue(young.getValue("B").hints.isEmpty(), "tracking too young to judge")
    }

    @Test
    fun `noisy at the thresholds`() {
        fun feed(total: Int, published: Int): FeedQuality.FeedMetrics {
            val rows = (1..total).map { art("A", "l$it", step1 = if (it > published) "rejected" else null) }
            return compute(listOf("A"), rows, groups = (1..published).map { setOf("l$it") }).getValue("A")
        }
        assertTrue(Hint.NOISY in feed(t.noisyMinArticles * 2, 1).hints)       // 1/60 = 1.7% < 2%
        assertTrue(Hint.NOISY !in feed(t.noisyMinArticles - 1, 0).hints)      // too few articles
        assertTrue(Hint.NOISY !in feed(50, 1).hints)                          // 2% is not < 2%
    }

    @Test
    fun `mirror and first source need enough clusters`() {
        val rows = mutableListOf<FeedArticleOutcome>()
        val sightings = mutableListOf<FeedSighting>()
        val groups = (1..t.minPublishedForFirst).map { i ->
            rows += art("P", "s$i", at = now.minusHours(10))
            sightings += FeedSighting("s$i", "M", "tech", now.minusHours(9))
            setOf("s$i")
        }
        val ms = compute(listOf("P", "M"), rows, groups = groups, sightings = sightings)
        assertEquals(listOf(Hint.PRIMARY), ms.getValue("P").hints)
        assertEquals(listOf(Hint.MIRROR), ms.getValue("M").hints)
        val few = compute(listOf("P", "M"), rows.take(1), groups = groups.take(1), sightings = sightings.take(1))
        assertTrue(few.getValue("M").hints.isEmpty())
    }

    @Test
    fun `junk score hook averages when present`() {
        val ms = compute(listOf("A", "B"), listOf(art("A", "a", junk = 0.2), art("A", "b", junk = 0.4), art("B", "c")))
        assertEquals(0.3, ms.getValue("A").avgJunk!!, 1e-9)
        assertEquals(null, ms.getValue("B").avgJunk)
    }

    @Test
    fun `display name prefers the label, shortens reddit and long paths`() {
        assertEquals("Studio TG", FeedQuality.displayName("http://192.168.77.7:1200/telegram/channel/x", "Studio TG"))
        assertEquals("reddit r/Games", FeedQuality.displayName("https://www.reddit.com/r/Games/.rss"))
        assertEquals("arstechnica.com", FeedQuality.displayName("https://arstechnica.com"))
        assertEquals(20, FeedQuality.displayName("https://example.com/a/very/long/path/to/feed.xml", max = 20).length)
    }
}
