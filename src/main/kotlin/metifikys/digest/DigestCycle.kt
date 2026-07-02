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
import java.util.concurrent.ExecutionException

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
    private val semanticDedupDetector: SemanticDedupDetector? = null
) {

    private val shortlistJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun runCycle() {
        logger.info { "=== Digest cycle started at ${LocalDateTime.now()} ===" }
        try {
            val rawArticles = fetcher.fetchAll(config.categories)
            logger.info { "Fetched ${rawArticles.size} articles total." }

            // Dedup BEFORE enrichment: skip articles whose links are already in DB
            val existingLinks = db.findExistingLinks(rawArticles.map { it.link })
            val newRawArticles = rawArticles.filter { it.link !in existingLinks }
            logger.info { "After dedup: ${newRawArticles.size} new article(s) (${existingLinks.size} already in DB)." }

            val enriched = articleFetcher.enrich(newRawArticles)
            val summarized = articleSummarizer.summarize(enriched)

            // Fill preview images (og:image) for image-enabled categories whose RSS entries
            // carried no image, so the photo+caption delivery path has something to post.
            val articles = fillPreviewImages(summarized)

            val inserted = db.insertArticles(articles)
            logger.info { "Inserted $inserted new articles into DB." }

            // Log-only embedding dedup detector. Mutates nothing; safe to call
            // unconditionally — the detector itself filters by per-category opt-in.
            semanticDedupDetector?.detectAndLog(articles)

            val byCategory = db.fetchReadyForDigestByCategory(config.processing.staleTimeoutHours)
            if (byCategory.isEmpty()) {
                logger.info { "No ready articles. Skipping digest." }
                return
            }

            categoryProcessor.process(byCategory)

            db.deleteOlderThan(1500)
            db.deleteOldSummaries(config.summaryHistory.retentionDays)
            db.pruneOldCoveredEvents(config.summaryHistory.retentionDays)
            db.pruneOldEventEmbeddings(config.summaryHistory.retentionDays)
            db.deleteOldRejectedEvents()
            db.deleteOldDigestMessages()
            db.deleteOldReactionCounts()
        } catch (e: Exception) {
            errorLog.recordError(null, "[Cycle]", e)
            logger.error(e) { "Digest cycle error" }
        } finally {
            // One-way status: post current snapshot to admin chat, deleting the previous post.
            // In `finally` so it runs on the early "no ready articles" return as well.
            statusPoster?.post()
            logger.info { "=== Digest cycle finished ===" }
        }
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
