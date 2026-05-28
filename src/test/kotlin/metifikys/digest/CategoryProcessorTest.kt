package metifikys.digest

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import metifikys.ai.BillingException
import metifikys.ai.LlmClient
import metifikys.ai.LlmClientsFactory
import metifikys.ai.dedup.EventExtractor
import metifikys.ai.dedup.ExtractOutcome
import metifikys.ai.dedup.PromptLoader
import metifikys.ai.dedup.ResolvedDedupPrompts
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.CategoryLlmOverrides
import metifikys.config.DatabaseConfig
import metifikys.config.DedupConfig
import metifikys.config.DigestConfig
import metifikys.config.FeedConfig
import metifikys.config.LlmOverride
import metifikys.config.OpenAIConfig
import metifikys.config.ProcessingConfig
import metifikys.config.RankerConfig
import metifikys.config.SchedulerConfig
import metifikys.config.SummaryHistoryConfig
import metifikys.config.TelegramConfig
import metifikys.db.NewsDatabase
import metifikys.db.SummaryRecord
import metifikys.model.Article
import metifikys.model.ExtractionResult
import metifikys.model.ShortlistItem
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import kotlin.test.assertFailsWith

class CategoryProcessorTest {

    private val resolvedDedup = ResolvedDedupPrompts(
        extractSystem = "S", extractUser = "U",
        renderSystem = "RS", renderUser = "RU",
        contextDays = 7, maxContextEvents = 50
    )

    private fun cfg(
        minArticles: Int = 3,
        primaryMaxPending: Int = 2,
        secondaryMaxPending: Int = 1,
        historyMaxCount: Int = 0,
        category: CategoryConfig = cat()
    ) = AppConfig(
        telegram = TelegramConfig(botToken = "t"),
        openai = OpenAIConfig(apiKey = "sk"),
        database = DatabaseConfig(path = ":memory:"),
        scheduler = SchedulerConfig(intervalMinutes = 60),
        categories = mapOf("tech" to category),
        summaryHistory = SummaryHistoryConfig(maxCount = historyMaxCount),
        processing = ProcessingConfig(
            minArticles = minArticles,
            primaryMaxPending = primaryMaxPending,
            secondaryMaxPending = secondaryMaxPending
        )
    )

    private fun cat(
        dedup: DedupConfig? = null,
        llm: CategoryLlmOverrides? = null
    ) = CategoryConfig(
        emoji = "📰",
        feeds = listOf(FeedConfig("https://example.com/rss")),
        channelId = "@tech",
        dedup = dedup,
        llm = llm
    )

    private fun article(n: Int, pubDate: LocalDateTime = LocalDateTime.now()) = Article(
        category = "tech",
        title = "Title $n",
        link = "https://example.com/$n",
        description = "desc $n",
        pubDate = pubDate
    )

    private fun shortlistItem(n: Int) = ShortlistItem(
        eventKey = "k$n",
        coreFact = "fact $n",
        importance = 7,
        url = "https://example.com/$n",
        status = "new",
        articleIndices = listOf(0)
    )

    private fun deps(
        config: AppConfig,
        eventExtractor: EventExtractor? = null,
        relaxedDb: NewsDatabase = mockk(relaxed = true),
        promptLoader: PromptLoader = mockk(),
        factory: LlmClientsFactory = mockk(),
        deliverer: DigestDeliverer = mockk(relaxed = true)
    ): Tuple {
        every { promptLoader.resolve(any()) } returns null  // default: legacy path
        every { relaxedDb.countPendingBatchesForCategory(any()) } returns 0
        every { relaxedDb.fetchRecentSummaries(any(), any()) } returns emptyList()
        return Tuple(
            CategoryProcessor(config, relaxedDb, factory, promptLoader, deliverer, eventExtractor),
            relaxedDb, factory, promptLoader, deliverer
        )
    }

    private data class Tuple(
        val processor: CategoryProcessor,
        val db: NewsDatabase,
        val factory: LlmClientsFactory,
        val promptLoader: PromptLoader,
        val deliverer: DigestDeliverer
    )

