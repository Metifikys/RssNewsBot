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

    // ── title candidates: Step 1's subject is a Ukrainian topic, the game title sits inside it ──

    private fun titles(subject: String, franchise: String) =
        SteamAppResolver.candidates(subject, franchise).map { it.title to it.allowExtension }

    @Test
    fun `candidates are the subject's Latin titles that share a word with the franchise, then the franchise exactly`() {
        assertEquals(
            listOf("Titan Quest II" to true, "Titan Quest" to false),
            titles("Вихід Titan Quest II з раннього доступу", "Titan Quest / THQ Nordic")
        )
        assertEquals(listOf("Doom: The Dark Ages" to true, "Doom" to false), titles("Slaughter Mode для Doom: The Dark Ages", "Doom / id Software"))
        assertEquals(listOf("Frutiger Home" to true), titles("Frutiger Home", "Frutiger Home"))
        assertEquals(listOf("Signal Veil" to true), titles("Signal Veil", ""))
        assertEquals(listOf("Rust" to true), titles("Оновлення Rust з коровами й вівцями", "Rust / Facepunch Studios"))
    }

    @Test
    fun `a run naming something else is not searched, and a franchise is never extended`() {
        // "Valentine's Day" is not the Pokémon game; "One Piece" is the other side of a crossover.
        assertEquals(listOf("Pokémon TCG" to true, "Pokémon" to false), titles("Перший Valentine's Day сет Pokémon TCG", "Pokémon / The Pokémon Company"))
        assertEquals(listOf("Spider-Man" to true), titles("Кросовер Spider-Man і One Piece", "Spider-Man / One Piece"))
        // No Latin run of the game: only the franchise, exact — "GTA 6" news is not about GTA V.
        assertEquals(listOf("Grand Theft Auto" to false), titles("GTA 6 отримала рейтинг ESRB M", "Grand Theft Auto / Rockstar Games"))
        // "The" alone does not tie "The Boba Teashop" to "The Elder Scrolls".
        assertEquals(listOf("The Elder Scrolls" to false), titles("The Boba Teashop: Refill", "The Elder Scrolls / Bethesda"))
    }

    @Test
    fun `the franchise fallback needs the same words, not one more`() {
        assertTrue(!SteamAppResolver.matches("warhammer_40_000_darktide", "warhammer_40_000", allowExtension = false))
        assertTrue(SteamAppResolver.matches("warhammer_40_000_darktide", "warhammer_40_000"))
        assertTrue(SteamAppResolver.matches("rust", "rust", allowExtension = false))
    }

    @Test
    fun `an event resolves through its Latin title, and the franchise fallback rejects a longer store title`() {
        val titanQuest = """{"total":1,"items":[{"type":"app","name":"Titan Quest II","id":1154030}]}"""
        val gtaV = """{"total":1,"items":[{"type":"app","name":"Grand Theft Auto V Enhanced","id":3240220}]}"""
        FakeApi()
            .route("/api/storesearch/?term=Titan+Quest+II", titanQuest)
            .route("/api/storesearch/?term=Grand+Theft+Auto", gtaV)
            .start().use { api ->
                val r = resolver(api)
                val titan = item("Вихід Titan Quest II з раннього доступу").copy(franchise = "Titan Quest / THQ Nordic")
                assertEquals(1154030L, r.appId(titan))
                val gta6 = item("GTA 6 отримала рейтинг ESRB M").copy(franchise = "Grand Theft Auto / Rockstar Games")
                assertNull(r.appId(gta6))
            }
    }
}
