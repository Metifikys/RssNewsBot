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
import metifikys.fetch.ArticleFetcher
import metifikys.fetch.ArticleSummarizer
import metifikys.fetch.RssFetcher
import metifikys.telegram.StatusCommand
import metifikys.telegram.StatusPoster
import metifikys.telegram.TelegramSender
import metifikys.telegram.TelegramUpdatesPoller
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Entry point: wires collaborators, owns the scheduler thread, and delegates each tick
 * to [DigestCycle]. All digest logic lives in `metifikys.digest.*` and `metifikys.format.*`.
 */
class NewsBot(
    private val config: AppConfig,
    fetcher: RssFetcher = RssFetcher(allowPrivateHosts = true),
    articleFetcher: ArticleFetcher = ArticleFetcher(RssFetcher(allowPrivateHosts = true)),
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
        } else null
) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

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
        semanticDedupDetector = semanticDedupDetector
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
        val syncProvider = if (config.openrouter != null) "openrouter(${config.openrouter.model})" else "openai(${config.openai.model})"
        val extractorProvider = if (config.openrouter != null) {
            "openai(${config.openai.model}) + openrouter(${config.openrouter.model}) on every 2nd request"
        } else {
            "openai(${config.openai.model})"
        }
        logger.info {
            "[LLM] sync provider: $syncProvider; step1 extractor: $extractorProvider; " +
                "batch provider: openai(${config.openai.batchModel})"
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

        // Graceful shutdown: flush the scheduler and wait up to 30s for in-flight tasks
        Runtime.getRuntime().addShutdownHook(Thread({
            logger.info { "[Shutdown] Stopping scheduler..." }
            scheduler.shutdown()
            weeklyScheduler?.shutdown()
            updatesPoller?.stop()
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.warn { "[Shutdown] Scheduler did not terminate in 30s; forcing." }
                    scheduler.shutdownNow()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            logger.info { "[Shutdown] Done." }
        }, "shutdown-hook"))

        digestCycle.resumePendingBatches()
        logger.info { "Running first digest cycle immediately." }
        runDigestCycle()
        val intervalMinutes = config.scheduler.intervalMinutes
        scheduler.scheduleAtFixedRate(
            { runDigestCycle() },
            intervalMinutes,
            intervalMinutes,
            TimeUnit.MINUTES
        )
        logger.info { "Next cycle in $intervalMinutes minute(s)." }

        weeklyScheduler?.let {
            val w = config.weekly!!
            it.start()
            logger.info {
                "[Weekly] Weekly top-story roundup enabled: ${w.dayOfWeek} ${w.time}, topN=${w.topN}, " +
                    "lookback=${w.lookbackDays}d, minMentions=${w.minMentions}."
            }
        }

        updatesPoller?.start()
    }

    fun runDigestCycle() = digestCycle.runCycle()

    internal fun resumePendingBatches() = digestCycle.resumePendingBatches()
}
