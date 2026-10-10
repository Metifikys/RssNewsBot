package metifikys.ai.dedup

import io.mockk.*
import metifikys.ai.BillingException
import metifikys.ai.OpenAI
import metifikys.config.CategoryConfig
import metifikys.config.DedupConfig
import metifikys.config.DigestConfig
import metifikys.config.FeedConfig
import metifikys.config.RankerConfig
import metifikys.db.CoveredEventRow
import metifikys.db.NewsDatabase
import metifikys.model.Article
import metifikys.model.ShortlistItem
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventExtractorTest {

    private lateinit var openAI: OpenAI
    private lateinit var promptLoader: PromptLoader
    private lateinit var db: NewsDatabase
    private lateinit var extractor: EventExtractor

    @BeforeEach
    fun setup() {
        openAI = mockk()
        promptLoader = mockk()
        db = mockk(relaxed = true)
        every { openAI.isBatchCapable } returns false
        extractor = EventExtractor(openAI, promptLoader, db)
    }

    private fun article(link: String) = Article(
        category = "games",
        title = "Title $link",
        link = link,
        description = "desc for $link",
        pubDate = LocalDateTime.now()
    )

    private fun cat(dedup: DedupConfig? = DedupConfig(promptFile = "any.yaml")): CategoryConfig =
        CategoryConfig(
            emoji = "🎮",
            feeds = listOf(FeedConfig("https://example.com/rss")),
            channelId = "@ch",
            dedup = dedup
        )

    private val okResolved = ResolvedDedupPrompts(
        extractSystem = "SYS:{{CATEGORY}}",
        extractUser = "BATCH={{CURRENT_BATCH_JSON}} COVERED={{PREVIOUSLY_COVERED_EVENTS_JSON}}",
        renderSystem = "RS",
        renderUser = "RU",
        contextDays = 7,
        maxContextEvents = 50
    )

    @Test
    fun `extract returns FallbackToLegacy when prompts are unresolvable`() {
        every { promptLoader.resolve(any()) } returns null

        val result = extractor.extract("games", cat(), listOf(article("https://a.com/1")))

        assertIs<ExtractOutcome.FallbackToLegacy>(result)
        verify(exactly = 0) { openAI.completeJson(any(), any(), any()) }
    }

    @Test
    fun `extract returns parsed result on valid JSON`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()

        val json = """
        {
          "extractions": [
            {
              "articleIndex": 0,
              "eventKey": "elden-ring|release|dlc",
              "coreFact": "новий DLC",
              "importance": 7,
              "url": "https://a.com/1",
              "status": "new"
            }
          ],
          "shortlist": [
            {
              "eventKey": "elden-ring|release|dlc",
              "coreFact": "новий DLC",
              "importance": 7,
              "url": "https://a.com/1",
              "status": "new",
              "articleIndices": [0]
            }
          ]
        }
        """.trimIndent()
        every { openAI.completeJson(any(), any(), any()) } returns json

        val result = extractor.extract("games", cat(), listOf(article("https://a.com/1")))

        val ready = assertIs<ExtractOutcome.Ready>(result)
        assertEquals(1, ready.result.shortlist.size)
        assertEquals("elden-ring|release|dlc", ready.result.shortlist[0].eventKey)
    }

    @Test
    fun `extract propagates InterruptedException instead of falling back to legacy`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } throws InterruptedException("cancelled")

        assertFailsWith<InterruptedException> {
            extractor.extract("games", cat(), listOf(article("https://a.com/1")))
        }
        // Thread.interrupted() also clears the flag so it doesn't leak into other tests.
        assertTrue(Thread.interrupted(), "interrupt flag must be restored")
    }

    @Test
    fun `extract returns FallbackToLegacy on malformed JSON`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns "not json"

        val result = extractor.extract("games", cat(), listOf(article("https://a.com/1")))

        assertIs<ExtractOutcome.FallbackToLegacy>(result)
    }

    @Test
    fun `extract returns FallbackToLegacy on blank LLM output`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns "  "

        val result = extractor.extract("games", cat(), listOf(article("https://a.com/1")))

        assertIs<ExtractOutcome.FallbackToLegacy>(result)
    }

    @Test
    fun `extract filters hallucinated URLs from shortlist`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()

        val json = """
        {
          "extractions": [],
          "shortlist": [
            {"eventKey":"k1","coreFact":"x","url":"https://a.com/1","status":"new"},
            {"eventKey":"k2","coreFact":"y","url":"https://evil.invented/url","status":"new"}
          ]
        }
        """.trimIndent()
        every { openAI.completeJson(any(), any(), any()) } returns json

        val result = extractor.extract("games", cat(), listOf(article("https://a.com/1")))

        val ready = assertIs<ExtractOutcome.Ready>(result)
        assertEquals(1, ready.result.shortlist.size)
        assertEquals("k1", ready.result.shortlist[0].eventKey)
    }

    @Test
    fun `extract empties shortlist when all URLs are hallucinated`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()

        val json = """
        {
          "extractions": [],
          "shortlist": [
            {"eventKey":"k","coreFact":"x","url":"https://fake.invalid/1","status":"new"}
          ]
        }
        """.trimIndent()
        every { openAI.completeJson(any(), any(), any()) } returns json

        val result = extractor.extract("games", cat(), listOf(article("https://a.com/1")))

        val ready = assertIs<ExtractOutcome.Ready>(result)
        assertTrue(ready.result.shortlist.isEmpty())
    }

    @Test
    fun `extract propagates BillingException`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } throws BillingException("quota")

        assertFailsWith<BillingException> {
            extractor.extract("games", cat(), listOf(article("https://a.com/1")))
        }
    }

    @Test
    fun `extract queries fetchRecentEvents with resolved config values`() {
        every { promptLoader.resolve(any()) } returns okResolved.copy(contextDays = 3, maxContextEvents = 42)
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns "{}"

        extractor.extract("games", cat(), listOf(article("https://a.com/1")))

        verify { db.fetchRecentEvents("games", 3, 42) }
    }

    @Test
    fun `extract sends every second request to alternate provider`() {
        val openRouter = mockk<OpenAI>()
        every { openRouter.isBatchCapable } returns false
        extractor = EventExtractor(openAI, promptLoader, db, alternateOpenAI = openRouter)
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns """
        {
          "extractions": [],
          "shortlist": [
            {"eventKey":"primary","coreFact":"x","url":"https://a.com/1","status":"new"}
          ]
        }
        """.trimIndent()
        every { openRouter.completeJson(any(), any(), any()) } returns """
        {
          "extractions": [],
          "shortlist": [
            {"eventKey":"alternate","coreFact":"x","url":"https://a.com/1","status":"new"}
          ]
        }
        """.trimIndent()

        val first = extractor.extract("games", cat(), listOf(article("https://a.com/1")))
        val second = extractor.extract("games", cat(), listOf(article("https://a.com/1")))
        val third = extractor.extract("games", cat(), listOf(article("https://a.com/1")))

        assertEquals("primary", assertIs<ExtractOutcome.Ready>(first).result.shortlist.single().eventKey)
        assertEquals("alternate", assertIs<ExtractOutcome.Ready>(second).result.shortlist.single().eventKey)
        assertEquals("primary", assertIs<ExtractOutcome.Ready>(third).result.shortlist.single().eventKey)
        verify(exactly = 2) { openAI.completeJson(any(), any(), any()) }
        verify(exactly = 1) { openRouter.completeJson(any(), any(), any()) }
    }

    @Test
    fun `extract serializes covered events and substitutes into user prompt`() {
        every { promptLoader.resolve(any()) } returns okResolved
        val captured = slot<Map<String, String>>()
        every { promptLoader.substitute(any(), capture(captured)) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns listOf(
            CoveredEventRow(
                category = "games",
                eventKey = "prior-key",
                subject = "s",
                franchise = "f",
                eventType = "release",
                coreFact = "старий факт",
                importance = 5,
                url = "https://old.com/1",
                coveredAt = LocalDateTime.now().minusDays(1)
            )
        )
        every { openAI.completeJson(any(), any(), any()) } returns "{}"

        extractor.extract("games", cat(), listOf(article("https://a.com/1")))

        val vars = captured.captured
        assertTrue(vars.containsKey("CURRENT_BATCH_JSON"))
        assertTrue(vars.containsKey("PREVIOUSLY_COVERED_EVENTS_JSON"))
        assertTrue(vars["PREVIOUSLY_COVERED_EVENTS_JSON"]!!.contains("prior-key"))
        assertTrue(vars["CURRENT_BATCH_JSON"]!!.contains("\"index\":0"))
        assertEquals("games", vars["CATEGORY"])
    }

    @Test
    fun `a converted-feed article carries its source hint, a plain article has no source key`() {
        every { promptLoader.resolve(any()) } returns okResolved
        val captured = slot<Map<String, String>>()
        every { promptLoader.substitute(any(), capture(captured)) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns "{}"
        val social = article("https://a.com/2").copy(
            metaJson = metifikys.model.ArticleMeta(source = metifikys.model.SourceMeta(kind = "social", label = "Studio X")).toJson()
        )

        extractor.extract("games", cat(), listOf(article("https://a.com/1"), social))

        val batch = captured.captured.getValue("CURRENT_BATCH_JSON")
        assertTrue(batch.contains("\"source\":\"official account: Studio X\""), batch)
        assertEquals(1, Regex("\"source\"").findAll(batch).count(), batch)
    }

    @Test
    fun `extract drops recent meaningful update below override threshold`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns listOf(
            CoveredEventRow(
                category = "games",
                eventKey = "prior-key",
                subject = "Genshin Impact",
                franchise = "Genshin Impact",
                eventType = "major_announcement",
                coreFact = "старий факт",
                importance = 4,
                newsworthiness = 4,
                digestFit = 4,
                url = "https://old.com/1",
                coveredAt = LocalDateTime.now().minusMinutes(15)
            )
        )
        every { openAI.completeJson(any(), any(), any()) } returns """
        {
          "extractions": [],
          "shortlist": [
            {
              "eventKey": "genshin_follow_up",
              "coreFact": "нові деталі",
              "importance": 7,
              "newsworthiness": 7,
              "digestFit": 6,
              "relatedPreviousEventKey": "prior-key",
              "url": "https://a.com/1",
              "status": "meaningful_update",
              "articleIndices": [0]
            }
          ]
        }
        """.trimIndent()

        val result = extractor.extract(
            "games",
            cat(
                DedupConfig(
                    promptFile = "any.yaml",
                    digest = DigestConfig(
                        ranker = RankerConfig(enabled = false),
                        meaningfulUpdateCooldownMinutes = 90,
                        newsworthinessOverride = 8
                    )
                )
            ),
            listOf(article("https://a.com/1"))
        )

        val ready = assertIs<ExtractOutcome.Ready>(result)
        assertTrue(ready.result.shortlist.isEmpty())
    }

    private fun updateShortlistJson(nw: Int = 9) = """
        {
          "extractions": [],
          "shortlist": [
            {
              "eventKey": "doom_crunch_update",
              "coreFact": "ще одна цитата",
              "newsworthiness": $nw,
              "digestFit": 7,
              "relatedPreviousEventKey": "doom_crunch",
              "url": "https://a.com/1",
              "status": "meaningful_update",
              "articleIndices": [0]
            },
            {
              "eventKey": "fresh",
              "coreFact": "нова подія",
              "newsworthiness": 6,
              "digestFit": 7,
              "url": "https://a.com/2",
              "status": "new",
              "articleIndices": [1]
            }
          ]
        }
    """.trimIndent()

    private fun capCat(maxPosts: Int?) = cat(
        DedupConfig(
            promptFile = "any.yaml",
            digest = DigestConfig(
                ranker = RankerConfig(enabled = false),
                meaningfulUpdateCooldownMinutes = 0,
                maxPostsPerEvent = maxPosts,
                updateWindowDays = 7
            )
        )
    )

    @Test
    fun `maxPostsPerEvent drops an update once the story has that many posts, override or not`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns updateShortlistJson(nw = 10)
        every { db.countDigestPostsByEventKeys("games", setOf("doom_crunch_update", "doom_crunch"), any()) } returns
            mapOf("doom_crunch" to 2)

        val result = extractor.extract("games", capCat(maxPosts = 2), listOf(article("https://a.com/1"), article("https://a.com/2")))

        val ready = assertIs<ExtractOutcome.Ready>(result)
        assertEquals(listOf("fresh"), ready.result.shortlist.map { it.eventKey })
    }

    @Test
    fun `maxPostsPerEvent keeps an update below the cap and sums own plus related key`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns updateShortlistJson()
        every { db.countDigestPostsByEventKeys(any(), any(), any()) } returns mapOf("doom_crunch" to 1, "doom_crunch_update" to 1)

        val kept = assertIs<ExtractOutcome.Ready>(
            extractor.extract("games", capCat(maxPosts = 3), listOf(article("https://a.com/1"), article("https://a.com/2")))
        )
        assertEquals(listOf("doom_crunch_update", "fresh"), kept.result.shortlist.map { it.eventKey })

        val dropped = assertIs<ExtractOutcome.Ready>(
            extractor.extract("games", capCat(maxPosts = 2), listOf(article("https://a.com/1"), article("https://a.com/2")))
        )
        assertEquals(listOf("fresh"), dropped.result.shortlist.map { it.eventKey })
    }

    @Test
    fun `eventDate survives only when well-formed and its year appears in the article`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns """
        {
          "extractions": [],
          "shortlist": [
            {"eventKey": "grounded", "coreFact": "f", "url": "https://a.com/1", "status": "new", "articleIndices": [0], "eventDate": "2026-10-14"},
            {"eventKey": "invented", "coreFact": "f", "url": "https://a.com/2", "status": "new", "articleIndices": [1], "eventDate": "2031-03-01"},
            {"eventKey": "malformed", "coreFact": "f", "url": "https://a.com/3", "status": "new", "articleIndices": [2], "eventDate": "next spring"},
            {"eventKey": "quarter", "coreFact": "f", "url": "https://a.com/4", "status": "new", "articleIndices": [3], "eventDate": "2027-q1"}
          ]
        }
        """.trimIndent()

        val articles = listOf(
            article("https://a.com/1").copy(description = "Silksong lands on 14 October 2026."),
            article("https://a.com/2").copy(description = "No date mentioned anywhere."),
            article("https://a.com/3").copy(description = "Coming in 2027."),
            article("https://a.com/4").copy(title = "Delayed to Q1 2027")
        )
        val ready = assertIs<ExtractOutcome.Ready>(extractor.extract("games", cat(), articles))
        val byKey = ready.result.shortlist.associateBy { it.eventKey }
        assertEquals("2026-10-14", byKey.getValue("grounded").eventDate)
        assertEquals(null, byKey.getValue("invented").eventDate)
        assertEquals(null, byKey.getValue("malformed").eventDate)
        assertEquals("2027-Q1", byKey.getValue("quarter").eventDate)
    }

    @Test
    fun `without maxPostsPerEvent there is no post-count lookup`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        // The story is covered, so the orphan-update check is settled without digest_messages.
        every { db.fetchCoveredEventsByKeys(any(), any(), any()) } returns listOf(
            CoveredEventRow(
                category = "games", eventKey = "doom_crunch", subject = "DOOM", franchise = "DOOM", eventType = "t",
                coreFact = "c", importance = 6, url = "https://old.com/doom", coveredAt = LocalDateTime.now().minusDays(2)
            )
        )
        every { openAI.completeJson(any(), any(), any()) } returns updateShortlistJson()

        val ready = assertIs<ExtractOutcome.Ready>(
            extractor.extract("games", capCat(maxPosts = null), listOf(article("https://a.com/1"), article("https://a.com/2")))
        )
        assertEquals(2, ready.result.shortlist.size)
        verify(exactly = 0) { db.countDigestPostsByEventKeys(any(), any(), any()) }
    }

    @Test
    fun `extract keeps recent meaningful update when it clears override threshold`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns listOf(
            CoveredEventRow(
                category = "games",
                eventKey = "prior-key",
                subject = "Genshin Impact",
                franchise = "Genshin Impact",
                eventType = "major_announcement",
                coreFact = "старий факт",
                importance = 4,
                newsworthiness = 4,
                digestFit = 4,
                url = "https://old.com/1",
                coveredAt = LocalDateTime.now().minusMinutes(15)
            )
        )
        every { openAI.completeJson(any(), any(), any()) } returns """
        {
          "extractions": [],
          "shortlist": [
            {
              "eventKey": "genshin_big_follow_up",
              "coreFact": "дуже важливі нові деталі",
              "importance": 8,
              "newsworthiness": 8,
              "digestFit": 7,
              "relatedPreviousEventKey": "prior-key",
              "url": "https://a.com/1",
              "status": "meaningful_update",
              "articleIndices": [0]
            }
          ]
        }
        """.trimIndent()

        val result = extractor.extract(
            "games",
            cat(
                DedupConfig(
                    promptFile = "any.yaml",
                    digest = DigestConfig(
                        ranker = RankerConfig(enabled = false),
                        meaningfulUpdateCooldownMinutes = 90,
                        newsworthinessOverride = 8
                    )
                )
            ),
            listOf(article("https://a.com/1"))
        )

        val ready = assertIs<ExtractOutcome.Ready>(result)
        assertEquals(1, ready.result.shortlist.size)
        assertEquals("genshin_big_follow_up", ready.result.shortlist.single().eventKey)
    }

    @Test
    fun `cooldown finds the prior event by key when it has scrolled out of the prompt context`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        // Prompt context (bounded by maxContextEvents) no longer holds the prior event…
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        // …but the keyed lookup still does: covered 20h ago, inside a 4-day cooldown.
        every { db.fetchCoveredEventsByKeys("games", setOf("doom_crunch"), any()) } returns listOf(
            CoveredEventRow(
                category = "games",
                eventKey = "doom_crunch",
                subject = "DOOM",
                franchise = "DOOM",
                eventType = "industry_news",
                coreFact = "кранч",
                importance = 6,
                newsworthiness = 6,
                digestFit = 6,
                url = "https://old.com/doom",
                coveredAt = LocalDateTime.now().minusHours(20)
            )
        )
        every { openAI.completeJson(any(), any(), any()) } returns """
        {
          "extractions": [],
          "shortlist": [
            {
              "eventKey": "doom_crunch",
              "coreFact": "нові деталі",
              "importance": 6,
              "newsworthiness": 6,
              "digestFit": 6,
              "relatedPreviousEventKey": null,
              "url": "https://a.com/1",
              "status": "meaningful_update",
              "articleIndices": [0]
            }
          ]
        }
        """.trimIndent()

        val result = extractor.extract(
            "games",
            cat(
                DedupConfig(
                    promptFile = "any.yaml",
                    digest = DigestConfig(
                        ranker = RankerConfig(enabled = false),
                        meaningfulUpdateCooldownMinutes = 4 * 24 * 60,
                        newsworthinessOverride = 8
                    )
                )
            ),
            listOf(article("https://a.com/1"))
        )

        val ready = assertIs<ExtractOutcome.Ready>(result)
        assertTrue(ready.result.shortlist.isEmpty())
        // The lookup window is the cooldown itself — older rows cannot trigger it anyway.
        val since = slot<LocalDateTime>()
        verify { db.fetchCoveredEventsByKeys("games", setOf("doom_crunch"), capture(since)) }
        val expected = LocalDateTime.now().minusMinutes(4 * 24 * 60)
        assertTrue(java.time.Duration.between(expected, since.captured).abs().toMinutes() < 5)
    }

    @Test
    fun `cooldown skips the keyed lookup when every related key is already in the prompt context`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns listOf(
            CoveredEventRow(
                category = "games",
                eventKey = "prior-key",
                subject = "S",
                franchise = "F",
                eventType = "t",
                coreFact = "c",
                importance = 4,
                url = "https://old.com/1",
                coveredAt = LocalDateTime.now().minusDays(3)
            )
        )
        every { openAI.completeJson(any(), any(), any()) } returns """
        {
          "extractions": [],
          "shortlist": [
            {
              "eventKey": "follow_up",
              "coreFact": "нові деталі",
              "importance": 5,
              "newsworthiness": 5,
              "digestFit": 5,
              "relatedPreviousEventKey": "prior-key",
              "url": "https://a.com/1",
              "status": "meaningful_update",
              "articleIndices": [0]
            }
          ]
        }
        """.trimIndent()

        val result = extractor.extract(
            "games",
            cat(
                DedupConfig(
                    promptFile = "any.yaml",
                    digest = DigestConfig(
                        ranker = RankerConfig(enabled = false),
                        meaningfulUpdateCooldownMinutes = 90
                    )
                )
            ),
            listOf(article("https://a.com/1"))
        )

        // 3 days > 90 min cooldown → kept; and no DB round-trip was needed.
        val ready = assertIs<ExtractOutcome.Ready>(result)
        assertEquals(1, ready.result.shortlist.size)
        verify(exactly = 0) { db.fetchCoveredEventsByKeys(any(), any(), any()) }
    }

    // ── meaningful_update without an earlier post ────────────────────────────

    private fun updatesJson(vararg items: Pair<String, String?>, newKey: String? = null): String {
        val updates = items.mapIndexed { i, (key, related) ->
            """{"eventKey": "$key", "coreFact": "c", "importance": 6, "newsworthiness": 6, "digestFit": 6,
               "relatedPreviousEventKey": ${related?.let { "\"$it\"" } ?: "null"}, "url": "https://a.com/$i",
               "status": "meaningful_update", "articleIndices": [$i]}"""
        }
        val fresh = newKey?.let {
            listOf("""{"eventKey": "$it", "coreFact": "c", "importance": 6, "newsworthiness": 6, "digestFit": 6,
               "url": "https://a.com/${items.size}", "status": "new", "articleIndices": [${items.size}]}""")
        }.orEmpty()
        return """{"extractions": [], "shortlist": [${(fresh + updates).joinToString(",")}]}"""
    }

    private fun extractUpdates(json: String, articleCount: Int): List<ShortlistItem> {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { openAI.completeJson(any(), any(), any()) } returns json
        val result = extractor.extract(
            "games",
            cat(DedupConfig(promptFile = "any.yaml", digest = DigestConfig(ranker = RankerConfig(enabled = false), meaningfulUpdateCooldownMinutes = 0))),
            (0 until articleCount).map { article("https://a.com/$it") }
        )
        return assertIs<ExtractOutcome.Ready>(result).result.shortlist
    }

    @Test
    fun `a meaningful_update whose story was never posted goes on as new`() {
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { db.fetchCoveredEventsByKeys(any(), any(), any()) } returns emptyList()
        every { db.countDigestPostsByEventKeys(any(), any(), any()) } returns emptyMap()

        val shortlist = extractUpdates(updatesJson("mistral_large_4" to null, "doom_dlc" to "doom_dlc_tease"), 2)

        assertEquals(listOf("new", "new"), shortlist.map { it.status })
        // The lookup window is digest.updateWindowDays (default 7).
        val since = slot<LocalDateTime>()
        verify { db.fetchCoveredEventsByKeys("games", setOf("mistral_large_4", "doom_dlc", "doom_dlc_tease"), capture(since)) }
        assertTrue(java.time.Duration.between(LocalDateTime.now().minusDays(7), since.captured).abs().toMinutes() < 5)
    }

    @Test
    fun `a meaningful_update stays one when its story is covered, posted or in the same shortlist`() {
        every { db.fetchRecentEvents(any(), any(), any()) } returns listOf(
            CoveredEventRow(
                category = "games", eventKey = "in_context", subject = "S", franchise = "F", eventType = "t",
                coreFact = "c", importance = 6, url = "https://old.com/1", coveredAt = LocalDateTime.now().minusDays(1)
            )
        )
        every { db.fetchCoveredEventsByKeys(any(), any(), any()) } answers {
            secondArg<Collection<String>>().filter { it == "in_archive" }.map {
                CoveredEventRow(
                    category = "games", eventKey = it, subject = "S", franchise = "F", eventType = "t",
                    coreFact = "c", importance = 6, url = "https://old.com/2", coveredAt = LocalDateTime.now().minusDays(3)
                )
            }
        }
        every { db.countDigestPostsByEventKeys(any(), any(), any()) } answers {
            secondArg<Collection<String>>().filter { it == "posted_only" }.associateWith { 1 }
        }

        val shortlist = extractUpdates(
            updatesJson(
                "u1" to "in_context",
                "in_archive" to null,     // Step 1 reused the key
                "u3" to "posted_only",
                "u4" to "fresh_story",    // the story's first post is in this very shortlist
                newKey = "fresh_story"
            ),
            5
        )

        assertEquals(
            mapOf("fresh_story" to "new", "u1" to "meaningful_update", "in_archive" to "meaningful_update",
                "u3" to "meaningful_update", "u4" to "meaningful_update"),
            shortlist.associate { it.eventKey to it.status }
        )
        // Updates anchored by the context or the shortlist need no DB round-trip; the
        // digest_messages fallback asks only for what covered_events left missing.
        verify { db.fetchCoveredEventsByKeys("games", setOf("in_archive", "u3", "posted_only"), any()) }
        verify { db.countDigestPostsByEventKeys("games", setOf("u3", "posted_only"), any()) }
    }

    @Test
    fun `a failed earlier-post lookup keeps the meaningful_update`() {
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { db.fetchCoveredEventsByKeys(any(), any(), any()) } throws RuntimeException("db down")

        val shortlist = extractUpdates(updatesJson("mistral_large_4" to null), 1)

        assertEquals("meaningful_update", shortlist.single().status)
    }

    // ── Story clusters (storyIntel) and shortlist sourceLinks ────────────────

    private val clusterJson = """
    {
      "extractions": [
        { "articleIndex": 0, "eventKey": "story", "coreFact": "f", "url": "https://a.com/1", "status": "new" },
        { "articleIndex": 1, "eventKey": "story", "coreFact": "f", "url": "https://b.com/2", "status": "duplicate" },
        { "articleIndex": 2, "eventKey": "story", "coreFact": "f", "url": "https://hallucinated.com/x", "status": "duplicate" },
        { "articleIndex": 2, "eventKey": "junk", "coreFact": "f", "url": "https://c.com/3", "status": "rejected" }
      ],
      "shortlist": [
        { "eventKey": "story", "coreFact": "f", "url": "https://a.com/1", "status": "new", "articleIndices": [0] }
      ]
    }
    """.trimIndent()

    private val clusterArticles = listOf(article("https://a.com/1"), article("https://b.com/2"), article("https://c.com/3"))

    private fun stubClusterRun() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns clusterJson
        every { db.fetchArticleStamps(any()) } answers {
            firstArg<Collection<String>>().withIndex().associate { (i, link) ->
                link to metifikys.db.ArticleStamp(100 + i, LocalDateTime.of(2026, 9, 1, 10, i), LocalDateTime.of(2026, 9, 1, 11, 0))
            }
        }
    }

    @Test
    fun `shortlist items carry their source links, in-batch copies included`() {
        stubClusterRun()
        val ready = assertIs<ExtractOutcome.Ready>(extractor.extract("games", cat(), clusterArticles))
        assertEquals(listOf("https://a.com/1", "https://b.com/2"), ready.result.shortlist.single().sourceLinks)
        verify(exactly = 0) { db.upsertEventArticles(any()) }
    }

    @Test
    fun `storyIntel records every in-batch extraction, never one with a url outside the batch`() {
        stubClusterRun()
        val rows = slot<List<metifikys.db.EventArticleRow>>()
        every { db.upsertEventArticles(capture(rows)) } just Runs
        extractor = EventExtractor(openAI, promptLoader, db, storyIntel = metifikys.config.StoryIntelConfig(enabled = true))

        extractor.extract("games", cat(), clusterArticles)

        val byLink = rows.captured.associateBy { it.link }
        assertEquals(setOf("https://a.com/1", "https://b.com/2", "https://c.com/3"), byLink.keys)
        assertEquals("story", byLink.getValue("https://b.com/2").eventKey)
        assertEquals("duplicate", byLink.getValue("https://b.com/2").step1Status)
        assertEquals("junk", byLink.getValue("https://c.com/3").eventKey)
        assertEquals("b.com", byLink.getValue("https://b.com/2").domain)
        assertEquals(LocalDateTime.of(2026, 9, 1, 11, 0), byLink.getValue("https://a.com/1").seenAt)
        assertTrue(rows.captured.all { it.role == metifikys.db.EventArticleRole.STEP1 && it.category == "games" })
    }

    @Test
    fun `storyIntel - a failing write never costs the digest`() {
        stubClusterRun()
        every { db.upsertEventArticles(any()) } throws RuntimeException("locked")
        extractor = EventExtractor(openAI, promptLoader, db, storyIntel = metifikys.config.StoryIntelConfig(enabled = true))

        val ready = assertIs<ExtractOutcome.Ready>(extractor.extract("games", cat(), clusterArticles))
        assertEquals(1, ready.result.shortlist.size)
    }

    // ── dedup.digest.antiBubble ─────────────────────────────────────────────────

    /** Three events for a 1-item digest: a liked top story, a disliked important one, a weak one. */
    private val antiBubbleJson = """
        {
          "extractions": [],
          "shortlist": [
            { "eventKey": "top", "subject": "Hades II", "franchise": "Hades", "coreFact": "a",
              "newsworthiness": 9, "digestFit": 9, "url": "https://a.com/1", "status": "new", "articleIndices": [0] },
            { "eventKey": "outside", "subject": "FIFA 27", "franchise": "FIFA", "coreFact": "b",
              "newsworthiness": 8, "digestFit": 6, "url": "https://a.com/2", "status": "new", "articleIndices": [1],
              "lane": "explore" },
            { "eventKey": "minor", "subject": "Minor", "franchise": "Minor", "coreFact": "c",
              "newsworthiness": 5, "digestFit": 5, "url": "https://a.com/3", "status": "new", "articleIndices": [2] }
          ]
        }
    """.trimIndent()

    private fun antiBubbleCat(anti: metifikys.config.AntiBubbleConfig?) = cat(
        DedupConfig(
            promptFile = "any.yaml",
            digest = DigestConfig(ranker = RankerConfig(enabled = true), maxDigestItems = 1, antiBubble = anti)
        )
    )

    private fun extractAntiBubble(anti: metifikys.config.AntiBubbleConfig?, lastSent: String? = null): List<metifikys.model.ShortlistItem> {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { db.getState("antibubble:games") } returns lastSent
        every { db.fetchAudienceAffinity() } returns listOf(
            metifikys.db.AudienceAffinityRow("games", "franchise", "fifa", 5.0, 5.0, -1.0, -1.0, -0.4, LocalDateTime.now()),
            metifikys.db.AudienceAffinityRow("games", "franchise", "hades", 5.0, 5.0, 1.0, 1.0, 0.4, LocalDateTime.now()),
            metifikys.db.AudienceAffinityRow("tech", "franchise", "fifa", 5.0, 5.0, 1.0, 1.0, 0.4, LocalDateTime.now())
        )
        every { openAI.completeJson(any(), any(), any()) } returns antiBubbleJson
        val articles = listOf(article("https://a.com/1"), article("https://a.com/2"), article("https://a.com/3"))
        return assertIs<ExtractOutcome.Ready>(extractor.extract("games", antiBubbleCat(anti), articles)).result.shortlist
    }

    @Test
    fun `anti-bubble appends the important low-affinity event past maxDigestItems`() {
        val shortlist = extractAntiBubble(metifikys.config.AntiBubbleConfig())
        assertEquals(listOf("top", "outside"), shortlist.map { it.eventKey })
        assertEquals(listOf(null, "explore"), shortlist.map { it.lane })
    }

    @Test
    fun `without the block, or within the interval, the model's lane is cleared and nothing is added`() {
        val off = extractAntiBubble(anti = null)
        assertEquals(listOf("top"), off.map { it.eventKey })
        assertTrue(off.all { it.lane == null })

        val recent = extractAntiBubble(metifikys.config.AntiBubbleConfig(), LocalDateTime.now().minusDays(3).toString())
        assertEquals(listOf("top"), recent.map { it.eventKey })

        val due = extractAntiBubble(metifikys.config.AntiBubbleConfig(), LocalDateTime.now().minusDays(8).toString())
        assertEquals(listOf("top", "outside"), due.map { it.eventKey })
    }

    // ── llm.shadow.extract ─────────────────────────────────────────────────

    private fun step1Json(shortlistUrl: String, rejectedKey: String) = """
        {
          "extractions": [
            {"articleIndex": 0, "eventKey": "ev|$shortlistUrl", "coreFact": "f", "url": "$shortlistUrl", "status": "new"},
            {"articleIndex": 1, "eventKey": "$rejectedKey", "coreFact": "r", "url": "https://a.com/2", "status": "rejected"}
          ],
          "shortlist": [
            {"eventKey": "ev|$shortlistUrl", "coreFact": "f", "url": "$shortlistUrl", "status": "new", "articleIndices": [0]}
          ]
        }
    """.trimIndent()

    private class ShadowStore : metifikys.db.ShadowRunStore {
        val rows = java.util.concurrent.CopyOnWriteArrayList<metifikys.db.ShadowRunRow>()
        override fun insertShadowRun(row: metifikys.db.ShadowRunRow) { rows += row }
        override fun fetchShadowRuns(since: LocalDateTime) = rows.toList()
        override fun deleteOldShadowRuns(days: Long) {}
    }

    private fun awaitRow(store: ShadowStore): metifikys.db.ShadowRunRow {
        val deadline = System.currentTimeMillis() + 5_000
        while (store.rows.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return store.rows.single()
    }

    @Test
    fun `shadow B runs on its own prompt, A is published unchanged and only A's rejects are saved`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns step1Json("https://a.com/1", "a-rejected")
        val bClient = mockk<metifikys.ai.LlmClient>()
        every { bClient.endpoint } returns metifikys.ai.LlmEndpoint("https://openrouter.ai/api/v1", "", "model-b")
        every { openAI.endpoint } returns metifikys.ai.LlmEndpoint("https://api.openai.com/v1", "", "model-a")
        every { bClient.completeJson(any(), any(), any()) } returns step1Json("https://a.com/3", "b-rejected")
        val store = ShadowStore()
        val runner = metifikys.digest.ShadowRunner(store)
        val bPrompts = okResolved.copy(extractSystem = "B-SYS", extractUser = "B-USER {{CURRENT_BATCH_JSON}}")
        val shadowed = EventExtractor(
            openAI, promptLoader, db,
            shadow = Step1Shadow(runner, metifikys.config.ShadowConfig(extract = metifikys.config.LlmOverride("openrouter", "model-b")), bClient, bPrompts)
        )
        val saved = mutableListOf<List<metifikys.db.RejectedEventRow>>()
        every { db.saveRejectedEvents(capture(saved)) } just Runs
        val articles = listOf(article("https://a.com/1"), article("https://a.com/2"), article("https://a.com/3"))

        try {
            val ready = assertIs<ExtractOutcome.Ready>(shadowed.extract("games", cat(), articles))

            assertEquals(listOf("https://a.com/1"), ready.result.shortlist.map { it.url })
            val row = awaitRow(store)
            assertEquals(listOf("https://a.com/1"), row.aUrls)
            assertEquals(listOf("https://a.com/3"), row.bUrls)
            assertEquals("model-b", row.bModel)
            verify(exactly = 1) { bClient.completeJson("B-SYS", "B-USER {{CURRENT_BATCH_JSON}}", 1) }
            assertEquals(listOf("a-rejected"), saved.flatten().map { it.eventKey }, "B's rejects are never saved")
        } finally {
            runner.shutdown()
        }
    }

    @Test
    fun `a failing shadow B leaves A's result untouched`() {
        every { promptLoader.resolve(any()) } returns okResolved
        every { promptLoader.substitute(any(), any()) } answers { firstArg() }
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        every { openAI.completeJson(any(), any(), any()) } returns step1Json("https://a.com/1", "a-rejected")
        every { openAI.endpoint } returns metifikys.ai.LlmEndpoint("https://api.openai.com/v1", "", "model-a")
        val bClient = mockk<metifikys.ai.LlmClient>()
        every { bClient.endpoint } returns metifikys.ai.LlmEndpoint("https://openrouter.ai/api/v1", "", "model-b")
        every { bClient.completeJson(any(), any(), any()) } throws RuntimeException("B is down")
        val store = ShadowStore()
        val runner = metifikys.digest.ShadowRunner(store)
        val shadowed = EventExtractor(
            openAI, promptLoader, db,
            shadow = Step1Shadow(runner, metifikys.config.ShadowConfig(extract = metifikys.config.LlmOverride("openrouter", "b")), bClient, okResolved)
        )
        try {
            val ready = assertIs<ExtractOutcome.Ready>(shadowed.extract("games", cat(), listOf(article("https://a.com/1"), article("https://a.com/2"))))
            assertEquals(listOf("https://a.com/1"), ready.result.shortlist.map { it.url })
            assertEquals("B is down", awaitRow(store).bError)
        } finally {
            runner.shutdown()
        }
    }

    // ── event-type mutes (/mute type:…, the inspector's button) ──────────────

    private val typedShortlistJson = """
        {
          "extractions": [],
          "shortlist": [
            {"eventKey": "gta_rumor", "eventType": "Rumor", "coreFact": "f", "newsworthiness": 9, "digestFit": 9,
             "url": "https://a.com/1", "status": "new", "articleIndices": [0]},
            {"eventKey": "silksong_date", "eventType": "release_date", "coreFact": "f", "newsworthiness": 6, "digestFit": 7,
             "url": "https://a.com/2", "status": "new", "articleIndices": [1]}
          ]
        }
    """.trimIndent()

    @Test
    fun `a muted event type is dropped after Step 1, before the ranker, with its own drop reason`() {
        every { db.fetchRecentEvents(any(), any(), any()) } returns emptyList()
        val muted = EventExtractor(openAI, promptLoader, db, mutedEventTypes = { if (it == "games") setOf("rumor") else emptySet() })
        val ranked = cat(DedupConfig(promptFile = "any.yaml", digest = DigestConfig(ranker = RankerConfig(enabled = true), meaningfulUpdateCooldownMinutes = 0)))
        val articles = listOf(article("https://a.com/1"), article("https://a.com/2"))

        val eval = muted.evaluate("games", ranked, articles, typedShortlistJson, emptyList())!!
        assertEquals(listOf("silksong_date"), eval.result.shortlist.map { it.eventKey })
        assertEquals(listOf(Step1Drop("https://a.com/1", "gta_rumor", Step1Drop.MUTED_TYPE)), eval.drops)

        val other = muted.evaluate("tech", ranked, articles, typedShortlistJson, emptyList())!!
        assertEquals(2, other.result.shortlist.size, "the mute is per category")
        val noRanker = muted.evaluate("games", cat(), articles, typedShortlistJson, emptyList())!!
        assertEquals(listOf("silksong_date"), noRanker.result.shortlist.map { it.eventKey }, "applies without a ranker too")
    }

    @Test
    fun `a failing type-mute lookup mutes nothing`() {
        val broken = EventExtractor(openAI, promptLoader, db, mutedEventTypes = { throw IllegalStateException("db down") })
        val eval = broken.evaluate("games", cat(), listOf(article("https://a.com/1"), article("https://a.com/2")), typedShortlistJson, emptyList())!!
        assertEquals(2, eval.result.shortlist.size)
        assertTrue(eval.drops.isEmpty())
    }
}
