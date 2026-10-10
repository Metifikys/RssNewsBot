package metifikys.db

import metifikys.model.Article
import metifikys.model.ArticleStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `articles.feed_url` / `fetched_at` / `drop_reason` and `feed_sightings` (feed-quality metrics, step one). */
class FeedColumnsTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-feeds", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun article(link: String, feed: String?) =
        Article(category = "tech", title = "T", link = link, description = "d", feedUrl = feed)

    private fun column(link: String, name: String): String? =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.prepareStatement("SELECT $name FROM articles WHERE link = ?").use { st ->
                st.setString(1, link)
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else error("no row $link") }
            }
        }

    @Test
    fun `insertArticles writes feed_url and fetched_at, a pre-feed article keeps them null-free of feed`() {
        db.insertArticles(listOf(article("https://a/1", "https://feed/a"), article("https://a/2", null)))

        assertEquals("https://feed/a", column("https://a/1", "feed_url"))
        assertNotNull(column("https://a/1", "fetched_at"))
        assertNull(column("https://a/2", "feed_url"))
        assertNull(column("https://a/1", "drop_reason"))
        assertEquals("https://feed/a", db.fetchArticlesByLinks(listOf("https://a/1")).single().feedUrl)
    }

    @Test
    fun `a re-insert of a known link keeps the first feed and fetched_at`() {
        db.insertArticles(listOf(article("https://a/1", "https://feed/a")))
        val first = column("https://a/1", "fetched_at")
        Thread.sleep(5)
        db.insertArticles(listOf(article("https://a/1", "https://feed/b")))

        assertEquals("https://feed/a", column("https://a/1", "feed_url"))
        assertEquals(first, column("https://a/1", "fetched_at"))
        assertEquals(mapOf("https://a/1" to "https://feed/a"), db.findExistingLinkFeeds(listOf("https://a/1", "https://a/9")))
    }

    @Test
    fun `markMuted marks PROCESSED with drop_reason mute`() {
        db.insertArticles(listOf(article("https://a/1", "f"), article("https://a/2", "f")))
        db.markMuted(listOf("https://a/1"))

        assertEquals(ArticleStatus.PROCESSED.name, column("https://a/1", "status"))
        assertEquals(DropReason.MUTE, column("https://a/1", "drop_reason"))
        assertNull(column("https://a/2", "drop_reason"))
        assertEquals(listOf("https://a/2"), db.fetchReadyForDigest("tech").map { it.link })
    }

    @Test
    fun `insertSeenArticles stores a new feed's backlog as processed and out of the feed metrics`() {
        db.insertSeenArticles(listOf(article("https://a/1", "https://feed/new")))
        db.insertArticles(listOf(article("https://a/2", "https://feed/new")))

        assertEquals(ArticleStatus.PROCESSED.name, column("https://a/1", "status"))
        assertEquals(DropReason.NEW_FEED, column("https://a/1", "drop_reason"))
        assertEquals(listOf("https://a/2"), db.fetchReadyForDigest("tech").map { it.link })
        assertEquals(listOf("https://a/2"), db.fetchFeedArticleOutcomes(LocalDateTime.now().minusDays(1)).map { it.link })
    }

    @Test
    fun `findKnownFeedUrls sees feeds through articles and sightings`() {
        db.insertArticles(listOf(article("https://a/1", "https://feed/a")))
        db.insertFeedSightings(listOf(FeedSighting("https://a/1", "https://feed/echo", "tech", LocalDateTime.now())))

        assertEquals(
            setOf("https://feed/a", "https://feed/echo"),
            db.findKnownFeedUrls(listOf("https://feed/a", "https://feed/echo", "https://feed/new"))
        )
        assertEquals(emptySet(), db.findKnownFeedUrls(emptyList()))
    }

    @Test
    fun `feed sightings keep the first sighting per link and feed and expire`() {
        val old = LocalDateTime.now().minusDays(20)
        db.insertFeedSightings(listOf(
            FeedSighting("https://a/1", "https://feed/b", "tech", old),
            FeedSighting("https://a/1", "https://feed/b", "tech", LocalDateTime.now()),
            FeedSighting("https://a/2", "https://feed/c", "tech", LocalDateTime.now())
        ))
        assertEquals(2, db.fetchFeedSightingsSince(old.minusDays(1)).size)
        assertEquals(listOf("https://a/2"), db.fetchFeedSightingsSince(LocalDateTime.now().minusDays(1)).map { it.link })

        db.deleteOldFeedSightings(10)
        assertEquals(listOf("https://a/2"), db.fetchFeedSightingsSince(old.minusDays(1)).map { it.link })
    }
}
