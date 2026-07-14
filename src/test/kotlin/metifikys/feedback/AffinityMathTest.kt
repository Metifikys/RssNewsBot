package metifikys.feedback

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AffinityMathTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 7, 13, 12, 0)

    private fun params(
        halflifeDays: Double = 45.0,
        shrinkageK: Double = 5.0,
        minSamples: Double = 1.0,
        minVolume: Int = 3
    ) = AffinityMath.Params(halflifeDays, shrinkageK, minSamples, minVolume, now)

    private fun msg(
        franchise: String,
        volume: Int,
        valenceSum: Double,
        ageDays: Long = 5,
        category: String = "games",
        subject: String = "subj-$franchise",
        eventType: String = "major_announcement",
        source: String = "example.com"
    ) = AffinityMath.MessageSignal(
        category = category,
        sentAt = now.minusDays(ageDays),
        subject = subject,
        franchise = franchise,
        eventType = eventType,
        source = source,
        volume = volume,
        valenceSum = valenceSum
    )

    private fun rowsFor(rows: List<metifikys.db.AudienceAffinityRow>, dimension: String, key: String) =
        rows.single { it.dimension == dimension && it.key == key }

    @Test
    fun `positive franchise scores above negative franchise`() {
        val messages =
            List(5) { msg("loved", volume = 20, valenceSum = 20.0) } +      // tone +1
            List(5) { msg("hated", volume = 20, valenceSum = -20.0) } +     // tone -1
            List(5) { msg("meh", volume = 0, valenceSum = 0.0) }            // cohort baseline
        val rows = AffinityMath.aggregate(messages, params())

        val loved = rowsFor(rows, AffinityMath.DIM_FRANCHISE, "loved")
        val hated = rowsFor(rows, AffinityMath.DIM_FRANCHISE, "hated")
        assertTrue(loved.score > 0.0, "loved should be positive, got ${loved.score}")
        assertTrue(hated.score < 0.0, "hated should be negative, got ${hated.score}")
        assertTrue(loved.score > hated.score)
        assertEquals(1.0, loved.sentiment, 1e-9)
        assertEquals(-1.0, hated.sentiment, 1e-9)
    }

    @Test
    fun `shrinkage pulls a single observation toward the prior`() {
        // One glowing post for "rare" vs five for "common", plus negative "downer" posts that
        // drag the category prior below the glowing tone. Scores are centered on the prior,
        // so both stay positive — but rare's n=1 must sit much closer to zero than common's n=5.
        val messages =
            List(5) { msg("common", volume = 20, valenceSum = 20.0) } +
            List(1) { msg("rare", volume = 20, valenceSum = 20.0) } +
            List(6) { msg("downer", volume = 20, valenceSum = -20.0) }
        // minSamples below one decayed message (0.5^(5/45) ≈ 0.93) so "rare" survives the gate
        val rows = AffinityMath.aggregate(messages, params(minSamples = 0.5))

        val common = rowsFor(rows, AffinityMath.DIM_FRANCHISE, "common")
        val rare = rowsFor(rows, AffinityMath.DIM_FRANCHISE, "rare")
        assertTrue(common.score > 0.0 && rare.score > 0.0, "both above the (negative-leaning) prior")
        assertTrue(common.score > rare.score, "n=5 (${common.score}) should beat n=1 (${rare.score})")
    }

    @Test
    fun `low-volume messages carry no tone`() {
        // 2 reactions < minVolume=3 → tone is discarded; the only signal left is the prior 0.
        val messages =
            List(4) { msg("quiet", volume = 2, valenceSum = -2.0) } +
            List(4) { msg("baseline", volume = 0, valenceSum = 0.0) }
        val rows = AffinityMath.aggregate(messages, params())

        val quiet = rowsFor(rows, AffinityMath.DIM_FRANCHISE, "quiet")
        assertEquals(0.0, quiet.score, 1e-9)
        assertEquals(0.0, quiet.sentiment, 1e-9)
    }

    @Test
    fun `time decay discounts old messages`() {
        // "fresh" and "old" carry the same positive tone; "old" posted 90 days ago with
        // halflife 45 → quarter weight. The negative "downer" posts drag the category prior
        // below +1, so old's thinner effective n shrinks it further toward that prior.
        val messages =
            List(3) { msg("fresh", volume = 20, valenceSum = 20.0, ageDays = 0) } +
            List(3) { msg("old", volume = 20, valenceSum = 20.0, ageDays = 90) } +
            List(6) { msg("downer", volume = 20, valenceSum = -20.0, ageDays = 0) }
        // "old"'s decayed n is 0.75 — keep it above the minSamples gate
        val rows = AffinityMath.aggregate(messages, params(minSamples = 0.5))

        val fresh = rowsFor(rows, AffinityMath.DIM_FRANCHISE, "fresh")
        val old = rowsFor(rows, AffinityMath.DIM_FRANCHISE, "old")
        assertEquals(3.0, fresh.n, 1e-6)
        assertEquals(0.75, old.n, 1e-6)          // 3 × 0.5^(90/45)
        // Same positive tone, prior < tone → the smaller effective n shrinks harder.
        assertTrue(fresh.score > old.score, "fresh=${fresh.score} old=${old.score}")
    }

    @Test
    fun `minSamples drops thin keys`() {
        val messages =
            List(5) { msg("thick", volume = 5, valenceSum = 5.0) } +
            List(1) { msg("thin", volume = 5, valenceSum = 5.0) }
        val rows = AffinityMath.aggregate(messages, params(minSamples = 2.0))

        assertTrue(rows.any { it.dimension == AffinityMath.DIM_FRANCHISE && it.key == "thick" })
        assertTrue(rows.none { it.dimension == AffinityMath.DIM_FRANCHISE && it.key == "thin" })
    }

    @Test
    fun `blank dimension values are skipped, keys are normalized to lowercase`() {
        val messages = List(3) {
            msg("  SilkSong  ", volume = 5, valenceSum = 5.0, source = "")
        }
        val rows = AffinityMath.aggregate(messages, params())

        assertTrue(rows.any { it.dimension == AffinityMath.DIM_FRANCHISE && it.key == "silksong" })
        assertTrue(rows.none { it.dimension == AffinityMath.DIM_SOURCE })
    }

    @Test
    fun `z-score is winsorized so one viral post cannot dominate`() {
        // One message with 10_000 reactions among quiet peers: its z must be clamped to Z_CLAMP.
        val messages =
            List(1) { msg("viral", volume = 10_000, valenceSum = 10_000.0) } +
            List(20) { msg("quiet", volume = 1, valenceSum = 1.0) }
        val rows = AffinityMath.aggregate(messages, params(minSamples = 0.5))

        val viral = rowsFor(rows, AffinityMath.DIM_FRANCHISE, "viral")
        assertTrue(viral.engagementZ <= AffinityMath.Z_CLAMP + 1e-9)
        // combined = tone(1.0) × (0.5 + 0.5·sat(3.0)=1.0) = 1.0 → score bounded by shrinkage, not blown up
        assertTrue(abs(viral.score) <= 1.0)
    }

    @Test
    fun `zero std cohort yields zero z for everyone`() {
        val messages = List(4) { msg("flat", volume = 5, valenceSum = 5.0) }
        val rows = AffinityMath.aggregate(messages, params())
        assertEquals(0.0, rowsFor(rows, AffinityMath.DIM_FRANCHISE, "flat").engagementZ, 1e-9)
    }

    @Test
    fun `categories aggregate independently`() {
        val messages =
            List(3) { msg("halo", volume = 10, valenceSum = 10.0, category = "games") } +
            List(3) { msg("halo", volume = 10, valenceSum = -10.0, category = "tech") }
        val rows = AffinityMath.aggregate(messages, params())

        val games = rows.single { it.category == "games" && it.dimension == AffinityMath.DIM_FRANCHISE }
        val tech = rows.single { it.category == "tech" && it.dimension == AffinityMath.DIM_FRANCHISE }
        assertTrue(games.sentiment > 0 && tech.sentiment < 0)
    }

    @Test
    fun `sourceDomain extracts and normalizes host`() {
        assertEquals("example.com", AffinityMath.sourceDomain("https://www.Example.com/a/b?c=1"))
        assertEquals("kotaku.com", AffinityMath.sourceDomain("https://kotaku.com/x"))
        assertEquals("", AffinityMath.sourceDomain(""))
        assertEquals("", AffinityMath.sourceDomain("not a url"))
    }
}
