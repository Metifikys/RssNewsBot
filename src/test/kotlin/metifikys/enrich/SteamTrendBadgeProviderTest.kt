package metifikys.enrich

import metifikys.fetch.UrlGuard
import metifikys.model.ShortlistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SteamTrendBadgeProviderTest {

    private fun item(eventType: String = "release_date") = ShortlistItem(
        eventKey = "evt", subject = "Light No Fire", coreFact = "f", url = "https://example.com/1", status = "new", eventType = eventType
    )

    private val search = """{"total":1,"items":[{"type":"app","name":"Light No Fire","id":2719590}]}"""
    private val GAME = 2719590L

    /**
     * A store chart in the real `search/results/?json=1` shape (2026-09-23): `{"desc","items":[{name, logo}]}`,
     * slashes escaped, the appid only in the logo path; null = an item whose logo has no `/apps/<id>/`.
     */
    private fun chart(vararg ids: Long?) = """{"desc":"","items":[""" + ids.mapIndexed { i, id ->
        val logo = if (id != null) "https:\\/\\/shared.akamai.steamstatic.com\\/store_item_assets\\/steam\\/apps\\/$id\\/88b400eca727f0547232b49b3e62827f07900c77\\/capsule_sm_120.jpg?t=1769127177"
        else "https:\\/\\/shared.akamai.steamstatic.com\\/store_item_assets\\/steam\\/bundles\\/232\\/x\\/capsule_sm_120.jpg"
        """{"name":"Game $i","logo":"$logo"}"""
    }.joinToString(",") + "]}"

    /** Positions 1..n filled with other games, [GAME] at [rank]. */
    private fun chartWithGameAt(rank: Int, size: Int = 100): String =
        chart(*Array(size) { i -> if (i + 1 == rank) GAME else 1000L + i })

    /** The real `GetMostPlayedGames` shape. */
    private fun mostPlayed(vararg ranked: Pair<Int, Long>) = """{"response":{"rollup_date":1790035200,"ranks":[""" +
        ranked.joinToString(",") { (r, id) -> """{"rank":$r,"appid":$id,"last_week_rank":$r,"peak_in_game":1000}""" } + "]}}"

    private val TOP = "/search/results/?filter=topsellers"
    private val WISH = "/search/results/?filter=popularwishlist"
    private val SOON = "/search/results/?filter=popularcomingsoon"
    private val PLAYED = "/ISteamChartsService/GetMostPlayedGames/v1/"

    private fun provider(api: FakeApi, cache: BadgeCache = MemoryBadgeCache(), topN: Int = 25): SteamTrendBadgeProvider {
        val http = BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF)
        return SteamTrendBadgeProvider(http, cache, SteamAppResolver(http, cache, storeBaseUrl = api.baseUrl), topN,
            storeBaseUrl = api.baseUrl, apiBaseUrl = api.baseUrl)
    }

    @Test
    fun `ranks from two charts, appid read from the logo URL, at most two charts in fixed order`() {
        FakeApi().route("/api/storesearch/", search)
            .route(TOP, chartWithGameAt(4)).route(WISH, chartWithGameAt(12)).route(SOON, chartWithGameAt(2))
            .route(PLAYED, mostPlayed(1 to 730L, 2 to GAME)).start().use { api ->
                assertEquals("📈 Steam: №4 у хітах продажу · №12 серед найочікуваніших", provider(api).badge(item(), emptyList())?.text)
                assertEquals(1, api.hitsFor(TOP))
                assertEquals(1, api.hitsFor(PLAYED))
            }
    }

    @Test
    fun `an item without an appid keeps its position`() {
        FakeApi().route("/api/storesearch/", search).route(WISH, chart(1L, null, GAME)).start().use { api ->
            assertEquals("📈 Steam: №3 серед найочікуваніших", provider(api).badge(item(), emptyList())?.text)
        }
    }

    @Test
    fun `outside topN is no line, and a chart shorter than topN is fine`() {
        FakeApi().route("/api/storesearch/", search).route(TOP, chartWithGameAt(31)).route(WISH, chart(1L, 2L)).start().use { api ->
            assertNull(provider(api).badge(item(), emptyList()))
        }
        FakeApi().route("/api/storesearch/", search).route(TOP, chartWithGameAt(31)).start().use { api ->
            assertEquals("📈 Steam: №31 у хітах продажу", provider(api, topN = 31).badge(item(), emptyList())?.text)
        }
    }

    @Test
    fun `a failed chart is left out, the others still count`() {
        FakeApi().route("/api/storesearch/", search).route(TOP, "oops", status = 500).route(WISH, "{}")
            .route(SOON, chartWithGameAt(7, size = 10)).route(PLAYED, mostPlayed(1 to GAME)).start().use { api ->
                assertEquals("📈 Steam: №7 серед популярних майбутніх · №1 за онлайном", provider(api).badge(item(), emptyList())?.text)
            }
    }

    @Test
    fun `the next bullet reads the charts from the cache`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", search).route(TOP, chartWithGameAt(4)).route(WISH, chart())
            .route(SOON, chart()).route(PLAYED, mostPlayed()).start().use { api ->
                val p = provider(api, cache)
                val first = p.badge(item(), emptyList())
                val hits = api.hits.get()
                assertEquals(first, p.badge(item(), emptyList()))
                assertEquals(hits, api.hits.get())
                // Another provider instance (a restart) still finds them in badge_cache.
                assertEquals(first, provider(api, cache).badge(item(), emptyList()))
                assertEquals(hits, api.hits.get())
            }
    }

    @Test
    fun `other events and unknown games make no chart request`() {
        FakeApi().route("/api/storesearch/", search).start().use { api ->
            assertNull(provider(api).badge(item(eventType = "studio_closure"), emptyList()))
            assertNull(provider(api).badge(item().copy(subject = "Unknown"), emptyList()))
            assertEquals(0, api.hitsFor("/search/results/"))
            assertEquals(0, api.hitsFor(PLAYED))
        }
    }
}
