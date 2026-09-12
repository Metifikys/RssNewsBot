package metifikys.enrich

import metifikys.model.Article
import metifikys.model.ArticleMeta
import metifikys.model.HnMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HackerNewsBadgeProviderTest {

    private fun article(meta: ArticleMeta?) = Article(
        category = "tech", title = "t", link = "https://example.com/post", description = "d", metaJson = meta?.toJson()
    )

    private fun provider(api: FakeApi, cache: BadgeCache = MemoryBadgeCache()) =
        HackerNewsBadgeProvider(BadgeHttp(timeoutSeconds = 3), cache, apiBaseUrl = api.baseUrl)

    @Test
    fun `live score and comment count from the item endpoint`() {
        FakeApi().route("/v0/item/41234567.json", """{"id":41234567,"score":612,"descendants":401,"type":"story"}""").start().use { api ->
            val badge = provider(api).badge(null, listOf(article(ArticleMeta(HnMeta(41234567, 512, 340)))))
            assertEquals("🔥 [HN](https://news.ycombinator.com/item?id=41234567): 612 · 401 коментар", badge?.text)
        }
    }

    @Test
    fun `falls back to fetch-time counts when the API is down`() {
        FakeApi().route("/v0/item/", "nope", status = 503).start().use { api ->
            val badge = provider(api).badge(null, listOf(article(ArticleMeta(HnMeta(1, 512, 340)))))
            assertEquals("🔥 [HN](https://news.ycombinator.com/item?id=1): 512 · 340 коментарів", badge?.text)
        }
    }

    @Test
    fun `no HN meta means no badge, and the result is cached for an hour`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/v0/item/", """{"score":3,"descendants":2}""").start().use { api ->
            val p = provider(api, cache)
            assertNull(p.badge(null, listOf(article(null))))
            assertEquals(0, api.hits.get())
            val first = p.badge(null, listOf(article(ArticleMeta(HnMeta(9)))))
            assertEquals("🔥 [HN](https://news.ycombinator.com/item?id=9): 3 · 2 коментарі", first?.text)
            assertEquals(first, p.badge(null, listOf(article(ArticleMeta(HnMeta(9))))))
            assertEquals(1, api.hits.get())
        }
    }

    @Test
    fun `ukrainian plural forms`() {
        assertEquals("1 коментар", ukPlural(1, "коментар", "коментарі", "коментарів"))
        assertEquals("3 коментарі", ukPlural(3, "коментар", "коментарі", "коментарів"))
        assertEquals("5 коментарів", ukPlural(5, "коментар", "коментарі", "коментарів"))
        assertEquals("11 коментарів", ukPlural(11, "коментар", "коментарі", "коментарів"))
        assertEquals("21 коментар", ukPlural(21, "коментар", "коментарі", "коментарів"))
        assertEquals("112 коментарів", ukPlural(112, "коментар", "коментарі", "коментарів"))
        assertEquals("12 345", groupThousands(12345))
        assertEquals("999", groupThousands(999))
        assertEquals("1 000 000", groupThousands(1_000_000))
    }
}
