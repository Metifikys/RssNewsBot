package metifikys.enrich

import metifikys.fetch.UrlGuard
import metifikys.model.ShortlistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // OpenCritic via RapidAPI: shapes from the RapidAPI documentation (not verified — the API needs a key).
    private val ocSearch = """[{"id":14343,"name":"Cyberpunk 2077","dist":0},{"id":1,"name":"Cyberpunk 2077: Phantom Liberty","dist":0.3}]"""
    private fun ocGame(score: Double = 90.6, recommended: Double = 96.8) =
        """{"id":14343,"name":"Cyberpunk 2077","topCriticScore":$score,"percentRecommended":$recommended,"tier":"Mighty",
        "url":"https://opencritic.com/game/14343/cyberpunk-2077"}"""

    private fun withOpenCritic(api: FakeApi, cache: BadgeCache = MemoryBadgeCache(), key: String? = "rk", maxSearches: Int = 20): CriticBadgeProvider {
        val http = BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF)
        return CriticBadgeProvider(SteamAppResolver(http, cache, storeBaseUrl = api.baseUrl), http, cache, key, api.baseUrl, maxSearches)
    }

    @Test
    fun `OpenCritic joins Metacritic when a key is set, with RapidAPI headers`() {
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", details())
            .route("/game/search", ocSearch).route("/game/14343", ocGame()).start().use { api ->
                assertEquals(
                    "🏆 [Metacritic](https://www.metacritic.com/game/pc/cyberpunk-2077): 86 · " +
                        "[OpenCritic](https://opencritic.com/game/14343/cyberpunk-2077): 91, рекомендують 97%",
                    withOpenCritic(api).badge(item(), emptyList())?.text
                )
                val ocHeaders = api.requests.indices.filter { api.requests[it].startsWith("/game/") }.map { api.headers[it] }
                assertEquals(2, ocHeaders.size)
                assertTrue(ocHeaders.all { it["x-rapidapi-key"] == "rk" && it["x-rapidapi-host"] == "opencritic-api.p.rapidapi.com" })
            }
    }

    @Test
    fun `without a key nothing goes to OpenCritic`() {
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", details())
            .route("/game/", ocGame()).start().use { api ->
                assertEquals(
                    "🏆 [Metacritic](https://www.metacritic.com/game/pc/cyberpunk-2077): 86",
                    withOpenCritic(api, key = null).badge(item(), emptyList())?.text
                )
                assertEquals(0, api.hitsFor("/game/"))
            }
    }

    @Test
    fun `an OpenCritic score of -1 leaves only Metacritic, and the game is cached`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", details())
            .route("/game/search", ocSearch).route("/game/14343", ocGame(score = -1.0, recommended = -1.0)).start().use { api ->
                val p = withOpenCritic(api, cache)
                assertEquals("🏆 [Metacritic](https://www.metacritic.com/game/pc/cyberpunk-2077): 86", p.badge(item(), emptyList())?.text)
                val hits = api.hits.get()
                p.badge(item(), emptyList())
                assertEquals(hits, api.hits.get())
            }
    }

    @Test
    fun `a game not on Steam can still get OpenCritic, and searches stop at the daily budget`() {
        FakeApi().route("/game/search", ocSearch).route("/game/14343", ocGame()).start().use { api ->
            val p = withOpenCritic(api, maxSearches = 1)
            assertEquals(
                "🏆 [OpenCritic](https://opencritic.com/game/14343/cyberpunk-2077): 91, рекомендують 97%",
                p.badge(item(), emptyList())?.text
            )
            val other = item().copy(subject = "Some Console Exclusive")
            assertNull(p.badge(other, emptyList()))
            assertEquals(1, api.hitsFor("/game/search"))
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
