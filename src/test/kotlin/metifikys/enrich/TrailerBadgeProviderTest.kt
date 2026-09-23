package metifikys.enrich

import metifikys.fetch.UrlGuard
import metifikys.model.Article
import metifikys.model.ArticleMeta
import metifikys.model.ShortlistItem
import metifikys.model.VideoMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailerBadgeProviderTest {

    private val types = listOf("major_announcement", "release_date")

    private fun item(eventType: String = "release_date", subject: String = "Hollow Knight: Silksong", franchise: String = "Hollow Knight") =
        ShortlistItem(
            eventKey = "e", subject = subject, franchise = franchise, eventType = eventType, coreFact = "f",
            url = "https://ign.com/silksong", status = "new"
        )

    private fun article(videoId: String? = null) = Article(
        category = "gaming", title = "t", link = "https://ign.com/silksong", description = "d",
        metaJson = videoId?.let { ArticleMeta(video = VideoMeta(it)).toJson() }
    )

    private fun provider(api: FakeApi, cache: BadgeCache = MemoryBadgeCache(), pageHtml: ((String) -> String?)? = null) =
        TrailerBadgeProvider(BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF), cache, types, pageHtml, youtubeBaseUrl = api.baseUrl)

    private fun oembed(title: String) = """{"title":"$title","author_name":"Team Cherry","type":"video"}"""

    @Test
    fun `a video whose title names the subject becomes the trailer line`() {
        FakeApi().route("/oembed", oembed("Hollow Knight: Silksong - Release Date Trailer")).start().use { api ->
            val badge = provider(api).badge(item(), listOf(article("aBcDeFgHiJ0")))
            assertEquals("▶️ [Трейлер](https://youtu.be/aBcDeFgHiJ0)", badge?.text)
            assertTrue(api.requests.single().startsWith("/oembed?url=https://www.youtube.com/watch?v=aBcDeFgHiJ0"), api.requests.single())
        }
        FakeApi().route("/oembed", oembed("Hollow Knight Silksong gameplay walkthrough")).start().use { api ->
            assertEquals("▶️ [Відео](https://youtu.be/aBcDeFgHiJ0)", provider(api).badge(item(), listOf(article("aBcDeFgHiJ0")))?.text)
        }
    }

    @Test
    fun `the site promo video is not the story trailer`() {
        FakeApi().route("/oembed", oembed("IGN Daily Fix - September 23")).start().use { api ->
            assertNull(provider(api).badge(item(), listOf(article("PROMO000000"))))
        }
    }

    @Test
    fun `other event types and unknown videos get nothing, and results are cached`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/oembed", "Not Found", status = 404).start().use { api ->
            val p = provider(api, cache)
            assertNull(p.badge(item(eventType = "layoffs"), listOf(article("aBcDeFgHiJ0"))))
            assertEquals(0, api.hits.get())
            assertNull(p.badge(item(), listOf(article("aBcDeFgHiJ0"))))
            assertNull(p.badge(item(), listOf(article("aBcDeFgHiJ0"))))
            assertEquals(1, api.hits.get())
        }
    }

    @Test
    fun `without ingest meta the post page is fetched once and its embed is used`() {
        var fetches = 0
        val page = """<html><body><article><iframe src="https://www.youtube.com/embed/PAGE0000000"></iframe></article></body></html>"""
        val cache = MemoryBadgeCache()
        FakeApi().route("/oembed", oembed("Hollow Knight: Silksong Trailer")).start().use { api ->
            val p = provider(api, cache) { fetches++; page }
            assertEquals("▶️ [Трейлер](https://youtu.be/PAGE0000000)", p.badge(item(), listOf(article()))?.text)
            assertEquals("▶️ [Трейлер](https://youtu.be/PAGE0000000)", p.badge(item(), listOf(article()))?.text)
            assertEquals(1, fetches)
            assertEquals(1, api.hits.get())
            assertNull(provider(api).badge(item(), listOf(article())))   // no page fetcher, no meta
        }
    }

    @Test
    fun `the franchise alone is enough when the subject is longer than the title`() {
        FakeApi().route("/oembed", oembed("DOOM: The Dark Ages | Official Trailer")).start().use { api ->
            val badge = provider(api).badge(
                item(subject = "DOOM: The Dark Ages Collectors Edition", franchise = "Doom"),
                listOf(article("aBcDeFgHiJ0"))
            )
            assertEquals("▶️ [Трейлер](https://youtu.be/aBcDeFgHiJ0)", badge?.text)
        }
    }
}
