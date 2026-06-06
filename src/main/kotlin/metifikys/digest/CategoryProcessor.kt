package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.BillingException
import metifikys.ai.LlmClientsFactory
import metifikys.ai.dedup.EventExtractor
import metifikys.ai.dedup.ExtractOutcome
import metifikys.ai.dedup.PromptLoader
import metifikys.ai.dedup.ResolvedDedupPrompts
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.db.NewsDatabase
import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Per-category Step-1 (event extraction / dedup) and Step-2 (digest render) routing.
 *
 * For each ready category:
 *   1. Pre-cycle gates (min-articles floor, batch backpressure)
 *   2. Optional dedup pass (event extraction → shortlist) with weak-shortlist hold-back
 *   3. Submit to OpenAI Batch API (or sync fallback when batches are stuck)
 *
 * Both routes deliver via [DigestDeliverer] so the LLM input is identical regardless of API.
 */
class CategoryProcessor(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val llmClientsFactory: LlmClientsFactory,
    private val promptLoader: PromptLoader,
    private val deliverer: DigestDeliverer,
    /**
     * Test escape hatch: when non-null, every category uses this extractor instead of
     * one built per-category from [llmClientsFactory]. Production code leaves this null.
     */
    private val eventExtractor: EventExtractor? = null,
    private val errorLog: CycleErrorLog = CycleErrorLog(),
    /**
     * Optional log-only event-level embedding analyzer (Layer 3.5). When non-null, runs on
     * every non-empty shortlist about to be rendered — embeds events by `event_key` and logs
     * near-duplicates against recently-covered events. Never mutates state. Null in tests /
     * when no category has opted in via `semanticDedup.eventEnabled`.
     */
    private val eventSemanticAnalyzer: EventSemanticAnalyzer? = null
) {

    private companion object {
        const val SYNC_FALLBACK_CAP = 60
    }

    private val extractorCounters = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Submits each category to the OpenAI Batch API as an independent batch job.
     * Each category gets its own CompletableFuture with its own delivery callback,
     * so categories don't block each other — a fast category delivers immediately.
     * Falls back to synchronous calls per category if batch submission fails.
     */
    fun process(byCategory: Map<String, List<Article>>) {
        val historyMaxCount = config.summaryHistory.maxCount
        val chunkSize = 100

        for ((name, articles) in byCategory) {
            val categoryConfig = config.categories[name] ?: continue

            // ── Step 1: pre-cycle gate ─────────────────────────────────────────────
            // (a) basic floor — too few articles to bother with the LLM at all
            if (articles.size < config.processing.minArticles) {
                logger.info { "[Category:$name] Only ${articles.size} article(s), need ${config.processing.minArticles}. Skipping." }
                continue
            }
            // (b) backpressure — if batches are piling up AND new article volume isn't large
            // enough to justify forcing a sync call, skip the whole cycle for this category.
            val pendingForCategory = db.countPendingBatchesForCategory(name)
            // Three-tier routing driven by global thresholds:
            //   pending < primaryMax                              → primary batch
            //   primaryMax ≤ pending < primaryMax+secondaryMax    → llm.batchFallback (when configured)
            //   pending ≥ syncCutoff                              → sync render fallback (isBatchingStuck)
            // When the category has no `batchFallback`, the secondary band collapses and the
            // sync cutoff drops to `primaryMaxPending` — preserving the original two-tier behaviour.
            // When primaryMaxPending == 0 OR the category sets `skipBatch: true`, the primary
            // Batch API is disabled outright: no primary batch is ever submitted, render runs
            // synchronously (via batchFallback when configured, else the sync render client),
            // and the backpressure heuristic is moot (pending stays 0).
            val primaryMaxPending = config.processing.primaryMaxPending
            val secondaryMaxPending = config.processing.secondaryMaxPending
            val batchingDisabled = primaryMaxPending == 0 || categoryConfig.skipBatch || categoryConfig.skipBatch
            val hasBatchFallback = categoryConfig.llm?.batchFallback != null
            val syncCutoff = if (hasBatchFallback) primaryMaxPending + secondaryMaxPending else primaryMaxPending
            val isBatchingStuck = !batchingDisabled && pendingForCategory >= syncCutoff
            if (isBatchingStuck && articles.size < 2 * config.processing.minArticles) {
                logger.warn {
                    "[Category:$name] backpressure: pending=$pendingForCategory ≥ syncCutoff=$syncCutoff, " +
                        "newArticles=${articles.size} < 2×${config.processing.minArticles}. Skipping cycle."
                }
                continue
            }
            val useBatchFallback = hasBatchFallback &&
                (batchingDisabled || (!isBatchingStuck && pendingForCategory >= primaryMaxPending))
            // primaryMaxPending == 0 with no batchFallback configured → render via the sync client.
            val bypassPrimaryBatch = isBatchingStuck || (batchingDisabled && !useBatchFallback)

            val previousSummaries = if (historyMaxCount > 0) {
                db.fetchRecentSummaries(name, historyMaxCount)
                    .map { it.summary }
                    .reversed()
            } else emptyList()

            // ── Step 2: dedup (if configured for this category) ───────────────────
            // Produces (shortlist, resolvedDedup) when successful — both nullable. When the
            // category has no dedup config, OR Step 1 fails, we fall through with shortlist=null
            // and the legacy chunked path takes over.
            val resolvedDedup = promptLoader.resolve(categoryConfig)
            var shortlist: List<ShortlistItem>? = null
            var dedupCapped: List<Article>? = null

            if (resolvedDedup != null) {
                val capped = if (articles.size > 100) {
                    logger.warn {
                        "[Category:$name][Dedup] capped Step 1 input from ${articles.size} to 100 (newest by pubDate)"
                    }
                    articles.sortedByDescending { it.pubDate }.take(100)
                } else articles
                val cappedLinks = capped.map { it.link }
                db.markProcessing(cappedLinks)

                val extractor = eventExtractor ?: run {
                    val (primary, alternate) = llmClientsFactory.forExtract(categoryConfig)
                    EventExtractor(
                        openAI = primary,
                        promptLoader = promptLoader,
                        db = db,
                        alternateOpenAI = alternate,
                        requestCounter = extractorCounters.getOrPut(name) { AtomicLong(0) }
                    )
                }
                val outcome = try {
                    extractor.extract(name, categoryConfig, capped)
                } catch (e: BillingException) {
                    db.markUnprocessed(cappedLinks)
                    errorLog.recordBilling(name, "[Dedup]")
                    logger.warn { "[Category:$name][Dedup] Billing/quota limit in Step 1 — skipping." }
                    continue
                } catch (e: Exception) {
                    db.markUnprocessed(cappedLinks)
                    errorLog.recordError(name, "[Dedup]", e)
                    logger.error(e) { "[Category:$name][Dedup] Step 1 threw unexpectedly — skipping." }
                    continue
                }

                when (outcome) {
                    is ExtractOutcome.FallbackToLegacy -> {
                        db.markUnprocessed(cappedLinks)
                        logger.warn { "[Category:$name][Dedup] Step 1 fell back to legacy — running legacy chunked path." }
                        // shortlist stays null → fall through to legacy chunked submission
                    }
                    is ExtractOutcome.PendingBatch -> {
                        // Batch submitted; Step 2 fires from the callback when the batch resolves.
                        val capturedExtractor = extractor
                        outcome.future
                            .thenAccept { rawJson ->
                                val result = capturedExtractor.resumeFromBatch(name, categoryConfig, capped, rawJson)
                                when {
                                    result == null -> {
                                        db.markUnprocessed(cappedLinks)
                                        logger.warn { "[Category:$name][Dedup] Extract batch result parse failed — reverting to UNPROCESSED." }
                                    }
                                    result.shortlist.isEmpty() -> {
                                        logger.info {
                                            "[Category:$name][Dedup] Empty shortlist from batch extract — marking ${cappedLinks.size} article(s) PROCESSED."
                                        }
                                        db.markProcessed(cappedLinks)
                                    }
                                    else -> deliverShortlist(name, categoryConfig, capped, result.shortlist)
                                }
                            }
                            .exceptionally { ex ->
                                val cause = if (ex is ExecutionException) ex.cause ?: ex else ex
                                db.markUnprocessed(cappedLinks)
                                errorLog.recordError(name, "[Dedup]", cause)
                                if (cause is BillingException) {
                                    logger.warn { "[Category:$name][Dedup] Billing limit during extract batch polling." }
                                } else {
                                    logger.error(cause) { "[Category:$name][Dedup] Extract batch failed — reverting to UNPROCESSED." }
                                }
                                null
                            }
                        continue  // Skip Step 2 in this cycle; fires async via callback
                    }
                    is ExtractOutcome.Ready -> {
                        val extraction = outcome.result
                        if (extraction.shortlist.isEmpty()) {
                            logger.info {
                                "[Category:$name][Dedup] Empty shortlist (${extraction.extractions.size} extractions, all dupes/rejects) " +
                                    "— marking ${cappedLinks.size} article(s) PROCESSED, skipping submission."
                            }
                            db.markProcessed(cappedLinks)
                            continue
                        }
                        val digestCfg = categoryConfig.dedup?.digest
                        if (digestCfg != null && digestCfg.ranker.enabled) {
                            val shortlistSize = extraction.shortlist.size
                            if (shortlistSize < digestCfg.minStrongItems) {
                                val lastDigestAt = db.fetchRecentSummaries(name, 1).firstOrNull()?.createdAt
                                val hoursSince = lastDigestAt?.let {
                                    Duration.between(it, LocalDateTime.now()).toHours()
                                } ?: Long.MAX_VALUE
                                val pastMaxWait = hoursSince >= digestCfg.maxWaitHours
                                val meetsForceFloor = shortlistSize >= digestCfg.minItemsOnForcePublish
                                if (!(pastMaxWait && meetsForceFloor)) {
                                    logger.info {
                                        "[Category:$name][Dedup] Weak shortlist: $shortlistSize < minStrongItems=${digestCfg.minStrongItems} " +
                                            "(hoursSinceLastDigest=$hoursSince, maxWaitHours=${digestCfg.maxWaitHours}). " +
                                            "Holding back; marking ${cappedLinks.size} article(s) PROCESSED."
                                    }
                                    db.markProcessed(cappedLinks)
                                    continue
                                }
                                logger.info {
                                    "[Category:$name][Dedup] Force-publishing weak shortlist ($shortlistSize items) " +
                                        "— hoursSinceLastDigest=$hoursSince ≥ maxWaitHours=${digestCfg.maxWaitHours}."
                                }
                            }
                        }
                        shortlist = extraction.shortlist
                        dedupCapped = capped
                    }
                }
            }

            // ── Step 3+4: build prompt + route (batch preferred, sync as fallback) ─
            // PromptBuilder centralises the user/system messages; both routes call it
            // so the LLM input is identical regardless of which API we hit.
            if (shortlist != null && dedupCapped != null) {
                // Render mode: one digest per category (no chunking — already capped to ≤100).
                submitOrSync(
                    name = name,
                    categoryConfig = categoryConfig,
                    articles = dedupCapped,
                    shortlist = shortlist,
                    resolvedDedup = resolvedDedup,
                    previousSummaries = emptyList(),  // shortlist already encodes the editorial decision
                    chunkLabel = "",
                    isBatchingStuck = bypassPrimaryBatch,
                    useBatchFallback = useBatchFallback
                )
            } else {
                // Legacy path: chunked submission, no dedup.
                val chunks = articles.chunked(chunkSize)
                logger.info { "[Category:$name] Submitting ${articles.size} news in ${chunks.size} chunk(s) of up to $chunkSize..." }
                for ((chunkIdx, chunk) in chunks.withIndex()) {
                    val chunkLabel = if (chunks.size > 1) " [chunk ${chunkIdx + 1}/${chunks.size}]" else ""
                    submitOrSync(
                        name = name,
                        categoryConfig = categoryConfig,
                        articles = chunk,
                        shortlist = null,
                        resolvedDedup = null,
                        previousSummaries = previousSummaries,
                        chunkLabel = chunkLabel,
                        isBatchingStuck = bypassPrimaryBatch,
                        useBatchFallback = useBatchFallback
                    )
                }
            }
        }
    }

    /**
     * Drives Step 2 (digest render) for a category whose Step 1 shortlist has already
     * been produced — either inline (sync path) or by the extract-batch callback /
     * startup resume. Resolves dedup prompts, then calls [submitOrSync].
     */
    fun deliverShortlist(
        name: String,
        categoryConfig: CategoryConfig,
        articles: List<Article>,
        shortlist: List<ShortlistItem>
    ) {
        val resolvedDedup = promptLoader.resolve(categoryConfig) ?: run {
            logger.warn { "[Category:$name][Dedup] deliverShortlist: cannot resolve dedup prompts — skipping Step 2" }
            return
        }
        val pendingForCategory = db.countPendingBatchesForCategory(name)
        val primaryMaxPending = config.processing.primaryMaxPending
        val secondaryMaxPending = config.processing.secondaryMaxPending
        val batchingDisabled = primaryMaxPending == 0 || categoryConfig.skipBatch
        val hasBatchFallback = categoryConfig.llm?.batchFallback != null
        val syncCutoff = if (hasBatchFallback) primaryMaxPending + secondaryMaxPending else primaryMaxPending
        val isBatchingStuck = !batchingDisabled && pendingForCategory >= syncCutoff
        val useBatchFallback = hasBatchFallback &&
            (batchingDisabled || (!isBatchingStuck && pendingForCategory >= primaryMaxPending))
        val bypassPrimaryBatch = isBatchingStuck || (batchingDisabled && !useBatchFallback)
        submitOrSync(
            name = name,
            categoryConfig = categoryConfig,
            articles = articles,
            shortlist = shortlist,
            resolvedDedup = resolvedDedup,
            previousSummaries = emptyList(),
            chunkLabel = "",
            isBatchingStuck = bypassPrimaryBatch,
            useBatchFallback = useBatchFallback
        )
    }

    /**
     * Submits a single category-or-chunk via the Batch API.
     *
     * When the category is already backpressured (`isBatchingStuck`), bypasses batch and
     * uses the sync OpenAI client instead. Submission failures do NOT trigger sync fallback;
     * they revert the affected articles to UNPROCESSED for retry next cycle.
     *
     * Marks articles PROCESSING up-front; the async callback / sync exception handlers
     * handle UNPROCESSED reversion.
     */
    private fun submitOrSync(
        name: String,
        categoryConfig: CategoryConfig,
        articles: List<Article>,
        shortlist: List<ShortlistItem>?,
        resolvedDedup: ResolvedDedupPrompts?,
        previousSummaries: List<String>,
        chunkLabel: String,
        isBatchingStuck: Boolean,
        useBatchFallback: Boolean = false
    ) {
        // Layer 3.5: log-only event-level embedding analysis. This is the single chokepoint
        // every non-empty shortlist passes through (sync Ready, extract-batch callback, and
        // startup resume all converge here), so one call covers all routes. Analyze-only.
        if (shortlist != null) {
            eventSemanticAnalyzer?.analyzeAndLog(name, shortlist)
        }

        val effectiveArticles = if ((isBatchingStuck || useBatchFallback) && shortlist == null) {
            articles.take(SYNC_FALLBACK_CAP)
        } else {
            articles
        }
        val links = effectiveArticles.map { it.link }
        // For the dedup path, articles were already PROCESSING from Step 1 — markProcessing
        // is idempotent (won't downgrade PROCESSED rows), so calling again is safe.
        db.markProcessing(links)

        val input = if (shortlist != null) {
            requireNotNull(resolvedDedup) { "shortlist set without resolvedDedup for category '$name'" }
            CategoryInput(
                emoji = categoryConfig.emoji,
                articles = effectiveArticles,
                shortlist = shortlist,
                renderSystemPrompt = resolvedDedup.renderSystem,
                renderUserPrompt = resolvedDedup.renderUser
            )
        } else {
            CategoryInput(
                emoji = categoryConfig.emoji,
                articles = effectiveArticles,
                systemPrompt = categoryConfig.systemPrompt,
                userPrompt = categoryConfig.userPrompt,
                previousSummaries = previousSummaries
            )
        }

        val mode = if (shortlist != null) "render" else "legacy"
        logger.info { "[Category:$name]$chunkLabel Submitting ${effectiveArticles.size} news to OpenAI Batch API ($mode mode)..." }

        if (isBatchingStuck || useBatchFallback) {
            val syncClient = if (useBatchFallback) {
                llmClientsFactory.forBatchFallback(categoryConfig) ?: error("useBatchFallback set but forBatchFallback returned null for '$name'")
            } else {
                llmClientsFactory.forRender(categoryConfig)
            }
            val routeLabel = if (useBatchFallback) "batchFallback-override" else "stuck-sync-fallback"
            logger.info { "[Category:$name]$chunkLabel Routing via $routeLabel (pending batch detected)." }
            runSyncFallback(
                name = name,
                categoryConfig = categoryConfig,
                articles = effectiveArticles,
                shortlist = shortlist,
                resolvedDedup = resolvedDedup,
                previousSummaries = previousSummaries,
                chunkLabel = chunkLabel,
                syncClient = syncClient
            )
            return
        }

        val ctxLabel = chunkLabel.trim().ifEmpty { "[submit]" }
        val future: CompletableFuture<String> = try {
            llmClientsFactory.forBatch(categoryConfig).submitCategoryBatch(name, input)
        } catch (e: BillingException) {
            db.markUnprocessed(links)
            errorLog.recordBilling(name, ctxLabel)
            logger.warn { "[Category:$name]$chunkLabel Billing/quota limit reached — skipping." }
            return
        } catch (e: Exception) {
            db.markUnprocessed(links)
            errorLog.recordError(name, ctxLabel, e)
            logger.error(e) { "[Category:$name]$chunkLabel Batch submission failed. Reverted articles to UNPROCESSED." }
            return
        }

        future
            .thenAccept { summary -> deliverer.deliver(name, summary, articles, shortlist) }
            .exceptionally { ex ->
                val cause = if (ex is ExecutionException) ex.cause ?: ex else ex
                db.markUnprocessed(links)
                errorLog.recordError(name, ctxLabel, cause)
                if (cause is BillingException) {
                    logger.warn { "[Category:$name]$chunkLabel Billing/quota limit reached." }
                } else {
                    logger.error(cause) { "[Category:$name]$chunkLabel Batch failed" }
                }
                null
            }

        logger.info { "[Category:$name]$chunkLabel Polling in background." }
    }

    /**
     * Sync fallback path. When [shortlist] is non-null, runs the render-mode call so the
     * sync route produces the same editorial digest that the batch route would have.
     */
    private fun runSyncFallback(
        name: String,
        categoryConfig: CategoryConfig,
        articles: List<Article>,
        shortlist: List<ShortlistItem>? = null,
        resolvedDedup: ResolvedDedupPrompts? = null,
        previousSummaries: List<String> = emptyList(),
        chunkLabel: String = "",
        syncClient: metifikys.ai.LlmClient = llmClientsFactory.forRender(categoryConfig)
    ) {
        val links = articles.map { it.link }
        db.markProcessing(links)
        try {
            val summary = if (shortlist != null) {
                requireNotNull(resolvedDedup) { "shortlist set without resolvedDedup for sync fallback '$name'" }
                syncClient.summarizeShortlist(
                    category = name,
                    emoji = categoryConfig.emoji,
                    shortlist = shortlist,
                    articles = articles,
                    renderSystemPrompt = resolvedDedup.renderSystem,
                    renderUserPromptTemplate = resolvedDedup.renderUser
                )
            } else {
                syncClient.summarizeArticles(
                    name,
                    categoryConfig.emoji,
                    articles,
                    categoryConfig.systemPrompt,
                    categoryConfig.userPrompt,
                    previousSummaries,
                    maxArticles = SYNC_FALLBACK_CAP
                )
            }
            deliverer.deliver(name, summary, articles, shortlist)
        } catch (e: BillingException) {
            db.markUnprocessed(links)
            errorLog.recordBilling(name, chunkLabel.trim().ifEmpty { "[sync]" })
            logger.warn { "[Category:$name]$chunkLabel Billing/quota during sync fallback." }
        } catch (e: Exception) {
            db.markUnprocessed(links)
            errorLog.recordError(name, chunkLabel.trim().ifEmpty { "[sync]" }, e)
            logger.error(e) { "[Category:$name]$chunkLabel Sync fallback failed" }
        }
    }
}
