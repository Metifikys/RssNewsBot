package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import metifikys.ai.BillingException
import metifikys.ai.LlmClientsFactory
import metifikys.ai.dedup.EventExtractor
import metifikys.ai.dedup.PromptLoader
import metifikys.config.AppConfig
import metifikys.db.NewsDatabase
import metifikys.fetch.ArticleFetcher
import metifikys.fetch.ArticleSummarizer
import metifikys.fetch.RssFetcher
import metifikys.model.ShortlistItem
import metifikys.telegram.StatusPoster
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * One end-to-end digest cycle: RSS fetch → link-dedup → enrich → summarize per feed →
 * insert → fetch ready batches → delegate to [CategoryProcessor].
 *
 * Also owns batch resumption from previous runs: on startup, polls any DB-recorded
 * pending batches and routes their results through [DigestDeliverer].
 */
class DigestCycle(
    private val config: AppConfig,
    private val fetcher: RssFetcher,
    private val articleFetcher: ArticleFetcher,
    private val articleSummarizer: ArticleSummarizer,
    private val db: NewsDatabase,
    private val llmClientsFactory: LlmClientsFactory,
    private val categoryProcessor: CategoryProcessor,
    private val deliverer: DigestDeliverer,
    private val promptLoader: PromptLoader,
    private val statusPoster: StatusPoster? = null,
    private val errorLog: CycleErrorLog = CycleErrorLog(),
    /**
     * Optional log-only embedding-dedup detector. When non-null, it runs after
     * `db.insertArticles(...)` to embed new articles and log near-duplicate
     * candidates. Never mutates article state. Null in tests / when no category
     * has opted in.
     */
    private val semanticDedupDetector: SemanticDedupDetector? = null,
    /**
     * Optional reaction-feedback aggregator (phase 1: aggregation only). When non-null,
     * each cycle start gives it a chance to rebuild `audience_affinity`; it internally
     * no-ops unless the previous rebuild is older than `feedback.recomputeHours`.
     * Null in tests / when `feedback.enabled=false`.
     */
    private val affinityAggregator: metifikys.feedback.AffinityAggregator? = null
) {

    private val shortlistJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun runCycle() {
        logger.info { "=== Digest cycle started at ${LocalDateTime.now()} ===" }
        try {
            // BUG-012: retention cleanup runs at the START of the cycle, and only when no batch is
            // still in flight. A pending batch callback (on a daemon thread) may later read
            // summaries/articles that a mid-cycle cleanup would delete, collapsing the previousUrls
            // dedup snapshot and letting duplicate posts through.
            runCleanup()

            // Reaction-feedback aggregation (phase 1: nothing reads the scores yet except
            // /status). Internally throttled and exception-safe — cannot break the cycle.
            affinityAggregator?.recomputeIfStale()

            // One shared wall-clock deadline for the whole cycle's fetch stage — every
            // category's feeds go through the fetcher's bounded pool, so the budget is
            // global by design (the pool caps pressure on the shared RSSHub host).
            val fetchDeadlineNanos = fetcher.newFetchDeadline()
            val categories = config.categories.entries.toList()

            // Common case in tests / small setups: one category → run inline, skip the pool.
            if (categories.size == 1) {
                val (name, catCfg) = categories.first()
                runCategoryPipeline(name, catCfg, fetchDeadlineNanos)
                return
            }

            // Fan out one fully independent pipeline per category: fetch → dedup → enrich →
            // summarize → insert → digest. A dead feed or a stuck LLM call in one category
            // costs that category only; the others fetch and post on their own clock.
            val concurrency = minOf(categories.size, config.processing.maxConcurrentCategories).coerceAtLeast(1)
            val pool = Executors.newFixedThreadPool(concurrency) { r ->
                Thread(r, "digest-worker").also { it.isDaemon = true }
            }
            try {
                val tasks = categories.map { (name, catCfg) ->
                    Callable { runCategoryPipeline(name, catCfg, fetchDeadlineNanos) }
                }
                // Barrier deadline = fetch budget + the category fan-out ceiling, so the
                // single-thread scheduler can never be wedged by a pathological hang. The
                // ceiling is configurable because it must stay above the worst-case chain of
                // inner LLM timeouts (e.g. claudeCli.timeoutSeconds × retries per call) —
                // otherwise the barrier interrupts healthy workers mid-call.
                val barrierSeconds = config.fetcher.fetchDeadlineSeconds +
                    config.processing.categoryDeadlineMinutes * 60
                pool.invokeAll(tasks, barrierSeconds, TimeUnit.SECONDS)
            } finally {
                pool.shutdown()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn { "Digest cycle interrupted — shutting down." }
        } catch (e: Exception) {
            errorLog.recordError(null, "[Cycle]", e)
            logger.error(e) { "Digest cycle error" }
        } finally {
            // One-way status: post current snapshot to admin chat, deleting the previous post.
            // In `finally` so it runs on the early single-category return as well.
            statusPoster?.post()
            logger.info { "=== Digest cycle finished ===" }
        }
    }

    /**
     * Full ingest → digest pipeline for ONE category: fetch its feeds → link-dedup → enrich →
     * summarize → preview images → insert → semantic-dedup log → ready query → process.
     * Exceptions are contained here, so a failing category never touches its siblings.
     */
    private fun runCategoryPipeline(
        name: String,
        catCfg: metifikys.config.CategoryConfig,
        fetchDeadlineNanos: Long
    ) {
        try {
            val rawArticles = fetcher.fetchCategory(name, catCfg.feeds, fetchDeadlineNanos)
            logger.info { "[Cycle:$name] Fetched ${rawArticles.size} article(s)." }

            // Dedup BEFORE enrichment: skip articles whose links are already in DB
            val existingLinks = db.findExistingLinks(rawArticles.map { it.link })
            val newRawArticles = rawArticles.filter { it.link !in existingLinks }
            logger.info { "[Cycle:$name] After dedup: ${newRawArticles.size} new article(s) (${existingLinks.size} already in DB)." }

            val enriched = articleFetcher.enrich(newRawArticles)
            val summarized = articleSummarizer.summarize(enriched)

            // Fill preview images (og:image) for image-enabled categories whose RSS entries
            // carried no image. The helper filters by category internally, so handing it a
            // single category's articles is safe.
            val articles = fillPreviewImages(summarized)

            val inserted = db.insertArticles(articles)
            logger.info { "[Cycle:$name] Inserted $inserted new article(s) into DB." }

            // Log-only embedding dedup detector. Mutates nothing; groups by category
            // internally, so a per-category subset is fine.
            semanticDedupDetector?.detectAndLog(articles)

            val ready = db.fetchReadyForDigest(name, config.processing.staleTimeoutHours)
            if (ready.isEmpty()) {
                logger.info { "[Cycle:$name] No ready articles. Skipping digest." }
                return
            }

            categoryProcessor.processSingle(name, ready)
        } catch (e: InterruptedException) {
            // Barrier cancellation or shutdown: keep the flag; articles left PROCESSING are
            // reclaimed next cycle via the stale-timeout.
            Thread.currentThread().interrupt()
            logger.warn { "[Cycle:$name] pipeline interrupted." }
        } catch (e: Exception) {
            errorLog.recordError(name, "[Cycle:$name]", e)
            logger.error(e) { "[Cycle:$name] pipeline failed — other categories unaffected." }
        }
    }

    /**
     * Retention cleanup, run at the start of a cycle. Skipped while any batch is still pending so an
     * in-flight callback never has the summaries/articles it depends on deleted out from under it
     * (BUG-012). Bounded by the configurable retention windows; the last two lines are the ones that
     * kept news.db growing to 700+ MB (llm_calls + article embeddings were only pruned from tests).
     */
    private fun runCleanup() {
        if (db.fetchPendingBatches().isNotEmpty()) {
            logger.info { "[Cleanup] Skipping retention cleanup — batch(es) still pending." }
            return
        }
        db.deleteOlderThan(config.processing.articleRetentionDays)
        db.deleteOldSummaries(config.summaryHistory.retentionDays)
        db.pruneOldCoveredEvents(config.summaryHistory.retentionDays)
        db.pruneOldEventEmbeddings(config.summaryHistory.retentionDays)
        db.deleteOldRejectedEvents()
        db.deleteOldDigestMessages()
        db.deleteOldReactionCounts()
        db.deleteOldLlmCalls(config.processing.llmCallRetentionDays)
        db.pruneOldEmbeddings(config.processing.embeddingRetentionDays)
    }

    /**
     * For articles in image-enabled categories that have no RSS image, fetches the article
     * page's Open Graph / Twitter Card preview image and populates [Article.imageUrl].
     * Articles in non-image categories or that already have an image pass through untouched.
     */
    private fun fillPreviewImages(articles: List<metifikys.model.Article>): List<metifikys.model.Article> {
        val imageCats = config.categories.filterValues { it.enableImages }.keys
        if (imageCats.isEmpty()) return articles

        val (eligible, rest) = articles.partition { it.category in imageCats && it.imageUrl == null }
        if (eligible.isEmpty()) return articles

        val withImages = articleFetcher.fillPreviewImages(eligible)
        val found = withImages.count { it.imageUrl != null }
        logger.info { "[PreviewImage] ${eligible.size} eligible article(s), $found got a preview image." }
        return withImages + rest
    }

    /**
     * On startup, checks the DB for any batch jobs that were still "pending" when the
     * bot last shut down, resumes polling them, and delivers the results asynchronously.
     * Branches on `kind`: "render" → existing render path; "extract" → Step 1 resume
     * path (fires Step 2 via [CategoryProcessor.deliverShortlist]).
     */
    fun resumePendingBatches() {
        val pending = db.fetchPendingBatches()
        if (pending.isEmpty()) return

        logger.info { "[Batch] Found ${pending.size} pending batch(es) from previous run — resuming..." }
        for (record in pending) {
            logger.info {
                "[Batch] Resuming batch ${record.batchId} kind=${record.kind} " +
                    "(categories: ${record.categoryNames.ifEmpty { "unknown" }}, created ${record.createdAt})"
            }

            if (record.kind == "extract") {
                resumeExtractBatch(record)
            } else {
                resumeRenderBatch(record)
            }
        }

        db.deleteOldBatches()
    }

    private fun resumeRenderBatch(record: metifikys.db.PendingBatch) {
        val rehydratedShortlist: List<ShortlistItem>? = record.shortlistJson?.let { raw ->
            try {
                shortlistJson.decodeFromString<List<ShortlistItem>>(raw)
            } catch (e: Exception) {
                logger.warn(e) { "[Batch] Failed to deserialize shortlist for batch ${record.batchId}" }
                null
            }
        }

        llmClientsFactory.forBatch(null).resumeBatch(record.batchId)
            .thenAccept { summaries ->
                for ((categoryName, summary) in summaries) {
                    val resolvedArticles = if (record.articleLinks.isNotBlank()) {
                        db.fetchArticlesByLinks(record.articleLinks.split("\n"))
                    } else {
                        // Legacy fallback for batches saved before this fix
                        db.fetchProcessingByCategory(categoryName).ifEmpty {
                            db.fetchReadyForDigestByCategory(config.processing.staleTimeoutHours)[categoryName]
                                ?: emptyList()
                        }
                    }
                    deliverer.deliver(categoryName, summary, resolvedArticles, rehydratedShortlist)
                }
            }
            .exceptionally { ex ->
                val cause = if (ex is ExecutionException) ex.cause ?: ex else ex
                errorLog.recordError(null, "[Batch resume]", cause)
                if (cause is BillingException) {
                    logger.warn { "[Batch] Billing limit hit while resuming ${record.batchId}. Skipping." }
                } else {
                    logger.error(cause) { "[Batch] Failed to resume batch ${record.batchId}" }
                }
                null
            }
    }

    private fun resumeExtractBatch(record: metifikys.db.PendingBatch) {
        val categoryName = record.categoryNames.split(',').map { it.trim() }.firstOrNull()
            ?: run {
                logger.warn { "[Batch][extract] No category name in record ${record.batchId} — skipping." }
                return
            }
        val catCfg = config.categories[categoryName] ?: run {
            logger.warn { "[Batch][extract] Unknown category '$categoryName' for batch ${record.batchId} — skipping." }
            return
        }
        val articles = if (record.articleLinks.isNotBlank()) {
            db.fetchArticlesByLinks(record.articleLinks.split("\n").filter { it.isNotBlank() })
        } else {
            db.fetchProcessingByCategory(categoryName)
        }

        val (primaryClient, _) = llmClientsFactory.forExtract(catCfg)
        val extractor = EventExtractor(
            openAI = primaryClient,
            promptLoader = promptLoader,
            db = db
        )

        primaryClient.resumeExtractBatch(record.batchId)
            .thenAccept { rawJson ->
                val result = extractor.resumeFromBatch(categoryName, catCfg, articles, rawJson)
                val links = articles.map { it.link }
                when {
                    result == null -> {
                        db.markUnprocessed(links)
                        logger.warn { "[Batch][extract] Batch ${record.batchId} parse failed — reverting to UNPROCESSED." }
                    }
                    result.shortlist.isEmpty() -> {
                        db.markProcessed(links)
                        logger.info { "[Batch][extract] Batch ${record.batchId} empty shortlist — marking PROCESSED." }
                    }
                    else -> categoryProcessor.deliverShortlist(categoryName, catCfg, articles, result.shortlist)
                }
            }
            .exceptionally { ex ->
                val cause = if (ex is ExecutionException) ex.cause ?: ex else ex
                db.markUnprocessed(articles.map { it.link })
                errorLog.recordError(categoryName, "[Batch resume extract]", cause)
                if (cause is BillingException) {
                    logger.warn { "[Batch][extract] Billing limit hit while resuming ${record.batchId}. Skipping." }
                } else {
                    logger.error(cause) { "[Batch][extract] Failed to resume extract batch ${record.batchId}" }
                }
                null
            }
    }
}
