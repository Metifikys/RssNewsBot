package metifikys.enrich

import metifikys.fetch.UrlGuard
import metifikys.model.ShortlistItem
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeckBadgeProviderTest {

    private fun item(eventType: String = "release") = ShortlistItem(
        eventKey = "evt", subject = "Cyberpunk 2077", coreFact = "f", url = "https://example.com/1", status = "new", eventType = eventType
    )

    private val search = """{"total":1,"items":[{"type":"app","name":"Cyberpunk 2077","id":1091500}]}"""

    /** Shape of Valve's real report for 1091500 (2026-09-23), item lists trimmed. */
    private fun deck(category: Int) = """{"success":1,"results":{"appid":1091500,"resolved_category":$category,
        "resolved_items":[{"display_type":4,"loc_token":"#SteamDeckVerified_TestResult_DefaultControllerConfigFullyFunctional"}],
        "steam_deck_blog_url":"","search_id":null,"steamos_resolved_category":2,"steamos_resolved_items":[],
        "machine_resolved_category":3,"machine_resolved_items":[]}}"""
    /** The real answer for an unknown appid: `results` is an empty array. */
    private val deckUnknown = """{"success":1,"results":[]}"""
    /** The real ProtonDB summary for 1091500 (2026-09-23). */
    private fun proton(tier: String = "gold", total: Int = 2721) =
        """{"bestReportedTier":"platinum","confidence":"strong","score":0.77,"tier":"$tier","total":$total,"trendingTier":"platinum"}"""
    private val protonMissing = "<!DOCTYPE html><html lang=\"en\"><head><title>Page not found</title></head></html>"

    private val DECK = "/saleaction/ajaxgetdeckappcompatibilityreport"
    private val PROTON = "/api/v1/reports/summaries/"

    private fun provider(api: FakeApi, cache: BadgeCache = MemoryBadgeCache()): DeckBadgeProvider {
        val http = BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF)
        return DeckBadgeProvider(http, cache, SteamAppResolver(http, cache, storeBaseUrl = api.baseUrl), api.baseUrl, api.baseUrl)
    }

    @Test
    fun `both sources in one line`() {
        FakeApi().route("/api/storesearch/", search).route(DECK, deck(3)).route(PROTON, proton()).start().use { api ->
            assertEquals(
                "🕹 Steam Deck: ✅ перевірено · [ProtonDB](https://www.protondb.com/app/1091500): Gold (2 721 звіт)",
                provider(api).badge(item(), emptyList())?.text
            )
        }
    }

    @Test
    fun `Deck only when ProtonDB does not know the game or has too few reports`() {
        FakeApi().route("/api/storesearch/", search).route(DECK, deck(2)).route(PROTON, protonMissing, status = 404).start().use { api ->
            assertEquals("🕹 Steam Deck: 🟡 можна грати", provider(api).badge(item(eventType = "patch"), emptyList())?.text)
        }
        FakeApi().route("/api/storesearch/", search).route(DECK, deck(1)).route(PROTON, proton(total = 2)).start().use { api ->
            assertEquals("🕹 Steam Deck: ❌ не підтримується", provider(api).badge(item(), emptyList())?.text)
        }
        FakeApi().route("/api/storesearch/", search).route(DECK, deck(1)).route(PROTON, proton(tier = "pending", total = 50)).start().use { api ->
            assertEquals("🕹 Steam Deck: ❌ не підтримується", provider(api).badge(item(), emptyList())?.text)
        }
    }

    @Test
    fun `ProtonDB only when Valve has no report or an unknown category`() {
        FakeApi().route("/api/storesearch/", search).route(DECK, deckUnknown).route(PROTON, proton("platinum", 354)).start().use { api ->
            assertEquals(
                "🕹 [ProtonDB](https://www.protondb.com/app/1091500): Platinum (354 звіти)",
                provider(api).badge(item(), emptyList())?.text
            )
        }
        FakeApi().route("/api/storesearch/", search).route(DECK, deck(0)).route(PROTON, proton("borked", 12)).start().use { api ->
            assertEquals("🕹 [ProtonDB](https://www.protondb.com/app/1091500): Borked (12 звітів)", provider(api).badge(item(), emptyList())?.text)
        }
    }

    @Test
    fun `nothing to say is cached, a failed source is not`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", search).route(DECK, deckUnknown).route(PROTON, protonMissing, status = 404).start().use { api ->
            val p = provider(api, cache)
            assertNull(p.badge(item(), emptyList()))
            assertEquals("", cache.get("deck", "1091500", Duration.ofHours(1)))
            val hits = api.hits.get()
            assertNull(p.badge(item(), emptyList()))
            assertEquals(hits, api.hits.get())
        }
        val cache2 = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", search).route(DECK, deck(3)).route(PROTON, "boom", status = 502).start().use { api ->
            assertEquals("🕹 Steam Deck: ✅ перевірено", provider(api, cache2).badge(item(), emptyList())?.text)
            assertNull(cache2.get("deck", "1091500", Duration.ofHours(1)))
        }
    }

    @Test
    fun `other events make no request`() {
        FakeApi().start().use { api ->
            assertNull(provider(api).badge(item(eventType = "studio_closure"), emptyList()))
            assertNull(provider(api).badge(null, emptyList()))
            assertEquals(0, api.hits.get())
        }
    }
}
