package metifikys.db

import metifikys.model.Article
import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewsDatabaseTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-news", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun article(link: String, category: String = "tech") = Article(
        category = category,
        title = "Title for $link",
        link = link,
        description = "Some description",
        pubDate = LocalDateTime.now()
    )

    @Test
    fun `insertArticles inserts new articles and returns count`() {
        val articles = listOf(article("https://example.com/1"), article("https://example.com/2"))
        val count = db.insertArticles(articles)
        assertEquals(2, count)
    }

    @Test
    fun `insertArticles deduplicates by link`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))
        val count = db.insertArticles(listOf(a))
        assertEquals(0, count)
    }

    @Test
    fun `default insert status is UNPROCESSED`() {
        db.insertArticles(listOf(article("https://example.com/1")))

        val ready = db.fetchReadyForDigestByCategory()

        assertEquals(1, ready["tech"]?.size)
    }

    @Test
    fun `markProcessing excludes fresh rows from ready pool`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))
        db.markProcessing(listOf(a.link))

        val ready = db.fetchReadyForDigestByCategory(staleTimeoutHours = 3)

        assertTrue(ready.isEmpty())
    }

    @Test
    fun `stale PROCESSING rows are reclaimable after cutoff`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))
        db.markProcessing(listOf(a.link))

        transaction {
            ArticlesTable.update({ ArticlesTable.link eq a.link }) {
                it[processingStartedAt] = LocalDateTime.now().minusHours(5)
            }
        }

        val ready = db.fetchReadyForDigestByCategory(staleTimeoutHours = 1)

        assertEquals(1, ready["tech"]?.size)
    }

    @Test
    fun `reclaimOrphanedProcessing resets PROCESSING rows except those still owned by a batch`() {
        val a = article("https://example.com/1")
        val b = article("https://example.com/2")
        val c = article("https://example.com/3")
        db.insertArticles(listOf(a, b, c))
        db.markProcessing(listOf(a.link, b.link))

        val reclaimed = db.reclaimOrphanedProcessing(keepLinks = listOf(b.link))

        assertEquals(1, reclaimed)
        val ready = db.fetchReadyForDigestByCategory(staleTimeoutHours = 3)["tech"].orEmpty().map { it.link }
        assertEquals(listOf(a.link, c.link).sorted(), ready.sorted())
        assertEquals(listOf(b.link), db.fetchProcessingByCategory("tech").map { it.link })
    }

    @Test
    fun `reclaimOrphanedProcessing with no pending batches resets every PROCESSING row`() {
        val a = article("https://example.com/1")
        val b = article("https://example.com/2")
        db.insertArticles(listOf(a, b))
        db.markProcessing(listOf(a.link, b.link))

        assertEquals(2, db.reclaimOrphanedProcessing())
        assertEquals(2, db.fetchReadyForDigestByCategory(staleTimeoutHours = 3)["tech"]?.size)
    }

    @Test
    fun `markUnprocessed returns rows to ready pool`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))
        db.markProcessing(listOf(a.link))

        db.markUnprocessed(listOf(a.link))

        val ready = db.fetchReadyForDigestByCategory()
        assertEquals(1, ready["tech"]?.size)
    }

    @Test
    fun `markProcessed permanently removes rows from ready pool`() {
        val a = article("https://example.com/1")
        db.insertArticles(listOf(a))

        db.markProcessed(listOf(a.link))
        db.markUnprocessed(listOf(a.link))

        val ready = db.fetchReadyForDigestByCategory()
        assertTrue(ready.isEmpty())
    }

    @Test
    fun `deleteOlderThan removes old articles`() {
        val oldArticle = Article(
            category = "tech",
            title = "Old",
            link = "https://example.com/old",
            description = "old",
            pubDate = LocalDateTime.now().minusDays(10)
        )
        db.insertArticles(listOf(oldArticle))
        db.deleteOlderThan(7)
        val result = db.fetchReadyForDigestByCategory()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `migration from legacy processed column maps processed rows to PROCESSED`() {
        val legacyFile = File.createTempFile("legacy-news", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${legacyFile.absolutePath}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE articles (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          category VARCHAR(100) NOT NULL,
                          title VARCHAR(1000) NOT NULL,
                          link VARCHAR(2000) NOT NULL UNIQUE,
                          description TEXT NOT NULL,
                          pub_date DATETIME NOT NULL,
                          processed BOOLEAN NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        INSERT INTO articles(category, title, link, description, pub_date, processed)
                        VALUES ('tech', 'Done', 'https://example.com/done', 'desc', datetime('now'), 1)
                        """.trimIndent()
                    )
                    stmt.execute(
                        """
                        INSERT INTO articles(category, title, link, description, pub_date, processed)
                        VALUES ('tech', 'Todo', 'https://example.com/todo', 'desc', datetime('now'), 0)
                        """.trimIndent()
                    )
                }
            }

            val migratedDb = NewsDatabase(legacyFile.absolutePath)
            val readyLinks = migratedDb.fetchReadyForDigestByCategory()["tech"]?.map { it.link } ?: emptyList()

            assertTrue(readyLinks.contains("https://example.com/todo"))
            assertFalse(readyLinks.contains("https://example.com/done"))
        } finally {
            legacyFile.delete()
        }
    }

    @Test
    fun `imageUrl round-trips through insert and fetch`() {
        val withImage = Article(
            category = "tech",
            title = "T",
            link = "https://example.com/img",
            description = "d",
            pubDate = LocalDateTime.now(),
            imageUrl = "https://cdn.example.com/p.jpg"
        )
        val withoutImage = Article(
            category = "tech",
            title = "T2",
            link = "https://example.com/no-img",
            description = "d",
            pubDate = LocalDateTime.now()
        )
        db.insertArticles(listOf(withImage, withoutImage))

        val byLink = db.fetchReadyForDigestByCategory()["tech"].orEmpty().associateBy { it.link }
        assertEquals("https://cdn.example.com/p.jpg", byLink["https://example.com/img"]?.imageUrl)
        assertEquals(null, byLink["https://example.com/no-img"]?.imageUrl)
    }

    // ── findExistingLinks tests ──────────────────────────────────────────────

    @Test
    fun `findExistingLinks returns links that exist in DB`() {
        db.insertArticles(listOf(article("https://example.com/1"), article("https://example.com/2")))
        val existing = db.findExistingLinks(listOf(
            "https://example.com/1",
            "https://example.com/2",
            "https://example.com/3"
        ))
        assertEquals(setOf("https://example.com/1", "https://example.com/2"), existing)
    }

    @Test
    fun `findExistingLinks returns empty set for no matches`() {
        val existing = db.findExistingLinks(listOf("https://example.com/999"))
        assertTrue(existing.isEmpty())
    }

    @Test
    fun `findExistingLinks handles empty input`() {
        val existing = db.findExistingLinks(emptyList())
        assertTrue(existing.isEmpty())
    }

    // ── Summary history tests ─────────────────────────────────────────────────

    @Test
    fun `saveSummary and fetchRecentSummaries returns summaries newest-first`() {
        db.saveSummary("tech", "First summary")
        Thread.sleep(50) // ensure distinct timestamps
        db.saveSummary("tech", "Second summary")
        Thread.sleep(50)
        db.saveSummary("tech", "Third summary")

        val results = db.fetchRecentSummaries("tech", 2)
        assertEquals(2, results.size)
        assertEquals("Third summary", results[0].summary)
        assertEquals("Second summary", results[1].summary)
    }

    @Test
    fun `fetchRecentSummaries isolates by category`() {
        db.saveSummary("tech", "Tech summary")
        db.saveSummary("gaming", "Gaming summary")

        val techResults = db.fetchRecentSummaries("tech", 10)
        assertEquals(1, techResults.size)
        assertEquals("Tech summary", techResults[0].summary)
    }

    @Test
    fun `fetchRecentSummaries returns empty list when no summaries exist`() {
        val results = db.fetchRecentSummaries("tech", 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `countPendingBatchesForCategory counts only pending rows for that category`() {
        db.savePendingBatch("batch-1", 0, 1, "tech")
        db.savePendingBatch("batch-2", 0, 1, "tech")
        db.savePendingBatch("batch-3", 0, 1, "tech")
        db.savePendingBatch("batch-4", 0, 1, "gaming")
        db.updateBatchStatus("batch-2", "completed")

        assertEquals(2, db.countPendingBatchesForCategory("tech"))
        assertEquals(1, db.countPendingBatchesForCategory("gaming"))
        assertEquals(0, db.countPendingBatchesForCategory("news"))
    }

    @Test
    fun `fetchArticlesByLinks returns rows in the order of the requested links`() {
        // Step 1 articleIndices are positional into the prompt's article list, and a resumed
        // extract batch rebuilds that list from its saved links — SQLite order must not leak through.
        db.insertArticles(listOf(
            article("https://example.com/b"),
            article("https://example.com/c"),
            article("https://example.com/a")
        ))
        val requested = listOf("https://example.com/c", "https://example.com/a", "https://example.com/b")
        assertEquals(requested, db.fetchArticlesByLinks(requested).map { it.link })
    }

    @Test
    fun `fetchArticlesByLinks returns only the requested articles`() {
        db.insertArticles(listOf(
            article("https://example.com/1"),
            article("https://example.com/2"),
            article("https://example.com/3")
        ))
        val result = db.fetchArticlesByLinks(listOf("https://example.com/1", "https://example.com/3"))
        assertEquals(2, result.size)
        assertEquals(setOf("https://example.com/1", "https://example.com/3"), result.map { it.link }.toSet())
    }

    @Test
    fun `savePendingBatch articleLinks round-trips through fetchPendingBatches`() {
        val links = listOf("https://example.com/a", "https://example.com/b").joinToString("\n")
        db.savePendingBatch("batch-rt", 0, 1, "tech", links)
        val batch = db.fetchPendingBatches().single { it.batchId == "batch-rt" }
        assertEquals(links, batch.articleLinks)
    }

    @Test
    fun `deleteOldSummaries preserves recent summaries`() {
        db.saveSummary("tech", "Recent summary")
        db.deleteOldSummaries(14)

        val results = db.fetchRecentSummaries("tech", 10)
        assertEquals(1, results.size)
    }

    // ── Digest messages & reactions ──────────────────────────────────────────

    private fun digestMsg(chatId: Long, messageId: Long, category: String = "tech", eventKey: String? = "evt-$messageId") =
        DigestMessageRow(chatId, messageId, category, eventKey, "https://a.com/$messageId", LocalDateTime.now())

    @Test
    fun `fetchReactionSummary aggregates totals, post count, and top emoji per category`() {
        db.insertDigestMessages(listOf(digestMsg(-100L, 1, "tech"), digestMsg(-100L, 2, "tech")))
        db.replaceReactionCounts(-100L, 1, listOf(ReactionCount("👍", 5), ReactionCount("❤️", 2)))
        db.replaceReactionCounts(-100L, 2, listOf(ReactionCount("👍", 3)))

        val summary = db.fetchReactionSummary(24).getValue("tech")
        assertEquals(10L, summary.totalReactions)          // 5 + 2 + 3
        assertEquals(2L, summary.postCount)                // two distinct messages
        assertEquals(ReactionCount("👍", 8), summary.byEmoji.first())  // top emoji, merged across posts
    }

    @Test
    fun `replaceReactionCounts replaces the whole set, not increments`() {
        db.insertDigestMessages(listOf(digestMsg(-100L, 1)))
        db.replaceReactionCounts(-100L, 1, listOf(ReactionCount("👍", 5), ReactionCount("👎", 2)))
        // A later update drops 👎 entirely and lowers 👍 (people removed reactions).
        db.replaceReactionCounts(-100L, 1, listOf(ReactionCount("👍", 4)))

        val summary = db.fetchReactionSummary(24).getValue("tech")
        assertEquals(4L, summary.totalReactions)
        assertEquals(listOf(ReactionCount("👍", 4)), summary.byEmoji)
    }

    @Test
    fun `replaceReactionCounts with empty list clears the message`() {
        db.insertDigestMessages(listOf(digestMsg(-100L, 1)))
        db.replaceReactionCounts(-100L, 1, listOf(ReactionCount("👍", 1)))
        db.replaceReactionCounts(-100L, 1, emptyList())

        assertTrue(db.fetchReactionSummary(24).isEmpty())
    }

    @Test
    fun `fetchReactionSummary ignores reactions on messages we did not send`() {
        // No digest_messages row for (-100, 99) → its reactions are not attributable to a category.
        db.replaceReactionCounts(-100L, 99, listOf(ReactionCount("👍", 7)))
        assertTrue(db.fetchReactionSummary(24).isEmpty())
    }

    @Test
    fun `getState and setState round-trip and upsert`() {
        assertEquals(null, db.getState("updates_offset"))
        db.setState("updates_offset", "42")
        assertEquals("42", db.getState("updates_offset"))
        db.setState("updates_offset", "43")
        assertEquals("43", db.getState("updates_offset"))
    }

    @Test
    fun `deleteOldReactionCounts preserves recent rows`() {
        db.insertDigestMessages(listOf(digestMsg(-100L, 1)))
        db.replaceReactionCounts(-100L, 1, listOf(ReactionCount("👍", 1)))
        db.deleteOldReactionCounts(90)
        assertEquals(1L, db.fetchReactionSummary(24).getValue("tech").totalReactions)
    }

    @Test
    fun `badge cache round-trips, expires by age, and prunes`() {
        assertEquals(null, db.getBadgeCache("steam", "silksong", java.time.Duration.ofHours(1)))
        db.putBadgeCache("steam", "silksong", "🎮 Steam: 719₴")
        db.putBadgeCache("steam", "doom", "")   // negative
        assertEquals("🎮 Steam: 719₴", db.getBadgeCache("steam", "silksong", java.time.Duration.ofHours(1)))
        assertEquals("", db.getBadgeCache("steam", "doom", java.time.Duration.ofHours(1)))
        assertEquals(null, db.getBadgeCache("hn", "silksong", java.time.Duration.ofHours(1)), "namespaced by provider")

        db.putBadgeCache("steam", "silksong", "🎮 Steam: 899₴")   // upsert
        assertEquals("🎮 Steam: 899₴", db.getBadgeCache("steam", "silksong", java.time.Duration.ofHours(1)))

        // Age the row past the TTL: expired for a 1h maxAge, gone after prune.
        transaction {
            BadgeCacheTable.update({ BadgeCacheTable.cacheKey eq "silksong" }) {
                it[fetchedAt] = LocalDateTime.now().minusDays(10)
            }
        }
        assertEquals(null, db.getBadgeCache("steam", "silksong", java.time.Duration.ofHours(1)))
        assertEquals("🎮 Steam: 899₴", db.getBadgeCache("steam", "silksong", java.time.Duration.ofDays(30)))
        db.deleteOldBadgeCache(7)
        assertEquals(null, db.getBadgeCache("steam", "silksong", java.time.Duration.ofDays(30)))
        assertEquals("", db.getBadgeCache("steam", "doom", java.time.Duration.ofHours(1)), "fresh rows survive the prune")
    }

    @Test
    fun `countDigestPostsByEventKeys counts deliveries, not chunks, within the window`() {
        val d1 = LocalDateTime.now().minusDays(5)
        val d2 = LocalDateTime.now().minusDays(1)
        db.insertDigestMessages(listOf(
            DigestMessageRow(-100L, 1L, "tech", "evt-a", "", d1),
            DigestMessageRow(-100L, 2L, "tech", "evt-a", "", d1),    // second chunk, same delivery
            DigestMessageRow(-100L, 3L, "tech", "evt-a", "", d2),
            DigestMessageRow(-100L, 4L, "tech", "evt-b", "", d2),
            DigestMessageRow(-100L, 5L, "gaming", "evt-a", "", d2)   // other category
        ))
        val counts = db.countDigestPostsByEventKeys("tech", setOf("evt-a", "evt-b", "evt-c"), LocalDateTime.now().minusDays(7))
        assertEquals(mapOf("evt-a" to 2, "evt-b" to 1), counts)
        assertEquals(mapOf("evt-a" to 1, "evt-b" to 1), db.countDigestPostsByEventKeys("tech", setOf("evt-a", "evt-b"), LocalDateTime.now().minusDays(2)))
        assertTrue(db.countDigestPostsByEventKeys("tech", emptyList(), d1).isEmpty())
    }

    @Test
    fun `tracker entries upsert per entity, list per tracker and prune by age`() {
        val row = TrackerEntryRow("gaming", "releases", "silksong", "Silksong", "2026-10-14", null, "scheduled", "evt-1", -100L, 12L, LocalDateTime.now())
        db.upsertTrackerEntry(row)
        db.upsertTrackerEntry(row.copy(trackerId = "layoffs", entityKey = "other", title = "Other"))
        assertEquals(listOf("silksong"), db.fetchTrackerEntries("gaming", "releases").map { it.entityKey })
        assertTrue(db.fetchTrackerEntries("tech", "releases").isEmpty())

        db.upsertTrackerEntry(row.copy(eventDate = "2026-11-01", prevDate = "2026-10-14", status = "delayed", messageId = 20L))
        val updated = db.fetchTrackerEntries("gaming", "releases").single()
        assertEquals("2026-11-01", updated.eventDate)
        assertEquals("2026-10-14", updated.prevDate)
        assertEquals("delayed", updated.status)
        assertEquals(20L, updated.messageId)

        db.upsertTrackerEntry(row.copy(entityKey = "stale", updatedAt = LocalDateTime.now().minusDays(400)))
        db.deleteOldTrackerEntries(365)
        assertEquals(listOf("silksong"), db.fetchTrackerEntries("gaming", "releases").map { it.entityKey })
    }

    @Test
    fun `entity intros upsert, read back and prune by age`() {
        assertEquals(null, db.getEntityIntro("gaming", "team_cherry"))
        val row = EntityIntroRow("gaming", "team_cherry", "Team Cherry", "line", "https://uk.wikipedia.org/wiki/Team_Cherry", "uk", LocalDateTime.now())
        db.putEntityIntro(row)
        assertEquals(row.copy(introducedAt = db.getEntityIntro("gaming", "team_cherry")!!.introducedAt), db.getEntityIntro("gaming", "team_cherry"))
        assertEquals(null, db.getEntityIntro("tech", "team_cherry"), "scoped by category")

        db.putEntityIntro(row.copy(extract = null, title = null, url = null, lang = null))   // a later miss overwrites
        assertEquals(null, db.getEntityIntro("gaming", "team_cherry")!!.extract)

        db.putEntityIntro(row.copy(entityKey = "old", introducedAt = LocalDateTime.now().minusDays(800)))
        db.deleteOldEntityIntros(730)
        assertEquals(null, db.getEntityIntro("gaming", "old"))
        assertTrue(db.getEntityIntro("gaming", "team_cherry") != null)
    }

    @Test
    fun `article meta_json is persisted and read back`() {
        val meta = metifikys.model.ArticleMeta(hn = metifikys.model.HnMeta(41234567, 512, 340)).toJson()
        db.insertArticles(listOf(article("https://example.com/hn").copy(metaJson = meta), article("https://example.com/plain")))
        val byLink = db.fetchReadyForDigestByCategory().getValue("tech").associateBy { it.link }
        assertEquals(meta, byLink.getValue("https://example.com/hn").metaJson)
        assertEquals(41234567L, byLink.getValue("https://example.com/hn").meta()!!.hn!!.id)
        assertEquals(null, byLink.getValue("https://example.com/plain").metaJson)
        assertEquals(meta, db.fetchArticlesByLinks(listOf("https://example.com/hn")).single().metaJson)
    }

    @Test
    fun `fetchLatestDigestMessagesByEventKeys returns the newest post per key, first chunk of it`() {
        val older = LocalDateTime.now().minusDays(3)
        val newer = LocalDateTime.now().minusDays(1)
        db.insertDigestMessages(listOf(
            DigestMessageRow(-100L, 10L, "tech", "evt-a", "", older),
            DigestMessageRow(-100L, 21L, "tech", "evt-a", "", newer),   // chunk 2 of the newer digest
            DigestMessageRow(-100L, 20L, "tech", "evt-a", "", newer),   // chunk 1 of the newer digest
            DigestMessageRow(-100L, 30L, "tech", "evt-b", "", older),
            DigestMessageRow(-100L, 40L, "gaming", "evt-a", "", newer), // other category
            DigestMessageRow(-100L, 50L, "tech", null, "", newer)       // legacy post, no event
        ))

        val rows = db.fetchLatestDigestMessagesByEventKeys("tech", setOf("evt-a", "evt-b", "evt-c"), LocalDateTime.now().minusDays(7))
        assertEquals(setOf("evt-a", "evt-b"), rows.keys)
        assertEquals(20L, rows.getValue("evt-a").messageId)
        assertEquals(-100L, rows.getValue("evt-a").chatId)
        assertEquals(30L, rows.getValue("evt-b").messageId)

        val recent = db.fetchLatestDigestMessagesByEventKeys("tech", setOf("evt-a", "evt-b"), LocalDateTime.now().minusDays(2))
        assertEquals(setOf("evt-a"), recent.keys)

        assertTrue(db.fetchLatestDigestMessagesByEventKeys("tech", emptyList(), older).isEmpty())
    }
}
