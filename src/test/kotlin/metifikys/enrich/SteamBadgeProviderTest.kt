package metifikys.enrich

import metifikys.model.ShortlistItem
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamBadgeProviderTest {

    private fun item(subject: String, eventType: String = "release_date") = ShortlistItem(
        eventKey = "evt", subject = subject, coreFact = "f", url = "https://example.com/1", status = "new", eventType = eventType
    )

    private val search = """{"total":2,"items":[
        {"type":"app","name":"Hollow Knight: Silksong","id":1030300},
        {"type":"app","name":"Hollow Knight","id":367520}]}"""
    private val details = """{"1030300":{"success":true,"data":{"is_free":false,
        "price_overview":{"currency":"UAH","initial":89900,"final":71900,"discount_percent":20,"final_formatted":"719₴"},
        "release_date":{"coming_soon":true,"date":"14 жовт. 2026"}}}}"""
    private val reviews = """{"success":1,"query_summary":{"num_reviews":0,"total_positive":11423,"total_negative":922,"total_reviews":12345}}"""

    private fun provider(api: FakeApi, cache: BadgeCache = MemoryBadgeCache()) =
        SteamBadgeProvider(BadgeHttp(timeoutSeconds = 3), cache, storeBaseUrl = api.baseUrl)

    @Test
    fun `renders price, discount, reviews and release date for a matched title`() {
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", details).route("/appreviews/1030300", reviews).start().use { api ->
            val badge = provider(api).badge(item("Hollow Knight: Silksong"), emptyList())
            assertEquals(
                "🎮 [Steam](https://store.steampowered.com/app/1030300): 719₴ (−20%) · 93% позитивних (12 345) · реліз 14 жовт. 2026",
                badge?.text
            )
        }
    }

    @Test
    fun `no confident title match means no badge, and the miss is cached`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", search).start().use { api ->
            val p = provider(api, cache)
            assertNull(p.badge(item("Silksong 2 Remastered Deluxe"), emptyList()))
            val hitsAfterFirst = api.hits.get()
            assertNull(p.badge(item("Silksong 2 Remastered Deluxe"), emptyList()))
            assertEquals(hitsAfterFirst, api.hits.get(), "negative result should be served from cache")
            assertEquals("", cache.get("steam", "silksong_2_remastered_deluxe", Duration.ofHours(1)))
        }
    }

    @Test
    fun `a positive result is cached and reused without HTTP`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", details).route("/appreviews/", reviews).start().use { api ->
            val p = provider(api, cache)
            val first = p.badge(item("Hollow Knight: Silksong"), emptyList())
            val hits = api.hits.get()
            assertEquals(first, p.badge(item("Hollow Knight: Silksong"), emptyList()))
            assertEquals(hits, api.hits.get())
        }
    }

    @Test
    fun `an API failure yields no badge and is not cached`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", "boom", status = 500).start().use { api ->
            assertNull(provider(api, cache).badge(item("Hollow Knight: Silksong"), emptyList()))
            assertNull(cache.get("steam", "hollow_knight_silksong", Duration.ofHours(1)))
        }
    }

    @Test
    fun `only game events with a subject are looked up`() {
        FakeApi().start().use { api ->
            val p = provider(api)
            assertNull(p.badge(null, emptyList()))
            assertNull(p.badge(item("Hollow Knight: Silksong", eventType = "studio_closure"), emptyList()))
            assertNull(p.badge(item("", eventType = "release_date"), emptyList()))
            assertEquals(0, api.hits.get())
        }
    }

    @Test
    fun `title matching is strict`() {
        FakeApi().start().use { api ->
            val p = provider(api)
            assertTrue(p.matches("hollow_knight_silksong", "hollow_knight_silksong"))
            assertTrue(p.matches("cyberpunk_2077_ultimate_edition", "cyberpunk_2077"))
            assertTrue(p.matches("the_witcher_3_wild_hunt", "witcher_3_wild_hunt"))
            assertFalse(p.matches("doom_eternal", "doom"))
            assertFalse(p.matches("hollow_knight", "hollow_knight_silksong"))
            assertFalse(p.matches(null, "x"))
        }
    }

    @Test
    fun `a coming-soon game without a firm date shows Steam's own wording, not "реліз Незабаром"`() {
        val noDate = """{"1030300":{"success":true,"data":{"is_free":false,"release_date":{"coming_soon":true,"date":"Незабаром"}}}}"""
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", noDate).start().use { api ->
            val badge = provider(api).badge(item("Hollow Knight: Silksong", eventType = "major_announcement"), emptyList())
            assertEquals("🎮 [Steam](https://store.steampowered.com/app/1030300): незабаром", badge?.text)
        }
    }

    @Test
    fun `free games and released games render without price or date`() {
        val freeDetails = """{"1030300":{"success":true,"data":{"is_free":true,"release_date":{"coming_soon":false,"date":"2019"}}}}"""
        FakeApi().route("/api/storesearch/", search).route("/api/appdetails", freeDetails).route("/appreviews/", reviews).start().use { api ->
            val badge = provider(api).badge(item("Hollow Knight: Silksong"), emptyList())
            assertEquals("🎮 [Steam](https://store.steampowered.com/app/1030300): безкоштовно · 93% позитивних (12 345)", badge?.text)
        }
    }
}
