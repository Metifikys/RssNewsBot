package metifikys.enrich

import metifikys.config.IntroConfig
import metifikys.db.EntityIntroRow
import metifikys.model.ShortlistItem
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikipediaIntroProviderTest {

    private fun item(franchise: String = "", subject: String = "") = ShortlistItem(
        eventKey = "evt", franchise = franchise, subject = subject, coreFact = "f", url = "https://example.com/1", status = "new"
    )

    private val search = """{"query":{"search":[{"title":"Team Cherry"},{"title":"Cherry (band)"}]}}"""
    private val summary = """{"type":"standard","title":"Team Cherry","description":"Австралійська студія відеоігор",
        "extract":"Team Cherry — австралійська незалежна студія розробки відеоігор з Аделаїди. Заснована 2014 року.",
        "content_urls":{"desktop":{"page":"https://uk.wikipedia.org/wiki/Team_Cherry"}}}"""

    private fun provider(api: FakeApi, store: IntroStore = MemoryIntroStore()) =
        WikipediaIntroProvider(BadgeHttp(timeoutSeconds = 3), store, baseUrlFor = { api.baseUrl })

    @Test
    fun `first mention yields a line from the short description, second mention nothing`() {
        val store = MemoryIntroStore()
        FakeApi().route("/w/api.php", search).route("/api/rest_v1/page/summary/", summary).start().use { api ->
            val p = provider(api, store)
            val line = p.intro("gaming", item(franchise = "Team Cherry"), IntroConfig())
            assertEquals("📚 Team Cherry: австралійська студія відеоігор · [Вікіпедія](https://uk.wikipedia.org/wiki/Team_Cherry)", line)
            val hits = api.hits.get()
            assertNull(p.intro("gaming", item(franchise = "Team Cherry"), IntroConfig()))
            assertNull(p.intro("gaming", item(subject = "team cherry"), IntroConfig()), "same key via subject")
            assertEquals(hits, api.hits.get(), "known entity must not be looked up again")
            assertEquals("Team Cherry", store.get("gaming", "team_cherry")!!.title)
            assertEquals("uk", store.get("gaming", "team_cherry")!!.lang)
        }
    }

    @Test
    fun `falls back to the first sentence of the extract when there is no description`() {
        val noDescription = summary.replace(""""description":"Австралійська студія відеоігор",""", "")
        FakeApi().route("/w/api.php", search).route("/api/rest_v1/page/summary/", noDescription).start().use { api ->
            val line = provider(api).intro("gaming", item(franchise = "Team Cherry"), IntroConfig())
            assertEquals(
                "📚 Team Cherry — австралійська незалежна студія розробки відеоігор з Аделаїди. · [Вікіпедія](https://uk.wikipedia.org/wiki/Team_Cherry)",
                line
            )
        }
    }

    @Test
    fun `a miss is recorded and not searched again for a month, a hit is repeated after repeatAfterDays`() {
        val store = MemoryIntroStore()
        FakeApi().route("/w/api.php", """{"query":{"search":[{"title":"Something Else Entirely"}]}}""").start().use { api ->
            val p = provider(api, store)
            assertNull(p.intro("gaming", item(franchise = "Nobody Studio"), IntroConfig()))
            val hits = api.hits.get()
            assertNull(p.intro("gaming", item(franchise = "Nobody Studio"), IntroConfig()))
            assertEquals(hits, api.hits.get())
            assertNull(store.get("gaming", "nobody_studio")!!.extract)

            // Age the miss past the retry window → searched again.
            store.put(store.get("gaming", "nobody_studio")!!.copy(introducedAt = LocalDateTime.now().minusDays(40)))
            assertNull(p.intro("gaming", item(franchise = "Nobody Studio"), IntroConfig()))
            assertTrue(api.hits.get() > hits)
        }
        FakeApi().route("/w/api.php", search).route("/api/rest_v1/page/summary/", summary).start().use { api ->
            val p = provider(api, store)
            store.put(EntityIntroRow("gaming", "team_cherry", "Team Cherry", "x", "u", "uk", LocalDateTime.now().minusDays(400)))
            assertTrue(p.intro("gaming", item(franchise = "Team Cherry"), IntroConfig(repeatAfterDays = 365))!!.startsWith("📚 Team Cherry"))
            assertNull(p.intro("gaming", item(franchise = "Team Cherry"), IntroConfig(repeatAfterDays = 365)))
        }
    }

    @Test
    fun `disambiguation pages and non-matching titles are skipped, languages are tried in order`() {
        val disambiguation = """{"type":"disambiguation","title":"Team Cherry","extract":"may refer to"}"""
        val calls = mutableListOf<String>()
        FakeApi().route("/w/api.php", search).route("/api/rest_v1/page/summary/", disambiguation).start().use { api ->
            val p = WikipediaIntroProvider(BadgeHttp(timeoutSeconds = 3), MemoryIntroStore(), baseUrlFor = { lang -> calls += lang; api.baseUrl })
            assertNull(p.intro("gaming", item(franchise = "Team Cherry"), IntroConfig(languages = listOf("uk", "en"))))
            assertEquals(listOf("uk", "en"), calls)
        }
    }

    @Test
    fun `a transport failure records nothing so the next digest retries`() {
        val store = MemoryIntroStore()
        FakeApi().route("/w/api.php", "down", status = 503).start().use { api ->
            assertNull(provider(api, store).intro("gaming", item(franchise = "Team Cherry"), IntroConfig()))
            assertNull(store.get("gaming", "team_cherry"))
        }
    }

    @Test
    fun `entity name prefers the franchise and needs at least two letters`() {
        assertEquals("Team Cherry", WikipediaIntroProvider.entityName(item(franchise = "Team Cherry", subject = "Silksong")))
        assertEquals("Silksong", WikipediaIntroProvider.entityName(item(subject = "Silksong")))
        assertNull(WikipediaIntroProvider.entityName(item(franchise = "X", subject = "42")))
        assertTrue(TitleMatch.similar("team_cherry", "team_cherry"))
        assertTrue(TitleMatch.similar("the_witcher_3_wild_hunt", "witcher_3_wild_hunt"))
        assertTrue(!TitleMatch.similar("cherry_band", "team_cherry"))
    }
}
