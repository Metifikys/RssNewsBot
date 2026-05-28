package metifikys.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CoveredEventsTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-covered", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun row(
        category: String = "games",
        key: String,
        coreFact: String = "fact",
        importance: Int = 5,
        url: String = "https://example.com/$key",
        coveredAt: LocalDateTime = LocalDateTime.now()
    ) = CoveredEventRow(
        category = category,
        eventKey = key,
        subject = "subj",
        franchise = "fr",
        eventType = "release",
        coreFact = coreFact,
        importance = importance,
        url = url,
        coveredAt = coveredAt
    )

    @Test
    fun `insert then fetch round trip`() {
        db.insertCoveredEvents(
            listOf(
                row(key = "k1", coveredAt = LocalDateTime.now().minusHours(2)),
                row(key = "k2", coveredAt = LocalDateTime.now().minusHours(1)),
                row(key = "k3", coveredAt = LocalDateTime.now())
            )
        )
        val fetched = db.fetchRecentEvents("games", sinceDays = 7, limit = 10)
        assertEquals(3, fetched.size)
        assertEquals(listOf("k3", "k2", "k1"), fetched.map { it.eventKey })
    }

    @Test
    fun `upsert refreshes coreFact importance url and coveredAt but not subject franchise eventType`() {
        val first = row(key = "k1", coreFact = "old", importance = 3, url = "https://x/1",
            coveredAt = LocalDateTime.now().minusDays(1))
        db.insertCoveredEvents(listOf(first))

        val second = CoveredEventRow(
            category = "games",
            eventKey = "k1",
            subject = "NEW-SUBJECT",       // should NOT be refreshed
            franchise = "NEW-FR",          // should NOT be refreshed
            eventType = "NEW-TYPE",        // should NOT be refreshed
            coreFact = "new",
            importance = 9,
            url = "https://x/2",
            coveredAt = LocalDateTime.now()
        )
        db.insertCoveredEvents(listOf(second))

        val all = db.fetchRecentEvents("games", sinceDays = 30, limit = 10)
        assertEquals(1, all.size)  // upsert, not insert
        val r = all.single()
        assertEquals("new", r.coreFact)
        assertEquals(9, r.importance)
        assertEquals("https://x/2", r.url)
        // descriptive fields preserved from the original row
        assertEquals("subj", r.subject)
        assertEquals("fr", r.franchise)
        assertEquals("release", r.eventType)
    }

    @Test
    fun `unique index is per category so same key in different categories coexists`() {
        db.insertCoveredEvents(
            listOf(
                row(category = "games", key = "shared"),
                row(category = "tech", key = "shared")
            )
        )
        assertEquals(1, db.fetchRecentEvents("games", 7, 10).size)
        assertEquals(1, db.fetchRecentEvents("tech", 7, 10).size)
    }

    @Test
    fun `fetchRecentEvents respects sinceDays window`() {
        db.insertCoveredEvents(
            listOf(
                row(key = "recent", coveredAt = LocalDateTime.now().minusDays(5)),
                row(key = "old", coveredAt = LocalDateTime.now().minusDays(20))
            )
        )
        val r = db.fetchRecentEvents("games", sinceDays = 7, limit = 10)
        assertEquals(listOf("recent"), r.map { it.eventKey })
    }

    @Test
    fun `fetchRecentEvents respects limit`() {
        val now = LocalDateTime.now()
        val rows = (1..25).map { row(key = "k$it", coveredAt = now.minusMinutes(it.toLong())) }
        db.insertCoveredEvents(rows)

        val result = db.fetchRecentEvents("games", sinceDays = 30, limit = 10)
        assertEquals(10, result.size)
        assertEquals("k1", result.first().eventKey)  // newest (1 minute ago)
    }

    @Test
    fun `fetchRecentEvents returns empty when limit is zero`() {
        db.insertCoveredEvents(listOf(row(key = "k1")))
        assertTrue(db.fetchRecentEvents("games", 7, 0).isEmpty())
    }

    @Test
    fun `pruneOldCoveredEvents deletes rows older than retention`() {
        // Insert two rows with distinct covered_at values via UPDATE since LocalDateTime.now() in
        // insertCoveredEvents would overwrite. We seed both and then back-date one.
        db.insertCoveredEvents(listOf(row(key = "old"), row(key = "young")))
        transaction {
            CoveredEventsTable.update({ CoveredEventsTable.eventKey eq "old" }) {
                it[coveredAt] = LocalDateTime.now().minusDays(30)
            }
            CoveredEventsTable.update({ CoveredEventsTable.eventKey eq "young" }) {
                it[coveredAt] = LocalDateTime.now().minusDays(5)
            }
        }

        db.pruneOldCoveredEvents(retentionDays = 14)

        val all = db.fetchRecentEvents("games", sinceDays = 60, limit = 10)
        assertEquals(listOf("young"), all.map { it.eventKey })
    }

    @Test
    fun `insertCoveredEvents is a no-op on empty list`() {
        db.insertCoveredEvents(emptyList())
        assertTrue(db.fetchRecentEvents("games", 7, 10).isEmpty())
    }

    @Test
    fun `savePendingBatch round-trips shortlistJson`() {
        val shortlistJson = """[{"eventKey":"k","coreFact":"x","url":"u","status":"new"}]"""
        db.savePendingBatch("b1", 0, 1, "games", "https://a/1", shortlistJson)
        val rec = db.fetchPendingBatches().single { it.batchId == "b1" }
        assertEquals(shortlistJson, rec.shortlistJson)
    }

    @Test
    fun `savePendingBatch persists null shortlistJson for legacy flow`() {
        db.savePendingBatch("b-legacy", 0, 1, "games", "https://a/1", shortlistJson = null)
        val rec = db.fetchPendingBatches().single { it.batchId == "b-legacy" }
        assertEquals(null, rec.shortlistJson)
    }

    @Test
    fun `upsert does not create duplicate rows across repeated calls`() {
        val r = row(key = "k-dup")
        db.insertCoveredEvents(listOf(r, r, r))
        // first call UPDATE (0 rows) → INSERT, subsequent UPDATE (1 row) → no insert
        val all = db.fetchRecentEvents("games", 30, 10)
        assertEquals(1, all.size)
        assertNotEquals(0, all.single().eventKey.length)
    }
}
