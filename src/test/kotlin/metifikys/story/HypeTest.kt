package metifikys.story

import metifikys.config.HypeLineConfig
import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import metifikys.digest.VectorMath
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HypeTest {

    private val t0 = LocalDateTime.of(2026, 9, 1, 12, 0)

    private fun row(id: Int, domain: String, minutes: Long) = EventArticleRow(
        category = "games", articleId = id, eventKey = "k", link = "https://$domain/$id", domain = domain,
        pubDate = t0.plusMinutes(minutes), seenAt = null, role = EventArticleRole.STEP1, step1Status = "new", recordedAt = t0
    )

    private fun texts(vararg pairs: Pair<EventArticleRow, String>) = pairs.associate { (r, t) -> r.link to t }

    @Test
    fun `retellings that credit another outlet are not original sources`() {
        val a = row(1, "insider-gaming.com", 0)
        val b = row(2, "ign.com", 30)
        val c = row(3, "pcgamer.com", 40)
        val c2 = row(4, "pcgamer.com", 50)
        val r = Hype.analyze(
            EventCluster("k", listOf(a, b, c, c2)),
            texts(a to "Silksong delayed", b to "Silksong delayed, via Insider Gaming", c to "According to Insider Gaming, delay", c2 to "Silksong delay explained"),
            emptyMap(), 0.95
        )
        assertEquals(3, r.outlets)
        assertEquals(2, r.originals)   // insider-gaming, and pcgamer's own explainer
        assertNull(r.commonSource)
        assertEquals(setOf(2, 3), r.reprints)
    }

    @Test
    fun `everyone crediting the same outlet names it`() {
        val a = row(1, "ign.com", 0)
        val b = row(2, "gamespot.com", 5)
        val r = Hype.analyze(
            EventCluster("k", listOf(a, b)),
            texts(a to "Big leak via Insider Gaming", b to "Big leak, according to Insider Gaming Report"),
            emptyMap(), 0.95
        )
        assertEquals(0, r.originals)
        assertEquals("Insider Gaming", r.commonSource)
        assertEquals("📢 2 видання · усі з посиланням на Insider Gaming", StoryLines.outlets(r))
    }

    @Test
    fun `a near-copy of an earlier outlet is a reprint, the earliest never is`() {
        val a = row(1, "a.com", 0)
        val b = row(2, "b.com", 10)
        val c = row(3, "c.com", 20)
        val v = VectorMath.l2Normalize(floatArrayOf(1f, 0f, 0f))
        val far = VectorMath.l2Normalize(floatArrayOf(0f, 1f, 0f))
        val r = Hype.analyze(EventCluster("k", listOf(c, b, a)), emptyMap(), mapOf(1 to v, 2 to v, 3 to far), 0.95)
        assertEquals(setOf(2), r.reprints)
        assertEquals(2, r.originals)
    }

    @Test
    fun `hype line respects minOutlets and the switch`() {
        val r = Hype.Result(outlets = 4, originals = 1, commonSource = null, reprints = emptySet())
        assertEquals("📢 4 видання · 1 першоджерело", StoryLines.hype(r, HypeLineConfig(minOutlets = 4)))
        assertNull(StoryLines.hype(r, HypeLineConfig(minOutlets = 5)))
        assertNull(StoryLines.hype(r, HypeLineConfig(enabled = false)))
        assertNull(StoryLines.hype(r, null))
        assertEquals("📢 9 видань", StoryLines.outlets(r.copy(outlets = 9, originals = 0)))
        assertEquals("📢 21 видання · 5 першоджерел", StoryLines.outlets(r.copy(outlets = 21, originals = 5)))
        assertEquals("📢 12 видань · 2 першоджерела", StoryLines.outlets(r.copy(outlets = 12, originals = 2)))
    }
}
