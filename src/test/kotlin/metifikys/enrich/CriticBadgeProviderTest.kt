package metifikys.enrich

import metifikys.fetch.UrlGuard
import metifikys.model.ShortlistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CriticBadgeProviderTest {

    private fun item(eventType: String = "release") = ShortlistItem(
        eventKey = "evt", subject = "Cyberpunk 2077", coreFact = "f", url = "https://example.com/1", status = "new", eventType = eventType
    )

    private val search = """{"total":1,"items":[{"type":"app","name":"Cyberpunk 2077","id":1091500}]}"""

    /** The fields of a real `appdetails` answer for 1091500 (2026-09-23). */
    private fun details(metacritic: String? = """{"score":86,"url":"https://www.metacritic.com/game/pc/cyberpunk-2077?ftag=MCD-06-10aaa1f"}""", comingSoon: Boolean = false) =
        """{"1091500":{"success":true,"data":{"is_free":false,
        ${metacritic?.let { "\"metacritic\":$it," } ?: ""}
        "release_date":{"coming_soon":$comingSoon,"date":"9 груд. 2020"}}}}"""

    private fun provider(api: FakeApi): CriticBadgeProvider {
        val http = BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF)
        return CriticBadgeProvider(SteamAppResolver(http, MemoryBadgeCache(), storeBaseUrl = api.baseUrl))
    }

    @Test
    fun `Metacritic score from appdetails, tracking parameter dropped`() {
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", details()).start().use { api ->
            assertEquals(
                "🏆 [Metacritic](https://www.metacritic.com/game/pc/cyberpunk-2077): 86",
                provider(api).badge(item(), emptyList())?.text
            )
        }
    }

    @Test
    fun `no score, or a game still coming soon, gives no line`() {
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", details(metacritic = null)).start().use { api ->
            assertNull(provider(api).badge(item(), emptyList()))
        }
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", details(comingSoon = true)).start().use { api ->
            assertNull(provider(api).badge(item(eventType = "release_date"), emptyList()))
        }
    }

    @Test
    fun `only release and review events are looked up`() {
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", details()).start().use { api ->
            assertNull(provider(api).badge(item(eventType = "major_announcement"), emptyList()))
            assertNull(provider(api).badge(null, emptyList()))
            assertEquals(0, api.hits.get())
            assertEquals("🏆 [Metacritic](https://www.metacritic.com/game/pc/cyberpunk-2077): 86", provider(api).badge(item("review_roundup"), emptyList())?.text)
        }
    }

    @Test
    fun `tracking removal keeps other parameters`() {
        FakeApi().start().use { api ->
            val p = provider(api)
            assertEquals("https://x.com/a?b=1", p.withoutTracking("https://x.com/a?ftag=Z&b=1"))
            assertEquals("https://x.com/a", p.withoutTracking("https://x.com/a"))
        }
    }
}