    // ── process(): minArticles gate ────────────────────────────────────────────

    @Test
    fun `skips when articles below minArticles`() {
        val (p, _, factory) = deps(cfg(minArticles = 5))
        p.process(mapOf("tech" to listOf(article(1), article(2))))
        verify(exactly = 0) { factory.forBatch(any()) }
    }

    @Test
    fun `skips when category not in config`() {
        val (p, _, factory) = deps(cfg(minArticles = 1))
        p.process(mapOf("ghost" to listOf(article(1))))
        verify(exactly = 0) { factory.forBatch(any()) }
    }

    // ── process(): backpressure ────────────────────────────────────────────────

    @Test
    fun `skips when batching stuck and articles below 2x min`() {
        val (p, db, factory) = deps(cfg(minArticles = 3, primaryMaxPending = 2, secondaryMaxPending = 1))
        every { db.countPendingBatchesForCategory("tech") } returns 5  // ≥ syncCutoff (2 since no batchFallback)
        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))  // 3 < 2*3
        verify(exactly = 0) { factory.forBatch(any()) }
    }

    @Test
    fun `continues when stuck but articles meet 2x min`() {
        val (p, db, factory) = deps(cfg(minArticles = 3, primaryMaxPending = 2))
        every { db.countPendingBatchesForCategory("tech") } returns 5
        val client = mockk<LlmClient>()
        every { factory.forRender(any()) } returns client
        every { client.summarizeArticles(any(), any(), any(), any(), any(), any(), any()) } returns "summary"

        val arts = (1..6).map { article(it) }
        p.process(mapOf("tech" to arts))

        verify { client.summarizeArticles(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── process(): useBatchFallback band ───────────────────────────────────────

    @Test
    fun `useBatchFallback engages when pending in secondary band and fallback configured`() {
        val cat = cat(llm = CategoryLlmOverrides(batchFallback = LlmOverride("openai", "gpt-fb")))
        val (p, db, factory) = deps(cfg(primaryMaxPending = 2, secondaryMaxPending = 1, category = cat))
        every { db.countPendingBatchesForCategory("tech") } returns 2  // ≥ primary, < syncCutoff(3)

        val fbClient = mockk<LlmClient>()
        every { factory.forBatchFallback(any()) } returns fbClient
        every { fbClient.summarizeArticles(any(), any(), any(), any(), any(), any(), any()) } returns "summary"

        p.process(mapOf("tech" to (1..3).map { article(it) }))

        verify { factory.forBatchFallback(any()) }
        verify { fbClient.summarizeArticles(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `useBatchFallback throws when forBatchFallback returns null`() {
        val cat = cat(llm = CategoryLlmOverrides(batchFallback = LlmOverride("openai", "gpt-fb")))
        val (p, db, factory) = deps(cfg(primaryMaxPending = 2, secondaryMaxPending = 1, category = cat))
        every { db.countPendingBatchesForCategory("tech") } returns 2
        every { factory.forBatchFallback(any()) } returns null

        assertFailsWith<IllegalStateException> {
            p.process(mapOf("tech" to (1..3).map { article(it) }))
        }
    }

    // ── process(): legacy path (no dedup) ──────────────────────────────────────

    @Test
    fun `legacy path submits batch when no dedup configured`() {
        val (p, _, factory, _, deliverer) = deps(cfg(minArticles = 1))
        val client = mockk<LlmClient>()
        every { factory.forBatch(any()) } returns client
        every { client.submitCategoryBatch(any(), any()) } returns CompletableFuture.completedFuture("digest")
        every { deliverer.deliver(any(), any(), any(), any()) } just Runs

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { client.submitCategoryBatch("tech", any()) }
        verify { deliverer.deliver("tech", "digest", any(), null) }
    }

    @Test
    fun `legacy path uses fetched summaries when historyMaxCount positive`() {
        val (p, db, factory) = deps(cfg(minArticles = 1, historyMaxCount = 2))
        every { db.fetchRecentSummaries("tech", 2) } returns listOf(
            SummaryRecord("tech", "old1", LocalDateTime.now()),
            SummaryRecord("tech", "old2", LocalDateTime.now())
        )
        val client = mockk<LlmClient>()
        every { factory.forBatch(any()) } returns client
        every { client.submitCategoryBatch(any(), any()) } returns CompletableFuture.completedFuture("d")

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { db.fetchRecentSummaries("tech", 2) }
    }

    // ── process(): dedup path ──────────────────────────────────────────────────

    @Test
    fun `dedup empty shortlist marks processed and skips submission`() {
        val cat = cat(dedup = DedupConfig(promptFile = "x.yaml"))
        val extractor = mockk<EventExtractor>()
        val (p, db, factory, promptLoader) = deps(cfg(minArticles = 1, category = cat), eventExtractor = extractor)
        every { promptLoader.resolve(any()) } returns resolvedDedup
        every { extractor.extract(any(), any(), any()) } returns ExtractOutcome.Ready(ExtractionResult(emptyList(), emptyList()))

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { db.markProcessed(any()) }
        verify(exactly = 0) { factory.forBatch(any()) }
    }

    @Test
    fun `dedup null result falls through to legacy`() {
        val cat = cat(dedup = DedupConfig(promptFile = "x.yaml"))
        val extractor = mockk<EventExtractor>()
        val (p, _, factory, promptLoader) = deps(cfg(minArticles = 1, category = cat), eventExtractor = extractor)
        every { promptLoader.resolve(any()) } returns resolvedDedup
        every { extractor.extract(any(), any(), any()) } returns ExtractOutcome.FallbackToLegacy
        val client = mockk<LlmClient>()
        every { factory.forBatch(any()) } returns client
        every { client.submitCategoryBatch(any(), any()) } returns CompletableFuture.completedFuture("d")

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { client.submitCategoryBatch(any(), any()) }
    }

    @Test
    fun `dedup BillingException skips category`() {
        val cat = cat(dedup = DedupConfig(promptFile = "x.yaml"))
        val extractor = mockk<EventExtractor>()
        val (p, db, factory, promptLoader) = deps(cfg(minArticles = 1, category = cat), eventExtractor = extractor)
        every { promptLoader.resolve(any()) } returns resolvedDedup
        every { extractor.extract(any(), any(), any()) } throws BillingException("quota")

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { db.markUnprocessed(any()) }
        verify(exactly = 0) { factory.forBatch(any()) }
    }

    @Test
    fun `dedup generic Exception skips category`() {
        val cat = cat(dedup = DedupConfig(promptFile = "x.yaml"))
        val extractor = mockk<EventExtractor>()
        val (p, db, factory, promptLoader) = deps(cfg(minArticles = 1, category = cat), eventExtractor = extractor)
        every { promptLoader.resolve(any()) } returns resolvedDedup
        every { extractor.extract(any(), any(), any()) } throws RuntimeException("boom")

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { db.markUnprocessed(any()) }
        verify(exactly = 0) { factory.forBatch(any()) }
    }

    @Test
    fun `dedup caps articles to 100 newest`() {
        val cat = cat(dedup = DedupConfig(promptFile = "x.yaml"))
        val extractor = mockk<EventExtractor>()
        val (p, _, _, promptLoader) = deps(cfg(minArticles = 1, category = cat), eventExtractor = extractor)
        every { promptLoader.resolve(any()) } returns resolvedDedup
        every { extractor.extract(any(), any(), any()) } returns ExtractOutcome.Ready(ExtractionResult(emptyList(), emptyList()))

        val now = LocalDateTime.now()
        val articles = (1..150).map { article(it, pubDate = now.minusMinutes(it.toLong())) }
        p.process(mapOf("tech" to articles))

        verify {
            extractor.extract("tech", any(), match { it.size == 100 })
        }
    }

    @Test
    fun `dedup successful shortlist submits render batch`() {
        val cat = cat(dedup = DedupConfig(promptFile = "x.yaml"))
        val extractor = mockk<EventExtractor>()
        val (p, _, factory, promptLoader, deliverer) = deps(cfg(minArticles = 1, category = cat), eventExtractor = extractor)
        every { promptLoader.resolve(any()) } returns resolvedDedup
        every { extractor.extract(any(), any(), any()) } returns ExtractOutcome.Ready(ExtractionResult(
            extractions = emptyList(),
            shortlist = listOf(shortlistItem(1))
        ))
        val client = mockk<LlmClient>()
        every { factory.forBatch(any()) } returns client
        every { client.submitCategoryBatch(any(), any()) } returns CompletableFuture.completedFuture("digest")
        every { deliverer.deliver(any(), any(), any(), any()) } just Runs

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { client.submitCategoryBatch("tech", any()) }
    }

    // ── process(): ranker hold-back / force-publish ────────────────────────────

    @Test
    fun `weak shortlist held back when before maxWait`() {
        val cat = cat(
            dedup = DedupConfig(
                promptFile = "x.yaml",
                digest = DigestConfig(
                    ranker = RankerConfig(enabled = true),
                    minStrongItems = 5,
                    maxWaitHours = 4,
                    minItemsOnForcePublish = 1
                )
            )
        )
        val extractor = mockk<EventExtractor>()
        val (p, db, factory, promptLoader) = deps(cfg(minArticles = 1, category = cat), eventExtractor = extractor)
        every { promptLoader.resolve(any()) } returns resolvedDedup
        every { extractor.extract(any(), any(), any()) } returns ExtractOutcome.Ready(ExtractionResult(
            extractions = emptyList(),
            shortlist = listOf(shortlistItem(1))  // 1 < minStrongItems=5
        ))
        every { db.fetchRecentSummaries("tech", 1) } returns listOf(
            SummaryRecord("tech", "recent", LocalDateTime.now().minusHours(1))  // 1h < 4h maxWait
        )

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { db.markProcessed(any()) }
        verify(exactly = 0) { factory.forBatch(any()) }
    }

    @Test
    fun `weak shortlist force-published past maxWait when meets force floor`() {
        val cat = cat(
            dedup = DedupConfig(
                promptFile = "x.yaml",
                digest = DigestConfig(
                    ranker = RankerConfig(enabled = true),
                    minStrongItems = 5,
                    maxWaitHours = 4,
                    minItemsOnForcePublish = 1
                )
            )
        )
        val extractor = mockk<EventExtractor>()
        val (p, db, factory, promptLoader, deliverer) = deps(cfg(minArticles = 1, category = cat), eventExtractor = extractor)
        every { promptLoader.resolve(any()) } returns resolvedDedup
        every { extractor.extract(any(), any(), any()) } returns ExtractOutcome.Ready(ExtractionResult(
            extractions = emptyList(),
            shortlist = listOf(shortlistItem(1))  // 1 ≥ minItemsOnForcePublish=1
        ))
        every { db.fetchRecentSummaries("tech", 1) } returns listOf(
            SummaryRecord("tech", "old", LocalDateTime.now().minusHours(10))  // 10h ≥ 4h maxWait
        )
        val client = mockk<LlmClient>()
        every { factory.forBatch(any()) } returns client
        every { client.submitCategoryBatch(any(), any()) } returns CompletableFuture.completedFuture("d")
        every { deliverer.deliver(any(), any(), any(), any()) } just Runs

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { client.submitCategoryBatch(any(), any()) }
    }

    // ── submitOrSync(): exception handling ────────────────────────────────────

    @Test
    fun `submitOrSync BillingException synchronously thrown reverts`() {
        val (p, db, factory) = deps(cfg(minArticles = 1))
        val client = mockk<LlmClient>()
        every { factory.forBatch(any()) } returns client
        every { client.submitCategoryBatch(any(), any()) } throws BillingException("quota")

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { db.markUnprocessed(any()) }
    }

    @Test
    fun `submitOrSync general Exception synchronously thrown reverts`() {
        val (p, db, factory) = deps(cfg(minArticles = 1))
        val client = mockk<LlmClient>()
        every { factory.forBatch(any()) } returns client
        every { client.submitCategoryBatch(any(), any()) } throws RuntimeException("net")

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { db.markUnprocessed(any()) }
    }

    @Test
    fun `submitOrSync future BillingException reverts`() {
        val (p, db, factory) = deps(cfg(minArticles = 1))
        val client = mockk<LlmClient>()
        every { factory.forBatch(any()) } returns client
        val fut = CompletableFuture<String>()
        fut.completeExceptionally(BillingException("quota"))
        every { client.submitCategoryBatch(any(), any()) } returns fut

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { db.markUnprocessed(any()) }
    }

    @Test
    fun `submitOrSync future generic exception reverts`() {
        val (p, db, factory) = deps(cfg(minArticles = 1))
        val client = mockk<LlmClient>()
        every { factory.forBatch(any()) } returns client
        val fut = CompletableFuture<String>()
        fut.completeExceptionally(RuntimeException("boom"))
        every { client.submitCategoryBatch(any(), any()) } returns fut

        p.process(mapOf("tech" to listOf(article(1), article(2), article(3))))

        verify { db.markUnprocessed(any()) }
    }

    // ── runSyncFallback: with shortlist ────────────────────────────────────────

    @Test
    fun `sync fallback with shortlist calls summarizeShortlist`() {
        val cat = cat(dedup = DedupConfig(promptFile = "x.yaml"))
        val extractor = mockk<EventExtractor>()
        val (p, db, factory, promptLoader, deliverer) = deps(
            cfg(minArticles = 1, primaryMaxPending = 2, category = cat),
            eventExtractor = extractor
        )
        every { db.countPendingBatchesForCategory("tech") } returns 5  // stuck
        every { promptLoader.resolve(any()) } returns resolvedDedup
        every { extractor.extract(any(), any(), any()) } returns ExtractOutcome.Ready(ExtractionResult(
            extractions = emptyList(),
            shortlist = listOf(shortlistItem(1))
        ))
        val syncClient = mockk<LlmClient>()
        every { factory.forRender(any()) } returns syncClient
        every {
            syncClient.summarizeShortlist(any(), any(), any(), any(), any(), any())
        } returns "summary"
        every { deliverer.deliver(any(), any(), any(), any()) } just Runs

        p.process(mapOf("tech" to (1..6).map { article(it) }))

        verify { syncClient.summarizeShortlist(any(), any(), any(), any(), any(), any()) }
    }

    // ── runSyncFallback: BillingException ─────────────────────────────────────

    @Test
    fun `sync fallback BillingException reverts`() {
        val (p, db, factory) = deps(cfg(minArticles = 1, primaryMaxPending = 2))
        every { db.countPendingBatchesForCategory("tech") } returns 5  // stuck
        val syncClient = mockk<LlmClient>()
        every { factory.forRender(any()) } returns syncClient
        every {
            syncClient.summarizeArticles(any(), any(), any(), any(), any(), any(), any())
        } throws BillingException("quota")

        p.process(mapOf("tech" to (1..6).map { article(it) }))

        verify { db.markUnprocessed(any()) }
    }

    @Test
    fun `sync fallback generic Exception reverts`() {
        val (p, db, factory) = deps(cfg(minArticles = 1, primaryMaxPending = 2))
        every { db.countPendingBatchesForCategory("tech") } returns 5
        val syncClient = mockk<LlmClient>()
        every { factory.forRender(any()) } returns syncClient
        every {
            syncClient.summarizeArticles(any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("boom")

        p.process(mapOf("tech" to (1..6).map { article(it) }))

        verify { db.markUnprocessed(any()) }
    }
}
