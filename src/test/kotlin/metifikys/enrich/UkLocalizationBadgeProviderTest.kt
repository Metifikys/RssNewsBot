package metifikys.enrich

import metifikys.fetch.UrlGuard
import metifikys.model.ShortlistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UkLocalizationBadgeProviderTest {

    private fun item(subject: String = "Cyberpunk 2077", eventType: String = "release") = ShortlistItem(
        eventKey = "evt", subject = subject, coreFact = "f", url = "https://example.com/1", status = "new", eventType = eventType
    )

    private val search = """{"total":1,"items":[{"type":"app","name":"Cyberpunk 2077","id":1091500}]}"""

    /** Shape of a real `IStoreBrowseService/GetItems` answer for 1091500 (2026-09-23), languages trimmed. */
    private fun getItems(uk: String?) = """{"response":{"store_items":[{"item_type":0,"id":1091500,"success":1,"visible":true,
        "name":"Cyberpunk 2077","appid":1091500,"type":0,"supported_languages":[
        {"elanguage":0,"eadditionallanguage":-1,"supported":true,"full_audio":true,"subtitles":true},
        {"elanguage":25,"eadditionallanguage":-1,"supported":true,"full_audio":false,"subtitles":true}
        ${uk?.let { ",$it" } ?: ""}]}]}}"""
    private val ukTextOnly = """{"elanguage":26,"eadditionallanguage":-1,"supported":true,"full_audio":false,"subtitles":true}"""
    /** A real answer for an unknown appid. */
    private val unknownApp = """{"response":{"store_items":[{"item_type":0,"id":999999999,"success":15,"visible":false,"name":"","store_url_path":"app/0/","store_url_slug":"","appid":0}]}}"""

    private fun details(languages: String) =
        """{"1091500":{"success":true,"data":{"is_free":false,"supported_languages":"$languages"}}}"""

    private fun provider(api: FakeApi, cache: BadgeCache = MemoryBadgeCache(), showMissing: Boolean = false): UkLocalizationBadgeProvider {
        val http = BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF)
        val resolver = SteamAppResolver(http, cache, storeBaseUrl = api.baseUrl)
        return UkLocalizationBadgeProvider(http, cache, resolver, showMissing, apiBaseUrl = api.baseUrl)
    }

    @Test
    fun `GetItems flags render interface, subtitles and voice-over`() {
        val full = """{"elanguage":26,"eadditionallanguage":-1,"supported":true,"full_audio":true,"subtitles":true}"""
        FakeApi().route("/api/storesearch/", search).route("/IStoreBrowseService/GetItems/v1/", getItems(full)).start().use { api ->
            assertEquals("🇺🇦 Українська: інтерфейс, субтитри, озвучка", provider(api).badge(item(), emptyList())?.text)
            val request = api.requests.last()
            assertTrue(request.contains("\"appid\":1091500") && request.contains("include_supported_languages"), request)
        }
    }

    @Test
    fun `without voice-over, and subtitles only`() {
        FakeApi().route("/api/storesearch/", search).route("/IStoreBrowseService/", getItems(ukTextOnly)).start().use { api ->
            assertEquals("🇺🇦 Українська: інтерфейс і субтитри, без озвучки", provider(api).badge(item(), emptyList())?.text)
        }
        val subsOnly = """{"elanguage":26,"eadditionallanguage":-1,"supported":false,"full_audio":false,"subtitles":true}"""
        FakeApi().route("/api/storesearch/", search).route("/IStoreBrowseService/", getItems(subsOnly)).start().use { api ->
            assertEquals("🇺🇦 Українська: субтитри, без озвучки", provider(api).badge(item(), emptyList())?.text)
        }
    }

    @Test
    fun `no Ukrainian means no line, unless showMissing`() {
        FakeApi().route("/api/storesearch/", search).route("/IStoreBrowseService/", getItems(null)).start().use { api ->
            assertNull(provider(api).badge(item(), emptyList()))
            assertEquals("🇺🇦 Без української", provider(api, showMissing = true).badge(item(), emptyList())?.text)
        }
    }

    @Test
    fun `GetItems failure falls back to the appdetails language list`() {
        val ukAudio = "англійська<strong>*</strong>, українська<strong>*</strong><br><strong>*</strong>повністю озвучено цими мовами"
        FakeApi().route("/api/storesearch/", search).route("/IStoreBrowseService/", "boom", status = 500)
            .route("/api/appdetails", details(ukAudio)).start().use { api ->
                assertEquals("🇺🇦 Українська з озвучкою", provider(api).badge(item(), emptyList())?.text)
            }
        // The real Cyberpunk list: Ukrainian without the asterisk.
        val ukText = "англійська<strong>*</strong>, турецька, українська<br><strong>*</strong>повністю озвучено цими мовами"
        FakeApi().route("/api/storesearch/", search).route("/IStoreBrowseService/", "boom", status = 500)
            .route("/api/appdetails", details(ukText)).start().use { api ->
                assertEquals("🇺🇦 Українська: є", provider(api).badge(item(), emptyList())?.text)
            }
        FakeApi().route("/api/storesearch/", search).route("/IStoreBrowseService/", unknownApp)
            .route("/api/appdetails", details("English<strong>*</strong>, Ukrainian")).start().use { api ->
                assertEquals("🇺🇦 Українська: є", provider(api).badge(item(), emptyList())?.text)
            }
        FakeApi().route("/api/storesearch/", search).route("/IStoreBrowseService/", "boom", status = 500)
            .route("/api/appdetails", details("англійська")).start().use { api ->
                assertNull(provider(api).badge(item(), emptyList()))
                assertEquals("🇺🇦 Без української", provider(api, showMissing = true).badge(item(), emptyList())?.text)
            }
    }

    @Test
    fun `a cached answer needs no HTTP, and the appid search is shared with the Steam badge`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/api/storesearch/", search).route("/IStoreBrowseService/", getItems(ukTextOnly))
            .route("/api/appdetails", details("")).route("/appreviews/", """{"query_summary":{"total_reviews":0}}""").start().use { api ->
                val http = BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF)
                val resolver = SteamAppResolver(http, cache, storeBaseUrl = api.baseUrl)
                val uk = UkLocalizationBadgeProvider(http, cache, resolver, apiBaseUrl = api.baseUrl)
                val steam = SteamBadgeProvider(http, cache, storeBaseUrl = api.baseUrl, resolver = resolver)
                steam.badge(item(), emptyList())
                val first = uk.badge(item(), emptyList())
                val hits = api.hits.get()
                assertEquals(first, uk.badge(item(), emptyList()))
                assertEquals(hits, api.hits.get())
                assertEquals(1, api.hitsFor("/api/storesearch/"))
            }
    }

    @Test
    fun `non-game events and unknown titles make no request to GetItems`() {
        FakeApi().route("/api/storesearch/", search).start().use { api ->
            assertNull(provider(api).badge(item(eventType = "studio_closure"), emptyList()))
            assertNull(provider(api).badge(null, emptyList()))
            assertNull(provider(api).badge(item(subject = "Unknown Game"), emptyList()))
            assertEquals(0, api.hitsFor("/IStoreBrowseService/"))
        }
    }
}
