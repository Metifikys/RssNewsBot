package metifikys.db

import metifikys.model.Article
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeedStatsStoreTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-feedstats", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private val t0 = LocalDateTime.of(2026, 9, 20, 10, 0)

    private fun delta(passes: Int, failures: Int, success: LocalDateTime?, sinceSuccess: Int, err: String? = null) =
        FeedStatsDelta(
            "https://f", "tech", passes, failures, t0.plusHours(passes.toLong()), success, sinceSuccess, err,
            if (success != null) 7 else null
        )

    @Test
    fun `flushes accumulate totals and carry the fail streak across flushes`() {
        db.applyFeedStats(listOf(delta(3, 1, t0, 0)))
        db.applyFeedStats(listOf(delta(2, 2, null, 2, "HTTP 500")))
        db.applyFeedStats(listOf(delta(1, 1, null, 1, "HTTP 502")))
        var row = db.fetchFeedStats().single()
        assertEquals(6, row.fetchesTotal)
        assertEquals(4, row.failuresTotal)
        assertEquals(3, row.failStreak)
        assertEquals(t0, row.lastSuccessAt)
        assertEquals("HTTP 502", row.lastError)
        assertEquals(7, row.lastEntries)

        db.applyFeedStats(listOf(delta(1, 0, t0.plusDays(1), 0)))
        row = db.fetchFeedStats().single()
        assertEquals(0, row.failStreak)
        assertEquals(t0.plusDays(1), row.lastSuccessAt)
    }

    @Test
    fun `outcome snapshot joins the Step 1 verdict and skips rows without a feed`() {
        db.insertArticles(listOf(
            Article("tech", "t", "https://a/1", "d", feedUrl = "F"),
            Article("tech", "t", "https://a/2", "d", feedUrl = "F"),
            Article("tech", "t", "https://a/3", "d")
        ))
        db.saveRejectedEvents(listOf(
            RejectedEventRow(category = "tech", eventKey = "k1", coreFact = "fact", url = "https://a/1",
                status = "rejected", extractedAt = LocalDateTime.now())
        ))
        val rows = db.fetchFeedArticleOutcomes(LocalDateTime.now().minusDays(1)).associateBy { it.link }
        assertEquals(setOf("https://a/1", "https://a/2"), rows.keys)
        assertEquals("rejected", rows.getValue("https://a/1").step1)
        assertNull(rows.getValue("https://a/2").step1)
        assertNull(rows.getValue("https://a/1").junkScore)
        assertEquals(setOf("F"), db.fetchFeedLastNew().keys)
        assertNotNull(db.fetchFeedTrackingSince())
    }

    @Test
    fun `the junk-score hook reads the column once it exists`() {
        db.insertArticles(listOf(Article("tech", "t", "https://a/1", "d", feedUrl = "F")))
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { it.executeUpdate("ALTER TABLE articles ADD COLUMN ${FeedStore.JUNK_SCORE_COLUMN} REAL") }
            c.createStatement().use { it.executeUpdate("UPDATE articles SET ${FeedStore.JUNK_SCORE_COLUMN} = 0.75") }
        }
        val row = db.fetchFeedArticleOutcomes(LocalDateTime.now().minusDays(1)).single()
        assertEquals(0.75, row.junkScore)
    }

    @Test
    fun `published link groups count a chunked post once`() {
        val at = LocalDateTime.now().minusHours(1)
        db.insertDigestMessages(listOf(
            DigestMessageRow(-1, 1, "tech", "e1", "https://a/1\nhttps://a/2", at),
            DigestMessageRow(-1, 2, "tech", "e1", "https://a/1\nhttps://a/2", at),
            DigestMessageRow(-1, 3, "tech", "e2", "https://a/3", at),
            DigestMessageRow(-1, 4, "tech", "e3", "https://a/4", at.minusDays(30))
        ))
        assertEquals(
            setOf(setOf("https://a/1", "https://a/2"), setOf("https://a/3")),
            db.fetchPublishedLinkGroupsSince(LocalDateTime.now().minusDays(1)).toSet()
        )
    }
}
