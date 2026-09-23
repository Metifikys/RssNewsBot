package metifikys.enrich

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.fetch.UrlGuard
import metifikys.model.ShortlistItem
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamAppResolverTest {

    private val search = """{"total":1,"items":[{"type":"app","name":"Cyberpunk 2077","id":1091500,"metascore":"86"}]}"""
    /** Trimmed from a real `appdetails?appids=1091500&cc=UA&l=ukrainian` answer (2026-09-23). */
    private val details = """{"1091500":{"success":true,"data":{"type":"game","name":"Cyberpunk 2077","steam_appid":1091500,
        "is_free":false,"detailed_description":"<p>very long</p>","screenshots":[{"id":0}],
        "metacritic":{"score":86,"url":"https://www.metacritic.com/game/pc/cyberpunk-2077?ftag=MCD-06-10aaa1f"},
        "price_overview":{"currency":"UAH","initial":139900,"final":139900,"discount_percent":0,"initial_formatted":"","final_formatted":"1 399₴"},
        "release_date":{"coming_soon":false,"date":"9 груд. 2020"},
        "supported_languages":"англійська<strong>*</strong>, українська<br><strong>*</strong>повністю озвучено цими мовами"}}}"""

    private fun resolver(api: FakeApi, cache: BadgeCache = MemoryBadgeCache()) =
        SteamAppResolver(BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF), cache, storeBaseUrl = api.baseUrl)

    private fun item(subject: String, eventType: String = "release") = ShortlistItem(
        eventKey = "evt", subject = subject, coreFact = "f", url = "https://example.com/1", status = "new", eventType = eventType
    )

    @Test
    fun `appid is found once and cached, a miss is cached as empty`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", search).start().use { api ->
            val r = resolver(api, cache)
            assertEquals(1091500L, r.appIdFor("Cyberpunk 2077"))
            assertEquals(1091500L, r.appIdFor("Cyberpunk 2077"))
            assertEquals(1, api.hits.get())
            assertEquals("1091500", cache.get(SteamAppResolver.APP_NS, "cyberpunk_2077", Duration.ofHours(1)))
            assertNull(r.appIdFor("Doom"))
            assertEquals("", cache.get(SteamAppResolver.APP_NS, "doom", Duration.ofHours(1)))
        }
    }

    @Test
    fun `the event-type gate matches the Steam badge's unless told otherwise`() {
        FakeApi().route("/api/storesearch/", search).start().use { api ->
            val r = resolver(api)
            assertNull(r.appId(item("Cyberpunk 2077", eventType = "patch")))
            assertEquals(0, api.hits.get())
            assertEquals(1091500L, r.appId(item("Cyberpunk 2077", eventType = "patch"), SteamBadgeProvider.EVENT_TYPES + "patch"))
        }
    }

    @Test
    fun `details keep only the fields the badges read`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/appdetails", details).start().use { api ->
            val d = resolver(api, cache).details(1091500)!!
            assertEquals(setOf("is_free", "price_overview", "release_date", "metacritic", "supported_languages"), d.keys)
            assertEquals("86", d["metacritic"]!!.jsonObject["score"]!!.jsonPrimitive.content)
            val cached = cache.get(SteamAppResolver.DETAILS_NS, "1091500", Duration.ofHours(1))!!
            assertTrue("screenshots" !in cached && "detailed_description" !in cached, cached)
            assertEquals(d, resolver(api, cache).details(1091500))
            assertEquals(1, api.hits.get())
        }
    }

    @Test
    fun `concurrent lookups of one game share a single request`() {
        FakeApi().route("/api/storesearch/", search, delayMs = 300).route("/api/appdetails", details, delayMs = 300).start().use { api ->
            val r = resolver(api)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val ids = (1..4).map { pool.submit(Callable { r.appIdFor("Cyberpunk 2077") }) }.map { it.get() }
                assertEquals(List(4) { 1091500L }, ids)
                val cards = (1..4).map { pool.submit(Callable { r.details(1091500) }) }.map { it.get() }
                assertTrue(cards.all { it != null && it == cards[0] })
            } finally {
                pool.shutdownNow()
            }
            assertEquals(1, api.hitsFor("/api/storesearch/"))
            assertEquals(1, api.hitsFor("/api/appdetails"))
        }
    }

    @Test
    fun `a failure is thrown to every waiter and not cached`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", "boom", status = 500).start().use { api ->
            val r = resolver(api, cache)
            val failed = runCatching { r.appIdFor("Cyberpunk 2077") }
            assertTrue(failed.isFailure)
            assertNull(cache.get(SteamAppResolver.APP_NS, "cyberpunk_2077", Duration.ofHours(1)))
        }
    }
}
