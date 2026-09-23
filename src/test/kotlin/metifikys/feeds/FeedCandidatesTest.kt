package metifikys.feeds

import io.mockk.every
import io.mockk.mockk
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.FeedCandidatesConfig
import metifikys.config.FeedConfig
import metifikys.config.FeedHealthConfig
import metifikys.db.DigestMessageRow
import metifikys.db.FeedCandidateStatus
import metifikys.db.NewsDatabase
import metifikys.fetch.RssFetcher
import metifikys.model.Article
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedCandidatesTest {

    @Test
    fun `registrable domains`() {
        assertEquals("arstechnica.com", FeedCandidates.registrableDomain("feeds.arstechnica.com"))
        assertEquals("bbc.co.uk", FeedCandidates.registrableDomain("news.bbc.co.uk"))
        assertEquals("pravda.com.ua", FeedCandidates.registrableDomain("www.pravda.com.ua"))
        assertEquals("192.168.77.7", FeedCandidates.registrableDomain("192.168.77.7"))
    }

    @Test
    fun `followed and ignored domains are excluded, the rest ranked by published links`() {
        val links = listOf(
            "https://blog.studio.com/a", "https://blog.studio.com/b", "https://studio.com/c",
            "https://www.arstechnica.com/x", "https://arstechnica.com/y", "https://arstechnica.com/z",
            "https://en.wikipedia.org/wiki/A", "https://en.wikipedia.org/wiki/B", "https://en.wikipedia.org/wiki/C",
            "https://lab.example.org/1", "https://lab.example.org/2", "https://lab.example.org/3", "https://lab.example.org/4",
            "https://once.net/1"
        )
        val ranked = FeedCandidates.rank(links, listOf("https://feeds.arstechnica.com/arstechnica/index"), listOf("wikipedia.org"), 3)
        assertEquals(listOf("example.org" to 4, "studio.com" to 3), ranked.map { it.domain to it.published })
        assertEquals("blog.studio.com", ranked[1].host, "the most common host is what gets probed")
    }
}

class FeedCandidateFinderTest {

    private lateinit var db: NewsDatabase
    private lateinit var dbFile: File
    private val now = LocalDateTime.of(2026, 9, 23, 12, 0)

    @BeforeEach
    fun setup() {
        dbFile = File.createTempFile("test-candidates", ".db")
        db = NewsDatabase(dbFile.absolutePath)
    }

    @AfterEach
    fun teardown() {
        dbFile.delete()
    }

    private fun config(c: FeedCandidatesConfig): AppConfig {
        val cfg = mockk<AppConfig>(relaxed = true)
        every { cfg.feedHealth } returns FeedHealthConfig(candidates = c)
        every { cfg.categories } returns mapOf(
            "tech" to CategoryConfig(emoji = "x", feeds = listOf(FeedConfig("https://www.reddit.com/r/tech/.rss")), channelId = "@t")
        )
        return cfg
    }

    private val feedXml = """
        <?xml version="1.0"?><rss version="2.0"><channel><title>t</title><link>https://lab.example.org</link>
        <item><title>1</title><link>https://lab.example.org/1</link></item>
        <item><title>9</title><link>https://lab.example.org/9</link></item></channel></rss>
    """.trimIndent().toByteArray()

    @Test
    fun `probes due candidates within the budget, records the result and waits everyDays`() {
        db.insertDigestMessages((1..4).map { i ->
            DigestMessageRow(-1, i.toLong(), "tech", "e$i", "https://lab.example.org/$i\nhttps://www.reddit.com/r/tech/$i", now.minusDays(1))
        } + (1..3).map { i -> DigestMessageRow(-1, 10L + i, "tech", "s$i", "https://nofeed.io/$i", now.minusDays(1)) })
        db.insertArticles(listOf(Article("tech", "t", "https://lab.example.org/1", "d")))
        val requested = mutableListOf<String>()
        val discovery = FeedDiscovery(validator = RssFetcher(allowPrivateHosts = true), http = { url ->
            requested += url
            when (url) {
                "https://lab.example.org/" -> FeedDiscovery.Response(200, "text/html", null,
                    """<link rel="alternate" type="application/rss+xml" href="/rss.xml">""".toByteArray())
                "https://lab.example.org/rss.xml" -> FeedDiscovery.Response(200, "application/rss+xml", null, feedXml)
                else -> FeedDiscovery.Response(404, "text/html", null, ByteArray(0))
            }
        })
        val finder = FeedCandidateFinder(config(FeedCandidatesConfig(enabled = true, minPublished = 3)), db, discovery) { now }

        finder.runIfDue()

        val rows = db.fetchFeedCandidates().associateBy { it.domain }
        assertEquals(setOf("example.org", "nofeed.io"), rows.keys, "reddit is followed")
        val lab = rows.getValue("example.org")
        assertEquals(FeedCandidateStatus.FOUND, lab.status)
        assertEquals("https://lab.example.org/rss.xml", lab.feedUrl)
        assertEquals(1, lab.overlap)
        assertEquals(4, lab.published)
        assertEquals(FeedCandidateStatus.NONE, rows.getValue("nofeed.io").status)
        assertEquals(2 + 1 + FeedDiscovery.FALLBACK_PATHS.size, requested.size)

        requested.clear()
        finder.runIfDue()
        assertTrue(requested.isEmpty(), "not due again before everyDays")
    }

    @Test
    fun `a checked candidate is not probed again before recheckDays, a dismissed one never`() {
        db.insertDigestMessages((1..3).map { i -> DigestMessageRow(-1, i.toLong(), "tech", "e$i", "https://a.dev/$i", now.minusDays(1)) })
        val requests = mutableListOf<String>()
        val discovery = FeedDiscovery(http = { url -> requests += url; FeedDiscovery.Response(404, "text/html", null, ByteArray(0)) })
        val finder = FeedCandidateFinder(config(FeedCandidatesConfig(enabled = true, maxRequestsPerRun = 2)), db, discovery) { now }

        finder.run(now)
        assertEquals(2, requests.size, "maxRequestsPerRun caps the probes")
        requests.clear()
        finder.run(now.plusDays(1))
        assertTrue(requests.isEmpty())
        db.recordFeedCandidateCheck("a.dev", metifikys.db.FeedCandidateCheck(FeedCandidateStatus.DISMISSED), now)
        finder.run(now.plusDays(60))
        assertTrue(requests.isEmpty())
    }
}
