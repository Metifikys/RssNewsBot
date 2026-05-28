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
import metifikys.digest.SemanticDedupDetector
import metifikys.fetch.ArticleFetcher
import metifikys.fetch.ArticleSummarizer
import metifikys.fetch.RssFetcher
import metifikys.telegram.StatusCommand
import metifikys.telegram.StatusPoster
import metifikys.telegram.TelegramSender
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
        } else null
) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private val errorLog = CycleErrorLog()

    private val statusPoster: StatusPoster? = config.admin.statusChatId?.let { chatId ->
        StatusPoster(chatId, sender, StatusCommand(config, db, errorLog), errorLog)
    }

    internal val deliverer = DigestDeliverer(config, db, sender)

    private val categoryProcessor = CategoryProcessor(
        config = config,
        db = db,
        llmClientsFactory = llmClientsFactory,
        promptLoader = promptLoader,
        deliverer = deliverer,
        eventExtractor = eventExtractor,
        errorLog = errorLog
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
    }

    fun runDigestCycle() = digestCycle.runCycle()

    internal fun resumePendingBatches() = digestCycle.resumePendingBatches()
}
