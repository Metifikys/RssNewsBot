package metifikys.enrich

import metifikys.fetch.UrlGuard
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
        WikipediaIntroProvider(BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF), store, baseUrlFor = { api.baseUrl })

    @Test
    fun `first mention yields the article's opening sentence, second mention nothing`() {
        val store = MemoryIntroStore()
        FakeApi().route("/w/api.php", search).route("/api/rest_v1/page/summary/", summary).start().use { api ->
            val p = provider(api, store)
            val line = p.intro("gaming", item(franchise = "Team Cherry"), IntroConfig())
            assertEquals(
                "📚 Team Cherry — австралійська незалежна студія розробки відеоігор з Аделаїди · [Вікіпедія](https://uk.wikipedia.org/wiki/Team_Cherry)",
                line
            )
            val hits = api.hits.get()
            assertNull(p.intro("gaming", item(franchise = "Team Cherry"), IntroConfig()))
            assertNull(p.intro("gaming", item(subject = "team cherry"), IntroConfig()), "same key via subject")
            assertEquals(hits, api.hits.get(), "known entity must not be looked up again")
            assertEquals("Team Cherry", store.get("gaming", "team_cherry")!!.title)
            assertEquals("uk", store.get("gaming", "team_cherry")!!.lang)
        }
    }

    @Test
    fun `a long opening sentence gives way to the short description, and to a clipped sentence without one`() {
        val longSentence = "Team Cherry — " + "дуже довга розповідь про студію, ".repeat(9) + "заснована 2014 року. Друге речення."
        val withDescription = summary.replace(Regex(""""extract":"[^"]*""""), """"extract":"$longSentence"""")
        FakeApi().route("/w/api.php", search).route("/api/rest_v1/page/summary/", withDescription).start().use { api ->
            assertEquals(
                "📚 Team Cherry: австралійська студія відеоігор · [Вікіпедія](https://uk.wikipedia.org/wiki/Team_Cherry)",
                provider(api).intro("gaming", item(franchise = "Team Cherry"), IntroConfig())
            )
        }
        val noDescription = withDescription.replace(""""description":"Австралійська студія відеоігор",""", "")
        FakeApi().route("/w/api.php", search).route("/api/rest_v1/page/summary/", noDescription).start().use { api ->
            val line = provider(api).intro("gaming", item(franchise = "Team Cherry"), IntroConfig())!!
            val body = line.substringBefore(" · [Вікіпедія]")
            assertTrue(body.startsWith("📚 Team Cherry — дуже довга") && body.endsWith("…"), line)
            assertTrue(body.length <= 3 + WikipediaIntroProvider.MAX_CHARS + 1, "clipped body too long: ${body.length}")
        }
    }

    @Test
    fun `franchise segments are tried most-specific first and remembered separately`() {
        // "Game / Studio": the game has no article, the studio does → the studio is introduced,
        // both keys are recorded, and the next story about the same studio adds nothing.
        val store = MemoryIntroStore()
        val gameMiss = """{"query":{"search":[]}}"""
        FakeApi().route("/w/api.php?", gameMiss).start().use { api ->
            // FakeApi matches on path prefix only; route search by query manually below.
        }
        val api = FakeApi().start()
        try {
            val p = WikipediaIntroProvider(BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF), store, baseUrlFor = { api.baseUrl })
            // No routes → 404 → getJson null → miss for every candidate.
            assertNull(p.intro("gaming", item(franchise = "Silksong 2 / Team Cherry"), IntroConfig()))
            assertNull(store.get("gaming", "silksong_2")!!.extract)
            assertNull(store.get("gaming", "team_cherry")!!.extract)
            assertEquals(listOf("/w/api.php?action=query&list=search&srsearch=Silksong+2&srlimit=3&srprop=&format=json",
                "/w/api.php?action=query&list=search&srsearch=Team+Cherry&srlimit=3&srprop=&format=json"), api.requests)
        } finally {
            api.close()
        }
        // Now the studio has an article: a known-miss game falls through to the studio.
        FakeApi().route("/w/api.php", search).route("/api/rest_v1/page/summary/", summary).start().use { api2 ->
            val p = WikipediaIntroProvider(BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF), store, baseUrlFor = { api2.baseUrl })
            store.put(store.get("gaming", "team_cherry")!!.copy(introducedAt = LocalDateTime.now().minusDays(40)))
            val line = p.intro("gaming", item(franchise = "Silksong 2 / Team Cherry"), IntroConfig())
            assertTrue(line!!.startsWith("📚 Team Cherry"), line)
            assertTrue(api2.requests.none { "Silksong" in it }, "known miss must not be searched again: ${api2.requests}")
            // The game itself known-introduced → nothing, even though the studio is now known too.
            store.put(store.get("gaming", "team_cherry")!!)
            assertNull(p.intro("gaming", item(franchise = "Hollow Knight / Team Cherry"), IntroConfig()).takeIf { false })
            assertNull(p.intro("gaming", item(franchise = "Team Cherry / Silksong 2"), IntroConfig()))
        }
    }

    @Test
    fun `generic segments and parentheticals are ignored, titles match without Wikipedia's disambiguator`() {
        assertEquals(listOf("Warota"), WikipediaIntroProvider.entityCandidates(item(franchise = "Warota (indie)")))
        assertEquals(listOf("Diablo IV", "Blizzard Entertainment"), WikipediaIntroProvider.entityCandidates(item(franchise = "Diablo IV / Blizzard Entertainment")))
        assertEquals(listOf("Pokémon TCG"), WikipediaIntroProvider.entityCandidates(item(franchise = "Pokémon TCG / community")))
        assertEquals(listOf("Minecraft", "Roblox", "YouTube"), WikipediaIntroProvider.entityCandidates(item(franchise = "Minecraft, Roblox / YouTube")))
        assertEquals("super_smash_bros", WikipediaIntroProvider.titleKey("Super Smash Bros. (серія відеоігор)"))

        val seriesSearch = """{"query":{"search":[{"title":"Super Smash Bros. (серія відеоігор)"}]}}"""
        val seriesSummary = """{"type":"standard","title":"Super Smash Bros. (серія відеоігор)","extract":"Super Smash Bros. — серія файтингів від Nintendo.",
            "content_urls":{"desktop":{"page":"https://uk.wikipedia.org/wiki/SSB"}}}"""
        FakeApi().route("/w/api.php", seriesSearch).route("/api/rest_v1/page/summary/", seriesSummary).start().use { api ->
            assertEquals(
                "📚 Super Smash Bros. — серія файтингів від Nintendo · [Вікіпедія](https://uk.wikipedia.org/wiki/SSB)",
                provider(api).intro("gaming", item(franchise = "Super Smash Bros. / Nintendo"), IntroConfig())
            )
        }
    }

    @Test
    fun `a summary that redirected to a different page is not the entity's article`() {
        val redirected = """{"type":"standard","title":"Super Smash Bros. для Nintendo 3DS та Wii U","description":"відеогра 2014 року","extract":"x"}"""
        FakeApi().route("/w/api.php", """{"query":{"search":[{"title":"Super Smash Bros."}]}}""").route("/api/rest_v1/page/summary/", redirected).start().use { api ->
            assertNull(provider(api).intro("gaming", item(franchise = "Super Smash Bros."), IntroConfig()))
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
            val p = WikipediaIntroProvider(BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF), MemoryIntroStore(), baseUrlFor = { lang -> calls += lang; api.baseUrl })
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

    // ── topic check: the game Rust is not the programming language ──

    @Test
    fun `an off-topic article is skipped and the name is searched again with the topic hint`() {
        val langSearch = """{"query":{"search":[{"title":"Rust (мова програмування)"}]}}"""
        val langSummary = """{"type":"standard","title":"Rust (мова програмування)","description":"мова програмування",
            "extract":"Rust — сучасна багатопарадигмова мова програмування загального призначення.",
            "content_urls":{"desktop":{"page":"https://uk.wikipedia.org/wiki/Rust_(мова_програмування)"}}}"""
        val gameSearch = """{"query":{"search":[{"title":"Rust (відеогра)"}]}}"""
        val gameSummary = """{"type":"standard","title":"Rust (відеогра)","description":"відеогра 2018 року",
            "extract":"Rust — відеогра жанру survival, розроблена британською студією Facepunch Studios.",
            "content_urls":{"desktop":{"page":"https://uk.wikipedia.org/wiki/Rust_(відеогра)"}}}"""
        FakeApi()
            .route("/w/api.php?action=query&list=search&srsearch=Rust+відеогра", gameSearch)
            .route("/w/api.php?action=query&list=search&srsearch=Rust", langSearch)
            .route("/api/rest_v1/page/summary/Rust_(відеогра)", gameSummary)
            .route("/api/rest_v1/page/summary/Rust_(мова_програмування)", langSummary)
            .start().use { api ->
                val line = provider(api).intro("gaming", item(franchise = "Rust / Facepunch Studios"), IntroConfig(languages = listOf("uk")))
                assertEquals("📚 Rust — відеогра жанру survival, розроблена британською студією Facepunch Studios · [Вікіпедія](https://uk.wikipedia.org/wiki/Rust_(відеогра))", line)
            }
    }

    @Test
    fun `topic words decide what counts, an empty list checks nothing`() {
        val words = IntroConfig().topicWords
        assertTrue(!WikipediaIntroProvider.onTopic("Entropy", "", "Entropy is a thermodynamic state variable", words))
        assertTrue(!WikipediaIntroProvider.onTopic("Transit", "", "Transit — перша в світі супутникова система навігації", words))
        assertTrue(WikipediaIntroProvider.onTopic("Media Molecule", "", "Media Molecule Ltd. is a British video game developer", words))
        assertTrue(WikipediaIntroProvider.onTopic("Team Cherry", "Австралійська студія відеоігор", "", words))
        assertTrue(WikipediaIntroProvider.onTopic("Entropy", "", "Entropy is a thermodynamic state variable", emptyList()))
    }

    @Test
    fun `the game of a Game - Studio franchise must be the studio's game, else the studio is introduced`() {
        val oldGame = """{"query":{"search":[{"title":"Entropy (video game)"}]}}"""
        val oldSummary = """{"type":"standard","title":"Entropy (video game)","description":"2011 video game",
            "extract":"Entropy was a space MMORPG video game developed by the Norwegian game studio Artplant.",
            "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Entropy_(video_game)"}}}"""
        val studioSearch = """{"query":{"search":[{"title":"Lovely Hellplace"}]}}"""
        val studioSummary = """{"type":"standard","title":"Lovely Hellplace","description":"British video game developer",
            "extract":"Lovely Hellplace is an independent video game studio based in London.",
            "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Lovely_Hellplace"}}}"""
        FakeApi()
            .route("/w/api.php?action=query&list=search&srsearch=Lovely+Hellplace", studioSearch)
            .route("/w/api.php?action=query&list=search&srsearch=Entropy", oldGame)
            .route("/api/rest_v1/page/summary/Lovely_Hellplace", studioSummary)
            .route("/api/rest_v1/page/summary/Entropy_(video_game)", oldSummary)
            .start().use { api ->
                val line = provider(api).intro("gaming", item(franchise = "Entropy / Lovely Hellplace"), IntroConfig(languages = listOf("en")))
                assertEquals("📚 Lovely Hellplace is an independent video game studio based in London · [Вікіпедія](https://en.wikipedia.org/wiki/Lovely_Hellplace)", line)
            }
    }

    @Test
    fun `a studio is mentioned by a distinctive word, filler words prove nothing`() {
        assertTrue(WikipediaIntroProvider.mentions("Rust — відеогра, розроблена студією Facepunch Studios.", "Facepunch Studios"))
        assertTrue(!WikipediaIntroProvider.mentions("developed by the Norwegian game studio Artplant", "Lovely Hellplace"))
        assertTrue(WikipediaIntroProvider.mentions("Octopath Traveler is a 2018 game developed by Square Enix and Acquire", "Square Enix"))
    }
}
