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
 * The digest pipeline: RSS fetch → link-dedup → enrich → summarize per feed → insert → fetch
 * ready batches → delegate to [CategoryProcessor].
 *
 * Two entry points drive it:
 *  - [runCategory] + [runMaintenance] — per-category scheduler mode (default): every category
 *    runs on its own thread at its own interval, the housekeeping that used to open a cycle
 *    (retention cleanup, affinity rebuild, status post) runs on a separate maintenance tick.
 *  - [runCycle] — the legacy global cycle: housekeeping, then every category fanned out in
 *    parallel, then the status post once the slowest category is done.
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

    /**
     * One run of a single category's pipeline, for the per-category scheduler. Gets its own
     * fetch deadline (the fetcher's bounded pool is still shared, so concurrent categories
     * throttle each other at the HTTP level, by design). Never throws for pipeline failures —
     * [runCategoryPipeline] records them in the error log — so the caller's schedule survives.
     */
    fun runCategory(name: String) = timed(name, "run") { catCfg ->
        runCategoryPipeline(name, catCfg, fetcher.newFetchDeadline())
    }

    /**
     * Ingestion half only (fetch → link-dedup → enrich → summarize → insert → embedding dedup),
     * for a decoupled ingest timer (`fetcher.intervalMinutes`). Leaves the digest to [runDigest].
     */
    fun runIngest(name: String) = timed(name, "ingest") { catCfg ->
        try {
            ingestCategory(name, catCfg, fetcher.newFetchDeadline())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn { "[Cycle:$name] ingest interrupted." }
        } catch (e: Exception) {
            errorLog.recordError(name, "[Ingest:$name]", e)
            logger.error(e) { "[Cycle:$name] ingest failed — other categories unaffected." }
        }
    }

    /**
     * Digest half only (ready query → Step 1 → Step 2) on whatever ingestion has already put in
     * the DB. Articles inserted while this runs simply wait for the next digest.
     */
    fun runDigest(name: String) = timed(name, "digest") { _ ->
        try {
            digestCategory(name)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn { "[Cycle:$name] digest interrupted." }
        } catch (e: Exception) {
            errorLog.recordError(name, "[Cycle:$name]", e)
            logger.error(e) { "[Cycle:$name] digest failed — other categories unaffected." }
        }
    }

    private inline fun timed(name: String, what: String, block: (metifikys.config.CategoryConfig) -> Unit) {
        val catCfg = config.categories[name] ?: run {
            logger.warn { "[Cycle:$name] unknown category — nothing to run." }
            return
        }
        val startedNanos = System.nanoTime()
        logger.info { "[Cycle:$name] $what started at ${LocalDateTime.now()}" }
        try {
            block(catCfg)
        } finally {
            val seconds = (System.nanoTime() - startedNanos) / 1_000_000_000
            logger.info { "[Cycle:$name] $what finished in ${seconds}s" }
        }
    }

    /**
     * Shared housekeeping for per-category mode — what a global cycle did before and after its
     * fan-out: retention cleanup, the throttled affinity rebuild, and one status snapshot to the
     * admin chat. Runs on its own timer, independent of any category's progress. Each part is
     * exception-safe on its own so a failing status post cannot skip the next cleanup.
     */
    fun runMaintenance() {
        try {
            runCleanup()
        } catch (e: Exception) {
            errorLog.recordError(null, "[Cleanup]", e)
            logger.error(e) { "[Maintenance] retention cleanup failed" }
        }
        affinityAggregator?.recomputeIfStale()
        statusPoster?.post()
    }

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
                val deadlineMinutes = config.processing.categoryDeadlineMinutes
                if (deadlineMinutes > 0) {
                    // Opt-in hard ceiling: pipelines still running past fetch budget + ceiling
                    // are interrupted; their articles revert / are reclaimed next cycle.
                    val barrierSeconds = config.fetcher.fetchDeadlineSeconds + deadlineMinutes * 60
                    pool.invokeAll(tasks, barrierSeconds, TimeUnit.SECONDS)
                } else {
                    // Default: wait for every category to finish — the cycle ends when the
                    // slowest one does. Each pipeline is internally bounded (fetch deadline,
                    // HTTP timeouts, CLI force-kill, bounded retries), so digest work is
                    // never cancelled mid-flight and long cycles just delay the next run
                    // (the scheduler is single-threaded — cycles never overlap).
                    pool.invokeAll(tasks)
                }
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
            if (!ingestCategory(name, catCfg, fetchDeadlineNanos)) return
            digestCategory(name)
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
     * Ingestion half: fetch → link-dedup → enrich → summarize → insert → embedding dedup.
     * Returns false when the worker was cancelled part-way (nothing lost: un-inserted feed
     * content is still in the RSS next time, inserted rows wait for the next digest).
     */
    private fun ingestCategory(
        name: String,
        catCfg: metifikys.config.CategoryConfig,
        fetchDeadlineNanos: Long
    ): Boolean {
        val rawArticles = fetcher.fetchCategory(name, catCfg.feeds, fetchDeadlineNanos)
        logger.info { "[Cycle:$name] Fetched ${rawArticles.size} article(s)." }

        // The fetch stage swallows interrupts by design (returns what it got, flag restored).
        // A cancelled worker stops here: nothing was inserted yet and the feed content is
        // still in the RSS next cycle, so this costs a delay, never data.
        if (Thread.currentThread().isInterrupted()) {
            logger.warn { "[Cycle:$name] cancelled during fetch — deferring to the next cycle." }
            return false
        }

        // Dedup BEFORE enrichment: skip articles whose links are already in DB
        val existingLinks = db.findExistingLinks(rawArticles.map { it.link })
        val newRawArticles = rawArticles.filter { it.link !in existingLinks }
        logger.info { "[Cycle:$name] After dedup: ${newRawArticles.size} new article(s) (${existingLinks.size} already in DB)." }

        // Enrichment and preview-image lookup share one pass: both want the article's HTML,
        // so an article needing text AND an og:image is fetched once instead of twice. The
        // predicate keeps ArticleFetcher decoupled from config — we decide here which
        // categories have images enabled. Runs before summarization; the summarizer only
        // sets `summary`, so image filling is order-independent.
        val imageCategories = config.categories.filterValues { it.enableImages }.keys
        val enriched = articleFetcher.enrich(newRawArticles) { it.category in imageCategories }
        if (imageCategories.isNotEmpty()) {
            val eligible = newRawArticles.count { it.category in imageCategories && it.imageUrl == null }
            if (eligible > 0) {
                val found = enriched.count { it.category in imageCategories && it.imageUrl != null }
                logger.info { "[PreviewImage:$name] $eligible eligible article(s), $found now carry a preview image." }
            }
        }
        val articles = articleSummarizer.summarize(enriched)

        val inserted = db.insertArticles(articles)
        logger.info { "[Cycle:$name] Inserted $inserted new article(s) into DB." }

        // enrich/summarize/preview are best-effort and swallow interrupts (partial results,
        // flag restored). Keep the cheap DB insert above, but skip the embed + digest stages
        // on a cancelled worker — inserted articles wait for the next cycle.
        if (Thread.currentThread().isInterrupted()) {
            logger.warn { "[Cycle:$name] cancelled — skipping dedup/digest; inserted articles wait for the next cycle." }
            return false
        }

        // Log-only embedding dedup detector. Mutates nothing; groups by category
        // internally, so a per-category subset is fine.
        semanticDedupDetector?.detectAndLog(articles)
        return true
    }

    /** Digest half: ready query → [CategoryProcessor.processSingle] (Step 1 + Step 2). */
    private fun digestCategory(name: String) {
        val ready = db.fetchReadyForDigest(name, config.processing.staleTimeoutHours)
        if (ready.isEmpty()) {
            logger.info { "[Cycle:$name] No ready articles. Skipping digest." }
            return
        }
        categoryProcessor.processSingle(name, ready)
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
        db.pruneOldCoveredEvents(coveredEventsRetentionDays())
        db.pruneOldEventEmbeddings(config.summaryHistory.retentionDays)
        db.deleteOldRejectedEvents()
        db.deleteOldDigestMessages()
        db.deleteOldReactionCounts()
        db.deleteOldLlmCalls(config.processing.llmCallRetentionDays)
        db.pruneOldEmbeddings(config.processing.embeddingRetentionDays)
    }

    /**
     * `covered_events` is not only the Step-1 dedup memory — it is the join partner of every
     * affinity input (`fetchAffinityInputs` INNER JOINs it to recover a message's subject /
     * franchise / eventType / url). Pruning it at `summaryHistory.retentionDays` therefore
     * silently truncates `feedback.lookbackDays`: production ran a 14-day retention against a
     * 90-day lookback and discarded ~72% of every reaction ever collected, which is also why
     * `feedback.halflifeDays=45` never had room to decay anything. Step 1's own context stays
     * bounded by `dedup.contextDays` / `maxContextEvents` regardless, so holding the rows
     * longer costs disk, not prompt size.
     */
    private fun coveredEventsRetentionDays(): Long =
        if (config.feedback.enabled) {
            maxOf(config.summaryHistory.retentionDays, config.feedback.lookbackDays)
        } else {
            config.summaryHistory.retentionDays
        }

    /**
     * On startup, checks the DB for any batch jobs that were still "pending" when the
     * bot last shut down, resumes polling them, and delivers the results asynchronously.
     * Branches on `kind`: "render" → existing render path; "extract" → Step 1 resume
     * path (fires Step 2 via [CategoryProcessor.deliverShortlist]).
     */
    /**
     * Startup recovery, run once before [resumePendingBatches]: returns every article a previous
     * JVM left PROCESSING to UNPROCESSED, except those a still-pending Batch API job owns (its
     * callback marks them itself). Without this, rows orphaned by a forced shutdown sit until the
     * stale-timeout and then land in one cycle as a single oversized Step-1 prompt.
     */
    fun reclaimOrphanedProcessing() {
        val ownedByPendingBatches = db.fetchPendingBatches()
            .flatMap { rec -> rec.articleLinks.lines().map { it.trim() }.filter { it.isNotEmpty() } }
        val reclaimed = db.reclaimOrphanedProcessing(ownedByPendingBatches)
        if (reclaimed > 0) {
            logger.warn {
                "[Startup] Reclaimed $reclaimed article(s) left PROCESSING by a previous run " +
                    "(${ownedByPendingBatches.size} link(s) owned by pending batches kept)."
            }
        }
    }

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
