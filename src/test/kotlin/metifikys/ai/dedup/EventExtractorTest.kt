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
}
