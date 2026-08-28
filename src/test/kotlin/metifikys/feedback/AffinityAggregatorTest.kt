package metifikys.feedback

import metifikys.config.FeedbackConfig
import metifikys.db.CoveredEventRow
import metifikys.db.DigestMessageRow
import metifikys.db.NewsDatabase
import metifikys.db.ReactionCount
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReactionValenceTest {

    @Test
    fun `defaults map strong emoji and unknown falls back to neutral`() {
        val v = ReactionValence()
        assertEquals(1.0, v.of("🔥"))
        assertEquals(-1.0, v.of("💩"))
        assertEquals(1.0, v.of("paid"))
        assertEquals(0.0, v.of("custom:12345"))
        assertEquals(0.0, v.of("🦖"))
    }

    @Test
    fun `config overrides beat the defaults`() {
        val v = ReactionValence(mapOf("🤡" to 1.0, "custom:777" to 0.5))
        assertEquals(1.0, v.of("🤡"))
        assertEquals(0.5, v.of("custom:777"))
        assertEquals(1.0, v.of("🔥"))   // untouched default
    }
}

class AffinityAggregatorTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File
    private val now: LocalDateTime = LocalDateTime.now()

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-affinity", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    @Test
    fun `an audience_affinity table predating n_tone is migrated in place`() {
        // Production already has this table without the column; the schema sync must ADD it
        // rather than leave replaceAudienceAffinity writing to a column that does not exist.
        val legacyFile = File.createTempFile("test-affinity-legacy", ".db")
        try {
            java.sql.DriverManager.getConnection("jdbc:sqlite:${legacyFile.absolutePath}").use { c ->
                c.createStatement().use {
                    it.executeUpdate(
                        """
                        CREATE TABLE audience_affinity (
                            category VARCHAR(100) NOT NULL, dimension VARCHAR(30) NOT NULL,
                            "key" VARCHAR(500) NOT NULL, n DOUBLE PRECISION NOT NULL,
                            engagement_z DOUBLE PRECISION NOT NULL, sentiment DOUBLE PRECISION NOT NULL,
                            score DOUBLE PRECISION NOT NULL, updated_at TEXT NOT NULL,
                            CONSTRAINT pk_audience_affinity PRIMARY KEY (category, dimension, "key")
                        )
                        """.trimIndent()
                    )
                    it.executeUpdate(
                        "INSERT INTO audience_affinity VALUES " +
                            "('games','franchise','legacy',7.0,0.5,0.6,0.3,'2026-08-01 00:00:00.000')"
                    )
                }
            }

            val migrated = NewsDatabase(legacyFile.absolutePath)
            val existing = migrated.fetchAudienceAffinity().single()
            assertEquals("legacy", existing.key)
            assertEquals(0.0, existing.nTone, "pre-existing rows default to 0.0")

            val row = metifikys.db.AudienceAffinityRow(
                category = "games", dimension = "franchise", key = "fresh",
                n = 4.0, nTone = 2.5, engagementZ = 0.1, sentiment = 0.7, score = 0.2,
                updatedAt = now.withNano(0)
            )
            migrated.replaceAudienceAffinity(listOf(row))
            assertEquals(listOf(row), migrated.fetchAudienceAffinity())
        } finally {
            legacyFile.delete()
            // Re-point Exposed at this test's own database — NewsDatabase connects globally.
            db = NewsDatabase(dbFile.absolutePath)
        }
    }

    private fun feedback(enabled: Boolean = true, minVolume: Int = 3) = FeedbackConfig(
        enabled = enabled,
        attributionHours = 36,
        lookbackDays = 90,
        minSamples = 0.5,
        minVolume = minVolume,
        recomputeHours = 12
    )

    /** One delivered message with dims + reactions, [ageDays] old (past the attribution gate). */
    private fun seedMessage(
        messageId: Long,
        franchise: String,
        reactions: List<ReactionCount>,
        ageDays: Long = 3,
        category: String = "games",
        eventKey: String = "evt_$messageId"
    ) {
        val sentAt = now.minusDays(ageDays)
        db.insertCoveredEvents(listOf(CoveredEventRow(
            category = category, eventKey = eventKey, subject = "subject $messageId",
            franchise = franchise, eventType = "major_announcement", coreFact = "fact",
            importance = 5, url = "https://www.example.com/a$messageId", coveredAt = sentAt
        )))
        db.insertDigestMessages(listOf(DigestMessageRow(
            chatId = -100L, messageId = messageId, category = category,
            eventKey = eventKey, articleLinks = "", sentAt = sentAt
        )))
        if (reactions.isNotEmpty()) db.replaceReactionCounts(-100L, messageId, reactions)
    }

    @Test
    fun `recompute aggregates reactions into affinity rows and stamps state`() {
        repeat(4) { i ->
            seedMessage(10L + i, "silksong", listOf(ReactionCount("🔥", 10)))
        }
        repeat(4) { i ->
            seedMessage(20L + i, "fifa", listOf(ReactionCount("💩", 10)))
        }
        repeat(4) { i ->
            seedMessage(30L + i, "quiet game", emptyList())
        }

        AffinityAggregator(feedback(), db).recomputeIfStale()

        val rows = db.fetchAudienceAffinity()
        val silksong = rows.single { it.dimension == "franchise" && it.key == "silksong" }
        val fifa = rows.single { it.dimension == "franchise" && it.key == "fifa" }
        assertTrue(silksong.score > 0.0, "silksong should be positive, got ${silksong.score}")
        assertTrue(fifa.score < silksong.score, "fifa (${fifa.score}) should sit below silksong")
        assertTrue(rows.any { it.dimension == "source" && it.key == "example.com" })
        assertTrue(db.getState(AffinityAggregator.STATE_KEY) != null, "state timestamp must be stamped")
    }

    @Test
    fun `messages younger than attributionHours are excluded`() {
        seedMessage(1L, "fresh", listOf(ReactionCount("🔥", 50)), ageDays = 0)
        AffinityAggregator(feedback(), db).recomputeIfStale()
        assertTrue(db.fetchAudienceAffinity().none { it.key == "fresh" })
    }

    @Test
    fun `second run within recomputeHours is a no-op`() {
        seedMessage(1L, "silksong", listOf(ReactionCount("🔥", 10)))
        val agg = AffinityAggregator(feedback(), db)
        agg.recomputeIfStale()
        val stamp = db.getState(AffinityAggregator.STATE_KEY)

        // New data arrives, but the gate must hold until recomputeHours pass.
        seedMessage(2L, "newgame", listOf(ReactionCount("🔥", 10)))
        agg.recomputeIfStale()

        assertEquals(stamp, db.getState(AffinityAggregator.STATE_KEY))
        assertTrue(db.fetchAudienceAffinity().none { it.key == "newgame" })
    }

    @Test
    fun `changed feedback config forces an immediate recompute`() {
        seedMessage(1L, "silksong", listOf(ReactionCount("🔥", 10)))
        AffinityAggregator(feedback(), db).recomputeIfStale()

        // Same data, new parameters (different minVolume) → the 12h gate must NOT hold.
        seedMessage(2L, "newgame", listOf(ReactionCount("🔥", 10)))
        AffinityAggregator(feedback(minVolume = 1), db).recomputeIfStale()

        assertTrue(db.fetchAudienceAffinity().any { it.key == "newgame" })
    }

    @Test
    fun `single-reader channel - lone dislikes turn a franchise negative at minVolume 1`() {
        // The production shape: every post carries exactly one reaction from the operator.
        repeat(5) { i -> seedMessage(10L + i, "fifa", listOf(ReactionCount("👎", 1))) }
        repeat(5) { i -> seedMessage(20L + i, "silksong", listOf(ReactionCount("👍", 1))) }
        repeat(5) { i -> seedMessage(30L + i, "quiet", emptyList()) }

        AffinityAggregator(feedback(minVolume = 1), db).recomputeIfStale()

        val rows = db.fetchAudienceAffinity()
        val fifa = rows.single { it.dimension == "franchise" && it.key == "fifa" }
        val silksong = rows.single { it.dimension == "franchise" && it.key == "silksong" }
        assertTrue(fifa.score < 0.0, "disliked franchise must go negative, got ${fifa.score}")
        assertTrue(silksong.score > 0.0, "liked franchise must go positive, got ${silksong.score}")
        assertTrue(fifa.sentiment < 0.0 && silksong.sentiment > 0.0)
    }

    @Test
    fun `disabled feedback computes nothing`() {
        seedMessage(1L, "silksong", listOf(ReactionCount("🔥", 10)))
        AffinityAggregator(feedback(enabled = false), db).recomputeIfStale()
        assertTrue(db.fetchAudienceAffinity().isEmpty())
        assertEquals(null, db.getState(AffinityAggregator.STATE_KEY))
    }

    @Test
    fun `legacy messages without event key stay out of the learning set`() {
        db.insertDigestMessages(listOf(DigestMessageRow(
            chatId = -100L, messageId = 99L, category = "games",
            eventKey = null, articleLinks = "https://x.com/1", sentAt = now.minusDays(3)
        )))
        db.replaceReactionCounts(-100L, 99L, listOf(ReactionCount("🔥", 10)))

        AffinityAggregator(feedback(), db).recomputeIfStale()

        assertTrue(db.fetchAudienceAffinity().isEmpty())
    }

    @Test
    fun `fetchAffinityInputs joins dims and keeps zero-reaction messages`() {
        seedMessage(1L, "silksong", listOf(ReactionCount("🔥", 3), ReactionCount("👍", 2)))
        seedMessage(2L, "fifa", emptyList())

        val inputs = db.fetchAffinityInputs(olderThanHours = 36, lookbackDays = 90)

        assertEquals(2, inputs.size)
        val reacted = inputs.single { it.messageId == 1L }
        assertEquals("silksong", reacted.franchise)
        assertEquals(5, reacted.reactions.sumOf { it.count })
        val quiet = inputs.single { it.messageId == 2L }
        assertTrue(quiet.reactions.isEmpty())
    }

    @Test
    fun `replace and fetch audience affinity round-trips`() {
        val row = metifikys.db.AudienceAffinityRow(
            category = "games", dimension = "franchise", key = "silksong",
            n = 3.5, nTone = 1.8, engagementZ = 1.2, sentiment = 0.8, score = 0.42,
            updatedAt = now.withNano(0)
        )
        db.replaceAudienceAffinity(listOf(row))
        assertEquals(listOf(row), db.fetchAudienceAffinity())

        // replace-all semantics: a second write with a different set drops the first row
        db.replaceAudienceAffinity(listOf(row.copy(key = "fifa", score = -0.3)))
        val after = db.fetchAudienceAffinity()
        assertEquals(1, after.size)
        assertEquals("fifa", after.single().key)
    }
}
