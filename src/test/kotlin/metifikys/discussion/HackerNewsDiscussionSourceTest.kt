package metifikys.discussion

import metifikys.enrich.BadgeHttp
import metifikys.enrich.FakeApi
import metifikys.fetch.UrlGuard
import metifikys.model.Article
import metifikys.model.ArticleMeta
import metifikys.model.HnMeta
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HackerNewsDiscussionSourceTest {

    private val now = Instant.parse("2026-10-10T12:00:00Z")
    private val http = BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF)

    private fun source(api: FakeApi, rankReplies: Int = 10) =
        HackerNewsDiscussionSource(http, firebaseBaseUrl = api.baseUrl, algoliaBaseUrl = api.baseUrl, rankReplies = rankReplies, now = { now })

    private fun article(link: String, hn: HnMeta? = null) = Article(
        category = "tech", title = "t", link = link, description = "d", pubDate = LocalDateTime.now(),
        metaJson = hn?.let { ArticleMeta(hn = it).toJson() }
    )

    /** Algolia's tree, children in creation order; #2 is deleted, #3 has two replies. */
    private val tree = """
        {"id":100,"type":"story","author":"op","title":"Story","children":[
          {"id":1,"author":"a","text":"<p>First by time, ranked second on the page &amp; long enough.</p>","children":[]},
          {"id":2,"author":null,"text":null,"children":[{"id":21,"author":"x","text":"orphaned reply under a deleted comment","children":[]}]},
          {"id":3,"author":"c","text":"Ranked first on the page, with a <i>real</i> point to make.","children":[
            {"id":31,"author":"d","text":"An older reply that HN ranks lower.","children":[]},
            {"id":32,"author":"e","text":"The reply HN ranks on top of the two.","children":[]}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun `comments follow HN's page order, replies too, deleted ones dropped`() {
        FakeApi()
            .route("/v0/item/100.json", """{"id":100,"descendants":42,"kids":[3,1,2]}""")
            .route("/v0/item/3.json", """{"id":3,"kids":[32,31]}""")
            .route("/api/v1/items/100", tree)
            .start().use { api ->
                val ref = DiscussionRef("hn", "100", "https://news.ycombinator.com/item?id=100", 10)
                val thread = assertNotNull(source(api).fetch(ref))

                assertEquals(42, thread.commentCount)
                assertEquals("HN", thread.sourceName)
                assertEquals(listOf("3", "1"), thread.comments.map { it.id })
                assertEquals("Ranked first on the page, with a real point to make.", thread.comments[0].text)
                assertEquals(listOf("32", "31"), thread.comments[0].replies.map { it.id })
                assertEquals("First by time, ranked second on the page & long enough.", thread.comments[1].text)
                // Only the comment with replies needed its own Firebase call.
                assertEquals(0, api.hitsFor("/v0/item/1.json"))
            }
    }

    @Test
    fun `replies keep Algolia's order past rankReplies`() {
        FakeApi()
            .route("/v0/item/100.json", """{"id":100,"descendants":42,"kids":[3,1]}""")
            .route("/v0/item/3.json", """{"id":3,"kids":[32,31]}""")
            .route("/api/v1/items/100", tree)
            .start().use { api ->
                val thread = assertNotNull(source(api, rankReplies = 0).fetch(DiscussionRef("hn", "100", "u")))
                assertEquals(listOf("31", "32"), thread.comments[0].replies.map { it.id })
                assertEquals(0, api.hitsFor("/v0/item/3.json"))
            }
    }

    @Test
    fun `a dead story is gone, a story without comments has none`() {
        FakeApi()
            .route("/v0/item/100.json", """{"id":100,"dead":true}""")
            .route("/v0/item/101.json", """{"id":101,"descendants":0}""")
            .start().use { api ->
                assertNull(source(api).fetch(DiscussionRef("hn", "100", "u")))
                val empty = assertNotNull(source(api).fetch(DiscussionRef("hn", "101", "u")))
                assertEquals(0, empty.comments.size)
                assertEquals(0, api.hitsFor("/api/v1/items"))
            }
    }

    @Test
    fun `locate reads the hnrss id from the article metadata`() {
        val src = HackerNewsDiscussionSource(http)
        assertNull(src.locate(listOf(article("https://a.example/x"))))
        val ref = assertNotNull(src.locate(listOf(article("https://a.example/x"), article("https://b.example/y", HnMeta(77, 300, 120)))))
        assertEquals(DiscussionRef("hn", "77", "https://news.ycombinator.com/item?id=77", 120), ref)
    }

    @Test
    fun `lookupByUrl takes the busiest recent story with the same URL`() {
        val recent = now.minusSeconds(3600).epochSecond
        val old = now.minusSeconds(30L * 86400).epochSecond
        FakeApi()
            .route(
                "/api/v1/search",
                """{"hits":[
                  {"objectID":"1","url":"https://other.example/post","num_comments":999,"points":900,"created_at_i":$recent},
                  {"objectID":"2","url":"https://example.com/post","num_comments":900,"points":800,"created_at_i":$old},
                  {"objectID":"3","url":"http://www.example.com/post/","num_comments":12,"points":40,"created_at_i":$recent},
                  {"objectID":"4","url":"https://example.com/post#comments","num_comments":64,"points":90,"created_at_i":$recent}
                ]}"""
            )
            .start().use { api ->
                val ref = assertNotNull(source(api).lookupByUrl("https://example.com/post", maxAgeDays = 7))
                assertEquals("4", ref.threadId)
                assertEquals(64, ref.commentCount)
                // FakeApi records the decoded query.
                assertEquals(1, api.hitsFor("/api/v1/search?query=https://example.com/post&restrictSearchableAttributes=url&tags=story"))
            }
    }
}
