package metifikys.db

import metifikys.model.Article
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EventArticlesTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-event-articles", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private val t0 = LocalDateTime.of(2026, 9, 1, 12, 0)

    private fun row(
        id: Int,
        key: String,
        category: String = "games",
        role: String = EventArticleRole.STEP1,
        recordedAt: LocalDateTime = LocalDateTime.now()
    ) = EventArticleRow(
        category = category, articleId = id, eventKey = key, link = "https://s$id.com/a", domain = "s$id.com",
        pubDate = t0, seenAt = t0.plusMinutes(1), role = role, step1Status = "new", recordedAt = recordedAt
    )

    @Test
    fun `upsert - the latest decision about an article wins`() {
        db.upsertEventArticles(listOf(row(1, "old-key"), row(2, "old-key")))
        db.upsertEventArticles(listOf(row(1, "new-key").copy(step1Status = "duplicate")))

        val clusters = db.fetchEventClusters("games", listOf("old-key", "new-key"))
        assertEquals(listOf(2), clusters["old-key"]!!.map { it.articleId })
        val moved = clusters["new-key"]!!.single()
        assertEquals(1, moved.articleId)
        assertEquals("duplicate", moved.step1Status)
        assertEquals(t0.plusMinutes(1), moved.seenAt)
    }

    @Test
    fun `the same article id is a separate row per category`() {
        db.upsertEventArticles(listOf(row(1, "k", category = "games"), row(1, "k", category = "tech")))
        assertEquals(1, db.fetchEventClusters("games", listOf("k"))["k"]!!.size)
        assertEquals(1, db.fetchEventClusters("tech", listOf("k"))["k"]!!.size)
    }

    @Test
    fun `clusters by key - an update sees its related event`() {
        db.upsertEventArticles(listOf(row(1, "orig"), row(2, "orig"), row(3, "upd"), row(4, "other")))
        val rows = db.fetchEventClusters("games", listOf("upd", "orig"))
        val cluster = metifikys.story.EventCluster.of("upd", rows, listOf("orig"))
        assertEquals(setOf(1, 2, 3), cluster.members.map { it.articleId }.toSet())
        assertEquals(3, cluster.outlets)
    }

    @Test
    fun `event keys by article id, reassign and retention`() {
        db.upsertEventArticles(listOf(row(1, "dropped"), row(2, "dropped"), row(3, "kept", recordedAt = LocalDateTime.now().minusDays(200))))
        assertEquals(mapOf(1 to "dropped"), db.fetchEventKeysByArticleIds("games", listOf(1, 99)))

        assertEquals(2, db.reassignEventArticles("games", "dropped", "covered", EventArticleRole.LAYER35))
        val covered = db.fetchEventClusters("games", listOf("covered"))["covered"]!!
        assertTrue(covered.all { it.role == EventArticleRole.LAYER35 })

        db.deleteOldEventArticles(180)
        assertTrue(db.fetchEventClusters("games", listOf("kept")).isEmpty())
        assertEquals(2, db.fetchEventArticlesSince(null, LocalDateTime.now().minusDays(1)).size)
        assertEquals(0, db.fetchEventArticlesSince("tech", LocalDateTime.now().minusDays(1)).size)
    }

    @Test
    fun `article stamps carry the id, feed date and first-ingest time`() {
        val pub = LocalDateTime.of(2026, 8, 30, 9, 0)
        db.insertArticles(listOf(Article(category = "games", title = "t", link = "https://x.com/1", description = "d", pubDate = pub)))
        val stamp = db.fetchArticleStamps(listOf("https://x.com/1", "https://x.com/missing")).values.single()
        assertEquals(pub, stamp.pubDate)
        assertNotNull(stamp.fetchedAt)
        assertEquals(db.fetchArticleIdsByLinks(listOf("https://x.com/1"))["https://x.com/1"], stamp.id)
    }
}
