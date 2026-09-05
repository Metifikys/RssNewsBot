package metifikys

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.Embedder
import metifikys.ai.LlmCallRecorder
import metifikys.ai.LlmClientsFactory
import metifikys.ai.LlmEndpoint
import metifikys.ai.dedup.EventExtractor
import metifikys.ai.dedup.PromptLoader
import metifikys.config.AppConfig
import metifikys.db.NewsDatabase
import metifikys.digest.CategoryProcessor
import metifikys.digest.CycleErrorLog
import metifikys.digest.DigestCycle
import metifikys.digest.DigestDeliverer
import metifikys.config.dayOfWeekParsed
import metifikys.config.timeParsed
import metifikys.digest.EventSemanticAnalyzer
import metifikys.digest.SemanticDedupDetector
import metifikys.digest.WeeklyDigest
import metifikys.digest.WeeklyScheduler
import metifikys.feedback.AffinityAggregator
import metifikys.fetch.ArticleFetcher
import metifikys.fetch.ArticleSummarizer
import metifikys.fetch.HostThrottle
import metifikys.fetch.RssFetcher
import metifikys.telegram.StatusCommand
import metifikys.telegram.StatusPoster
import metifikys.telegram.TelegramSender
import metifikys.telegram.TelegramUpdatesPoller
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * ONE per-host gate for every outbound request the bot makes — RSS feeds and the article pages
 * they link to alike. Those routinely share a host (reddit, a publisher's own domain), so a 429
 * earned by either must slow down both; separate throttles would let the article fetcher, which
 * fetches 8 pages at a time, immediately re-earn the ban the feed fetcher just backed off from.
 *
 * File-private singleton rather than a constructor parameter so the public [NewsBot] signature
 * (and its positional call sites) stays untouched — it is only the DEFAULT for the two fetchers,
 * both of which tests still inject freely. Hosts that never 429 cost nothing, so a process-wide
 * instance carries no state a second bot instance would notice.
 */
private val SHARED_HOST_THROTTLE = HostThrottle()

/**
 * Entry point: wires collaborators, owns the scheduler thread, and delegates each tick
 * to [DigestCycle]. All digest logic lives in `metifikys.digest.*` and `metifikys.format.*`.
 */
class NewsBot(
    private val config: AppConfig,
    private val fetcher: RssFetcher = RssFetcher(
        allowPrivateHosts = true,
        maxAttempts = config.fetcher.maxAttempts,
        retryDelayMs = config.fetcher.retryDelaySeconds * 1000,
        maxConcurrentFetches = config.fetcher.maxConcurrentFetches,
        fetchDeadlineMs = config.fetcher.fetchDeadlineSeconds * 1000,
        hostThrottle = SHARED_HOST_THROTTLE
    ),
    // Validation-only RssFetcher (validateFeedUrl); its lazy fetch pool never starts.
    articleFetcher: ArticleFetcher = ArticleFetcher(
        RssFetcher(allowPrivateHosts = true),
        hostThrottle = SHARED_HOST_THROTTLE
    ),
    private val db: NewsDatabase = NewsDatabase(config.database.path),
    sender: TelegramSender = TelegramSender(config.telegram.botToken),
    promptLoader: PromptLoader = PromptLoader(),
    llmCallRecorder: LlmCallRecorder = LlmCallRecorder(db, metifikys.ai.LlmPricing(config.pricing)),
    llmClientsFactory: LlmClientsFactory = LlmClientsFactory(config, db, llmCallRecorder),
    articleSummarizer: ArticleSummarizer = ArticleSummarizer(config, config.categories, llmClientsFactory),
    /**
     * Test escape hatch: when non-null, every category uses this extractor instead of
     * one built per-category from [llmClientsFactory]. Production code leaves this null.
     */
    eventExtractor: EventExtractor? = null,
    /**
     * Optional log-only semantic-dedup detector. Constructed by default when at least
     * one category has `semanticDedup.enabled=true`; null otherwise so no embedding
     * client is created when nothing uses it.
     */
    semanticDedupDetector: SemanticDedupDetector? =
        if (config.categories.values.any { it.semanticDedup?.enabled == true }) {
            SemanticDedupDetector(config, db, Embedder(LlmEndpoint.forOpenAI(config), llmCallRecorder))
        } else null,
    /**
     * Optional log-only event-level embedding analyzer (Layer 3.5). Constructed by default
     * when at least one category has `semanticDedup.eventEnabled=true`; null otherwise so no
     * embedding client is created when nothing uses it.
     */
    eventSemanticAnalyzer: EventSemanticAnalyzer? =
        if (config.categories.values.any { it.semanticDedup?.eventEnabled == true }) {
            EventSemanticAnalyzer(config, db, Embedder(LlmEndpoint.forOpenAI(config), llmCallRecorder))
        } else null,
    /**
     * Optional reaction-feedback aggregator (phase 1 of the feedback loop). Constructed
     * only when `feedback.enabled=true`; aggregation-only, so it is safe to run even
     * while nothing consumes the scores.
     */
    affinityAggregator: AffinityAggregator? =
        if (config.feedback.enabled) AffinityAggregator(config.feedback, db) else null
) {

    /** Cycle-mode scheduler (`scheduler.perCategory=false`): one global cycle, never overlapping. */
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "digest-cycle") }

    /** Per-category mode: one single-thread scheduler per category, so runs of a category never overlap. */
    private val categorySchedulers = LinkedHashMap<String, ScheduledExecutorService>()

    /** Per-category mode: retention cleanup, affinity rebuild and the status post, on their own clock. */
    private val maintenanceScheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "maintenance") }

    private val errorLog = CycleErrorLog()

    private val statusPoster: StatusPoster? = config.admin.statusChatId?.let { chatId ->
        StatusPoster(chatId, sender, StatusCommand(config, db, errorLog), errorLog)
    }

    /**
     * Optional Telegram reaction poller. Constructed only when `telegram.updatesPolling=true`,
     * so no `getUpdates` loop runs (and no 409 risk) when the feature is off.
     */
    private val updatesPoller: TelegramUpdatesPoller? =
        if (config.telegram.updatesPolling) TelegramUpdatesPoller(config.telegram.botToken, db) else null

    internal val deliverer = DigestDeliverer(config, db, sender)

    private val categoryProcessor = CategoryProcessor(
        config = config,
        db = db,
        llmClientsFactory = llmClientsFactory,
        promptLoader = promptLoader,
        deliverer = deliverer,
        eventExtractor = eventExtractor,
        errorLog = errorLog,
        eventSemanticAnalyzer = eventSemanticAnalyzer
    )

    private val digestCycle = DigestCycle(
        config = config,
        fetcher = fetcher,
        articleFetcher = articleFetcher,
        articleSummarizer = articleSummarizer,
        db = db,
        llmClientsFactory = llmClientsFactory,
        categoryProcessor = categoryProcessor,
        deliverer = deliverer,
        promptLoader = promptLoader,
        statusPoster = statusPoster,
        errorLog = errorLog,
        semanticDedupDetector = semanticDedupDetector,
        affinityAggregator = affinityAggregator
    )

    /**
     * Optional weekly "top story of the week" roundup. Constructed (with its own embedding client)
     * only when `weekly.enabled=true`, so no extra OpenAI client is created when the feature is off.
     */
    private val weeklyDigest: WeeklyDigest? =
        if (config.weekly?.enabled == true) {
            WeeklyDigest(
                config = config,
                db = db,
                llmClientsFactory = llmClientsFactory,
                embedder = Embedder(LlmEndpoint.forOpenAI(config), llmCallRecorder),
                sender = sender
            )
        } else null

    private val weeklyScheduler: WeeklyScheduler? = config.weekly?.takeIf { it.enabled }?.let { w ->
        WeeklyScheduler(
            day = w.dayOfWeekParsed(),
            time = w.timeParsed(),
            runnable = { weeklyDigest?.run() }
        )
    }

    fun start() {
        logger.info { "RssNewsBot started." }
        val orModels = config.openrouter?.modelPriority?.joinToString(" → ")
        val syncProvider = if (orModels != null) "openrouter($orModels)" else "openai(${config.openai.model})"
        val extractorProvider = if (orModels != null) {
            "openai(${config.openai.model}) + openrouter($orModels) on every 2nd request"
        } else {
            "openai(${config.openai.model})"
        }
        logger.info {
            "[LLM] sync provider: $syncProvider; step1 extractor: $extractorProvider; " +
                "batch provider: openai(${config.openai.batchModel})"
        }
        logger.info {
            "[Fetch] queue model: maxConcurrent=${config.fetcher.maxConcurrentFetches}, " +
                "attempts=${config.fetcher.maxAttempts}, retryDelay=${config.fetcher.retryDelaySeconds}s, " +
                "deadline=${config.fetcher.fetchDeadlineSeconds}s"
        }
        val summarizeFeeds = config.categories.flatMap { (cat, cfg) ->
            cfg.feeds.filter { !it.summarize.isNullOrBlank() }.map { "$cat:${it.url}=${it.summarize}" }
        }
        if (summarizeFeeds.isNotEmpty()) {
            logger.info { "[LLM] per-article summarize enabled for ${summarizeFeeds.size} feed(s): $summarizeFeeds" }
        }
        val sdCats = config.categories
            .filter { (_, c) -> c.semanticDedup?.enabled == true }
            .map { (n, c) ->
                val sd = c.semanticDedup!!
                val hardTail = sd.hardThreshold?.let { " hard=$it" } ?: ""
                "$n[model=${sd.model} thr=${sd.threshold} window=${sd.windowDays}d topK=${sd.topK}$hardTail]"
            }
        if (sdCats.isNotEmpty()) {
            val withHardFilter = config.categories.values.count { it.semanticDedup?.hardThreshold != null }
            val mode = if (withHardFilter > 0) "log + hard-filter ($withHardFilter)" else "log-only"
            logger.info { "[SemanticDedup] $mode detector enabled for ${sdCats.size} category(ies): $sdCats" }
        }
        val eventSdCats = config.categories
            .filter { (_, c) -> c.semanticDedup?.eventEnabled == true }
            .map { (n, c) ->
                val sd = c.semanticDedup!!
                val hardTail = sd.eventHardThreshold?.let { " hard=$it" } ?: ""
                "$n[model=${sd.model} eventThr=${sd.eventThreshold} window=${sd.windowDays}d topK=${sd.topK}$hardTail]"
            }
        if (eventSdCats.isNotEmpty()) {
            val withHardFilter = config.categories.values.count { it.semanticDedup?.eventHardThreshold != null }
            val mode = if (withHardFilter > 0) "log + hard-filter ($withHardFilter)" else "log-only"
            logger.info { "[EventSemanticDedup] $mode analyzer enabled for ${eventSdCats.size} category(ies): $eventSdCats" }
        }
        if (config.feedback.enabled) {
            val fb = config.feedback
            logger.info {
                "[Affinity] reaction-feedback aggregation enabled: attribution=${fb.attributionHours}h " +
                    "lookback=${fb.lookbackDays}d halflife=${fb.halflifeDays}d k=${fb.shrinkageK} " +
                    "minSamples=${fb.minSamples} minVolume=${fb.minVolume} " +
                    "recompute every ${fb.recomputeHours}h"
            }
            val rankCats = config.categories.mapNotNull { (n, c) ->
                val r = c.dedup?.digest?.ranker ?: return@mapNotNull null
                if (r.reactionWeight > 0.0) {
                    val mode = if (r.reactionLogOnly) "LOG-ONLY" else "APPLIED"
                    "$n[weight=${r.reactionWeight} boost=±${r.maxAffinityBoost} $mode]"
                } else null
            }
            if (rankCats.isNotEmpty()) {
                logger.info { "[AffinityRank] reaction term active for ${rankCats.size} category(ies): $rankCats" }
            } else {
                logger.info { "[AffinityRank] no category sets ranker.reactionWeight — selection unaffected (phase 1 aggregation only)" }
            }
        }
        val overrides = config.categories.flatMap { (n, c) ->
            val ovr = c.llm ?: return@flatMap emptyList()
            listOfNotNull(
                ovr.extract?.let { "$n.extract=${it.provider}(${it.model})" },
                ovr.render?.let { "$n.render=${it.provider}(${it.model})" },
                ovr.batch?.let { "$n.batch=${it.provider}(${it.model})" },
                ovr.summarize?.let { "$n.summarize=${it.provider}(${it.model})" }
            )
        }
        if (overrides.isNotEmpty()) {
            logger.info { "[LLM] per-category overrides: $overrides" }
        }

        // Graceful shutdown: stop every scheduler and wait up to 30s in total for in-flight
        // runs; whatever is still running is interrupted (pipelines revert their PROCESSING
        // rows on interrupt, and the startup reclaim covers a worker killed mid-flight).
        Runtime.getRuntime().addShutdownHook(Thread({
            logger.info { "[Shutdown] Stopping schedulers..." }
            val executors = listOf(scheduler, maintenanceScheduler) + categorySchedulers.values
            executors.forEach { it.shutdown() }
            weeklyScheduler?.shutdown()
            updatesPoller?.stop()
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            try {
                for (executor in executors) {
                    val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
                    if (!executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                        logger.warn { "[Shutdown] A scheduler did not terminate in 30s; forcing." }
                        executor.shutdownNow()
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                executors.forEach { it.shutdownNow() }
            }
            fetcher.shutdown()
            logger.info { "[Shutdown] Done." }
        }, "shutdown-hook"))

        // Start the reaction poller before the first cycle so it isn't blocked behind a slow
        // (or failing) digest run — reaction collection is independent of the digest pipeline.
        updatesPoller?.start()

        digestCycle.reclaimOrphanedProcessing()
        digestCycle.resumePendingBatches()
        if (config.scheduler.perCategory) startPerCategorySchedulers() else startCycleScheduler()

        weeklyScheduler?.let {
            val w = config.weekly!!
            it.start()
            logger.info {
                "[Weekly] Weekly top-story roundup enabled: ${w.dayOfWeek} ${w.time}, topN=${w.topN}, " +
                    "lookback=${w.lookbackDays}d, minMentions=${w.minMentions}."
            }
        }
    }

    fun runDigestCycle() = digestCycle.runCycle()

    /** Per-category mode: one run of [name]'s pipeline (fetch → digest), for tests and manual triggers. */
    fun runCategory(name: String) = digestCycle.runCategory(name)

    /**
     * Default mode. Every category gets its own single-thread scheduler at its own interval
     * (`categories.<name>.intervalMinutes`, else `scheduler.intervalMinutes`), first run
     * immediately; housekeeping and the status post tick on the shared interval. No category
     * ever waits for another, and a run that outlasts its interval only delays its own next run.
     */
    private fun startPerCategorySchedulers() {
        val defaultInterval = config.scheduler.intervalMinutes
        // Cleanup + affinity + first status snapshot before the first pipelines start, as a
        // global cycle used to do at its start.
        guarded("[Maintenance]") { digestCycle.runMaintenance() }
        val cadence = config.categories.map { (name, cat) ->
            val interval = cat.intervalMinutes ?: defaultInterval
            val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "digest-$name") }
            categorySchedulers[name] = executor
            executor.scheduleAtFixedRate(
                { guarded("[Cycle:$name]") { digestCycle.runCategory(name) } },
                0,
                interval,
                TimeUnit.MINUTES
            )
            "$name every ${interval}m"
        }
        maintenanceScheduler.scheduleAtFixedRate(
            { guarded("[Maintenance]") { digestCycle.runMaintenance() } },
            defaultInterval,
            defaultInterval,
            TimeUnit.MINUTES
        )
        logger.info {
            "[Scheduler] per-category mode: ${cadence.joinToString(", ")}; " +
                "maintenance + status every ${defaultInterval}m."
        }
    }

    /** Legacy mode (`scheduler.perCategory=false`): one global cycle at the shared interval. */
    private fun startCycleScheduler() {
        logger.info { "Running first digest cycle immediately." }
        guarded("[Scheduler]") { runDigestCycle() }
        val intervalMinutes = config.scheduler.intervalMinutes
        scheduler.scheduleAtFixedRate(
            { guarded("[Scheduler]") { runDigestCycle() } },
            intervalMinutes,
            intervalMinutes,
            TimeUnit.MINUTES
        )
        logger.info { "Next cycle in $intervalMinutes minute(s)." }
    }

    /**
     * Scheduler-facing wrapper. [java.util.concurrent.ScheduledExecutorService] silently cancels
     * ALL future executions when a task throws, so anything escaping a run — the pipeline guards
     * `Exception` but not `Error` (OOM, StackOverflowError, NoClassDefFoundError) — would leave
     * the process alive with that schedule permanently dead and nothing in the log to say so.
     * Catching [Throwable] here keeps the schedule alive and makes the failure loud.
     */
    private fun guarded(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error(t) {
                "$label run threw ${t.javaClass.simpleName} — schedule kept alive, next run as planned."
            }
        }
    }

    internal fun resumePendingBatches() = digestCycle.resumePendingBatches()
}
