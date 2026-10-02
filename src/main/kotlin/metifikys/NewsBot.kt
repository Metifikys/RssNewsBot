package metifikys

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.CliHealthMonitor
import metifikys.ai.Embedder
import metifikys.ai.LlmCallRecorder
import metifikys.ai.LlmClientsFactory
import metifikys.ai.LlmEndpoint
import metifikys.ai.dedup.EventExtractor
import metifikys.ai.dedup.PromptFinding
import metifikys.ai.dedup.PromptLoader
import metifikys.ai.dedup.PromptValidator
import metifikys.config.AppConfig
import metifikys.db.NewsDatabase
import metifikys.db.RetentionArchiver
import metifikys.digest.CategoryExecutors
import metifikys.digest.CategoryProcessor
import metifikys.digest.CycleErrorLog
import metifikys.digest.DigestCycle
import metifikys.digest.DigestDeliverer
import metifikys.config.dayOfWeekParsed
import metifikys.config.timeParsed
import metifikys.digest.EventSemanticAnalyzer
import metifikys.digest.LongForm
import metifikys.digest.MuteRegistry
import metifikys.digest.PauseStore
import metifikys.digest.PersonaWriter
import metifikys.digest.UpdateDiff
import metifikys.digest.ShadowRunner
import metifikys.digest.FactChecker
import metifikys.digest.PostImages
import metifikys.digest.SemanticDedupDetector
import metifikys.digest.TrackerUpdater
import metifikys.digest.WeeklyDigest
import metifikys.digest.WeeklyScheduler
import metifikys.enrich.BadgeHttp
import metifikys.enrich.BadgeProviders
import metifikys.enrich.BadgeResolver
import metifikys.enrich.DbIntroStore
import metifikys.enrich.WikipediaIntroProvider
import metifikys.feedback.AffinityAggregator
import metifikys.feedback.MuteSuggester
import metifikys.fetch.ArticleFetcher
import metifikys.fetch.ArticleSummarizer
import metifikys.feeds.FeedCandidateFinder
import metifikys.feeds.FeedDiscovery
import metifikys.fetch.FeedHealthRecorder
import metifikys.fetch.HostThrottle
import metifikys.story.EventHistoryRecorder
import metifikys.fetch.RssFetcher
import metifikys.fetch.TextScrubRules
import metifikys.fetch.UrlGuard
import metifikys.log.RunContext
import metifikys.telegram.AdminAlerts
import metifikys.telegram.AdminAuth
import metifikys.telegram.AdminCallbackRouter
import metifikys.telegram.AdminCommandRouter
import metifikys.telegram.AdminCommands
import metifikys.telegram.ModerationNotifier
import metifikys.telegram.PostInspector
import metifikys.telegram.RunListener
import metifikys.telegram.RunQueued
import metifikys.telegram.StatusCommand
import metifikys.telegram.StatusPoster
import metifikys.telegram.TelegramSender
import metifikys.telegram.TelegramUpdatesPoller
import metifikys.telegraph.TelegraphPublisher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
        urlGuard = UrlGuard.forConfig(config),
        maxAttempts = config.fetcher.maxAttempts,
        retryDelayMs = config.fetcher.retryDelaySeconds * 1000,
        maxConcurrentFetches = config.fetcher.maxConcurrentFetches,
        fetchDeadlineMs = config.fetcher.fetchDeadlineSeconds * 1000,
        hostThrottle = SHARED_HOST_THROTTLE,
        healthRecorder = if (config.feedHealth.enabled) FeedHealthRecorder() else null,
        pathScopedThrottleHosts = config.fetcher.pathScopedThrottleHosts,
        imagePickers = PostImages.ingestPickers(config),
        videoCategories = config.categories.filterValues { it.post?.badges?.trailer == true }.keys
    ),
    // Same guard rule as the feed fetcher: private addresses only for the configured feed hosts
    // (and fetcher.privateHostAllowlist) — never for a link that merely came out of a feed.
    articleFetcher: ArticleFetcher = ArticleFetcher(
        UrlGuard.forConfig(config),
        hostThrottle = SHARED_HOST_THROTTLE,
        scrubRules = TextScrubRules.load(config.fetcher.scrubRulesFile)
    ),
    private val db: NewsDatabase = NewsDatabase(config.database.path),
    sender: TelegramSender = TelegramSender(config.telegram.botToken),
    private val promptLoader: PromptLoader = PromptLoader(),
    llmCallRecorder: LlmCallRecorder = LlmCallRecorder(db, metifikys.ai.LlmPricing(config.pricing)),
    /**
     * `claude` / `codex` login and usage-limit tracking with admin-chat alerts; built only with
     * `health.enabled` (null otherwise, and the CLI clients report nowhere).
     */
    private val cliHealthMonitor: CliHealthMonitor? =
        if (config.health.enabled) {
            val alerts = AdminAlerts(config.admin.statusChatId, sender)
            CliHealthMonitor(config, db, alert = { text, silent -> alerts.send(text, silent) })
        } else null,
    llmClientsFactory: LlmClientsFactory = LlmClientsFactory(config, db, llmCallRecorder, cliHealthMonitor),
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
        if (config.feedback.enabled) AffinityAggregator(config.feedback, db) else null,
    /**
     * Optional live-data footer badges (Steam / HN / GitHub). Built only when some category
     * enables one via `post.badges`; null otherwise so no HTTP client is created.
     */
    badgeResolver: BadgeResolver? = BadgeProviders.resolverFor(config, db, articleFetcher::fetchPageHtml),
    /** Optional first-mention Wikipedia intro; built only when some category enables `post.intro`. */
    introProvider: WikipediaIntroProvider? =
        if (config.categories.values.any { it.post?.intro?.enabled == true }) {
            WikipediaIntroProvider(BadgeHttp(timeoutSeconds = config.badges.timeoutSeconds), DbIntroStore(db))
        } else null,
    /** Optional living trackers; built only when some category configures `trackers`. */
    trackerUpdater: TrackerUpdater? =
        if (config.categories.values.any { it.trackers.isNotEmpty() }) TrackerUpdater(config, db, sender) else null
) {

    /** Cycle-mode scheduler (`scheduler.perCategory=false`): one global cycle, never overlapping. */
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "digest-cycle") }

    /**
     * Per-category mode: one single-thread scheduler per category run type (digest, and ingest
     * when decoupled), so runs of the same kind for a category never overlap — a manual `/run`
     * queues on the same thread.
     */
    private val categoryExecutors = CategoryExecutors()

    /** Per-category mode: retention cleanup, affinity rebuild and the status post, on their own clock. */
    private val maintenanceScheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "maintenance") }

    private val errorLog = CycleErrorLog()

    private val adminCommandsOn = config.admin.commands.enabled

    /**
     * Digest pauses (`/pause`). Only with admin commands on: a pause left in the DB while the
     * commands are off could never be lifted, so it is not honoured then either.
     */
    private val pauseStore: PauseStore? = if (adminCommandsOn) PauseStore(db) else null

    /**
     * The effective mute lists. The DB overlay (`admin_mutes`, written from Telegram) is read only
     * with admin commands on, for the same reason as [pauseStore]; otherwise the YAML lists alone.
     */
    private val muteRegistry = MuteRegistry(config, if (adminCommandsOn) db else null)

    private val statusCommand =
        StatusCommand(config, db, errorLog, sender::rateLimitsLast24h, pauseStore, cliHealthMonitor?.let { m -> m::statusLine })

    private val statusPoster: StatusPoster? = config.admin.statusChatId?.let { chatId ->
        StatusPoster(chatId, sender, statusCommand, errorLog)
    }

    /** Operator alerts (prompt problems, CLI login) to the admin chat; no-op without `admin.statusChatId`. */
    private val adminAlerts = AdminAlerts(config.admin.statusChatId, sender)

    private val promptValidator = PromptValidator(promptLoader)

    /**
     * Admin commands and button presses run here, one at a time, off the poller thread
     * (`admin.commands.enabled`; null — and no routers — otherwise).
     */
    private val adminExecutor: ExecutorService? =
        if (adminCommandsOn) {
            Executors.newSingleThreadExecutor { r -> Thread(r, "admin-commands").also { it.isDaemon = true } }
        } else null

    private val adminAuth = AdminAuth(config.admin)

    private val commandRouter: AdminCommandRouter? = adminExecutor?.let { AdminCommandRouter(adminAuth, sender, it) }

    private val callbackRouter: AdminCallbackRouter? = adminExecutor?.let { AdminCallbackRouter(adminAuth, sender, it) }

    /** Kept for the "/" menu published at [start]. */
    private val telegramSender: TelegramSender = sender

    /**
     * Optional Telegram updates poller (reactions, plus admin commands when enabled). Constructed
     * only when `telegram.updatesPolling=true`, so no `getUpdates` loop runs (and no 409 risk)
     * when the feature is off.
     */
    private val updatesPoller: TelegramUpdatesPoller? =
        if (config.telegram.updatesPolling) {
            TelegramUpdatesPoller(config.telegram.botToken, db, commandRouter, callbackRouter)
        } else null

    /** Weekly mute proposals (`admin.muteSuggestions`); its buttons come back through [callbackRouter]. */
    private val muteSuggester: MuteSuggester? =
        if (config.admin.muteSuggestions.enabled) MuteSuggester(config, db, muteRegistry, sender) else null

    /**
     * Moderation messages (`admin.moderation`) go out on their own thread after each digest, so
     * channel delivery never waits for the admin chat.
     */
    private val moderationExecutor: ExecutorService? =
        if (config.admin.moderation.enabled) {
            Executors.newSingleThreadExecutor { r -> Thread(r, "admin-notify").also { it.isDaemon = true } }
        } else null

    private val moderationNotifier: ModerationNotifier? =
        moderationExecutor?.let { ModerationNotifier(config, db, muteRegistry, sender, it) }

    /** `factCheck`: the log-only judge of sent posts; null when off. */
    private val factChecker: FactChecker? =
        if (config.factCheck.enabled) FactChecker(config.factCheck, db, { llmClientsFactory.forJudge(it) }) else null

    internal val deliverer =
        DigestDeliverer(
            config, db, sender, badgeResolver, introProvider, trackerUpdater, moderationNotifier,
            PostImages.forConfig(config, db, articleFetcher),
            telegraph = if (config.categories.values.any { it.post?.longForm?.takeIf { l -> l.enabled }?.telegraph?.enabled == true }) {
                TelegraphPublisher(config.telegraph, db)
            } else null,
            personaWriter = if (config.categories.values.any { it.post?.persona?.enabled == true }) {
                PersonaWriter({ llmClientsFactory.forPersona(it) })
            } else null,
            factChecker = factChecker
        )

    /** `llm.shadow` B runs; only when some category configures a shadow. */
    private val shadowRunner: ShadowRunner? =
        if (config.categories.values.any { it.llm?.shadow?.enabled == true }) ShadowRunner(db) else null

    private val categoryProcessor = CategoryProcessor(
        config = config,
        db = db,
        llmClientsFactory = llmClientsFactory,
        promptLoader = promptLoader,
        deliverer = deliverer,
        eventExtractor = eventExtractor,
        errorLog = errorLog,
        eventSemanticAnalyzer = eventSemanticAnalyzer,
        longFormTextFetcher = LongForm.TextFetcher(articleFetcher::fetchFullText),
        muteRegistry = muteRegistry,
        shadowRunner = shadowRunner,
        updateDiff = if (config.categories.values.any { it.dedup?.digest?.updateDiff?.enabled == true }) {
            UpdateDiff(db, { llmClientsFactory.forUpdateDiff(it) })
        } else null
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
        affinityAggregator = affinityAggregator,
        retentionArchiver = config.archive?.takeIf { it.enabled }?.let { RetentionArchiver.create(it, db) },
        pauseStore = pauseStore,
        muteSuggester = muteSuggester,
        cliHealthMonitor = cliHealthMonitor,
        // Only read with the feature on: tests hand in strict fetcher mocks.
        feedHealthRecorder = if (config.feedHealth.enabled) fetcher.healthRecorder else null,
        feedCandidateFinder = if (config.feedHealth.candidates.enabled) {
            FeedCandidateFinder(config, db, FeedDiscovery(SHARED_HOST_THROTTLE))
        } else null
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

    init {
        commandRouter?.let { router ->
            AdminCommands(
                config, db, muteRegistry, pauseStore!!, statusCommand, sender,
                runner = { name, withFetch, listener -> enqueueManualRun(name, withFetch, listener) }
            ).registerAll(router)
        }
        muteSuggester?.let { s -> callbackRouter?.register("ms") { cb, parts -> s.handleCallback(cb, parts) } }
        moderationNotifier?.let { m -> callbackRouter?.register("mod") { cb, parts -> m.handleCallback(cb, parts) } }
        // Post inspector (`admin.inspect`, on with the commands): `/inspect`, pasted links, forwards.
        if (config.admin.inspect.enabled) {
            commandRouter?.let { router ->
                val inspector = PostInspector(config, db, muteRegistry, sender)
                inspector.registerAll(router)
                callbackRouter?.register(PostInspector.NAMESPACE) { cb, parts -> inspector.handleCallback(cb, parts) }
            }
        }
    }

    fun start() {
        logger.info { "RssNewsBot started." }
        checkPrompts()
        // Hot reload: a prompt file edited on the server is re-read on its next use; re-check it
        // then. Never fatal (the bot is running) — ERRORs go to the log, /status and the admin chat.
        promptLoader.onReload = { file -> revalidatePrompts(file) }
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
        cliHealthMonitor?.let { m ->
            val probe = config.health.cliProbe
            logger.info {
                "[CliHealth] tracking ${m.providers.ifEmpty { listOf("no CLI configured") }}; " +
                    (if (probe.enabled) "idle probe every ${probe.intervalHours}h" else "probe off") +
                    (config.health.claudeTokenExpires?.let { "; setup-token expires $it" } ?: "")
            }
        }

        // Graceful shutdown: stop every scheduler and wait up to 30s in total for in-flight
        // runs; whatever is still running is interrupted (pipelines revert their PROCESSING
        // rows on interrupt, and the startup reclaim covers a worker killed mid-flight).
        Runtime.getRuntime().addShutdownHook(Thread({
            logger.info { "[Shutdown] Stopping schedulers..." }
            val executors = listOf(scheduler, maintenanceScheduler) + categoryExecutors.all()
            executors.forEach { it.shutdown() }
            weeklyScheduler?.shutdown()
            updatesPoller?.stop()
            adminExecutor?.shutdownNow()
            moderationExecutor?.shutdownNow()
            shadowRunner?.shutdown()
            factChecker?.shutdown()
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
        publishCommandMenu()
        if (!adminCommandsOn) warnIgnoredAdminMutes()

        digestCycle.reclaimOrphanedProcessing()
        // storyIntel: the first start with an empty event_history warms it up from covered_events.
        EventHistoryRecorder(config, db).backfillIfEmpty()
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

    /**
     * Startup prompt validation ([PromptValidator]): any ERROR refuses to start, listing every
     * finding — under systemd `Restart=always` that loops, but the cause is right there in the
     * journal, which beats weeks of a category quietly posting in the legacy format. WARNs go
     * to the log and, as one message, to the admin chat.
     */
    internal fun checkPrompts() {
        val findings = promptValidator.validate(config)
        if (findings.isEmpty()) {
            logger.info { "[Prompts] all prompts validated." }
            return
        }
        val errors = findings.filter { it.level == PromptFinding.Level.ERROR }
        if (errors.isNotEmpty()) {
            val report = PromptValidator.format(findings)
            logger.error { "[Prompts] refusing to start — ${errors.size} error(s):\n$report" }
            throw IllegalStateException("Prompt validation failed (${errors.size} error(s)):\n$report")
        }
        logFindings(findings)
        val warns = findings.filter { it.level == PromptFinding.Level.WARN }
        if (warns.isNotEmpty()) adminAlerts.send("⚠️ Промпти:\n" + warns.joinToString("\n") { "• ${it.category}/${it.slot}: ${it.message}" })
    }

    private fun revalidatePrompts(file: java.io.File) {
        val findings = promptValidator.categoriesUsing(file, config).flatMap { name ->
            promptValidator.validateCategory(name, config.categories.getValue(name), config)
        }
        logFindings(findings)
        val errors = findings.filter { it.level == PromptFinding.Level.ERROR }
        for (e in errors) errorLog.recordError(e.category, "[Prompts]", IllegalStateException("${e.slot}: ${e.message}"))
        if (errors.isNotEmpty()) {
            adminAlerts.send("⛔ Промпти після правки ${file.name}:\n" + errors.joinToString("\n") { "• ${it.category}/${it.slot}: ${it.message}" })
        }
    }

    private fun logFindings(findings: List<PromptFinding>) {
        for (f in findings) when (f.level) {
            PromptFinding.Level.ERROR -> logger.error { "[Prompts] $f" }
            PromptFinding.Level.WARN -> logger.warn { "[Prompts] $f" }
            PromptFinding.Level.INFO -> logger.info { "[Prompts] $f" }
        }
    }

    /**
     * Admin commands on: publishes the "/" menu to the admin chat and to each admin's private
     * chat only (`BotCommandScopeChat`), on the admin executor so a slow Bot API never delays
     * the start. Best-effort — the commands work without the menu.
     */
    private fun publishCommandMenu() {
        val router = commandRouter ?: return
        val executor = adminExecutor ?: return
        val chats = (listOfNotNull(config.admin.statusChatId?.trim()) + config.admin.userIds.map { it.toString() }).distinct()
        executor.execute {
            val menu = router.menu()
            val failed = chats.filterNot { telegramSender.setMyCommands(menu, it) }
            logger.info {
                "[Admin] commands enabled for users ${config.admin.userIds}; menu (${menu.size} commands) set in " +
                    "${chats.size - failed.size}/${chats.size} chat(s)" + if (failed.isEmpty()) "" else ", failed: $failed"
            }
        }
    }

    /** Mutes added from Telegram stay in the DB when the commands are switched off — say they are inert. */
    private fun warnIgnoredAdminMutes() {
        val n = try {
            db.fetchAdminMutes().size
        } catch (e: Exception) {
            0
        }
        if (n > 0) logger.warn { "[Admin] $n mute(s) added from Telegram (admin_mutes) are ignored while admin.commands is off" }
    }

    fun runDigestCycle() = digestCycle.runCycle()

    /** Per-category mode: one run of [name]'s pipeline (fetch → digest), for tests and manual triggers. */
    fun runCategory(name: String) = digestCycle.runCategory(name)

    /**
     * Default mode. Every category gets its own single-thread scheduler at its own interval
     * (`categories.<name>.intervalMinutes`, else `scheduler.intervalMinutes`), first run
     * immediately; housekeeping and the status post tick on the shared interval. No category
     * ever waits for another, and a run that outlasts its interval only delays its own next run.
     *
     * With an ingest cadence configured (`categories.<name>.fetchIntervalMinutes`, else
     * `fetcher.intervalMinutes`) the category is split in two timers: ingestion (fetch → enrich →
     * summarize → insert) on the fetch cadence and the digest (Step 1 + Step 2) on the digest
     * cadence, so a burst of articles is prepared as it arrives and the digest run stays short.
     */
    private fun startPerCategorySchedulers() {
        val defaultInterval = config.scheduler.intervalMinutes
        // Cleanup + affinity + first status snapshot before the first pipelines start, as a
        // global cycle used to do at its start.
        guarded("[Maintenance]") { digestCycle.runMaintenance() }
        val cadence = config.categories.map { (name, cat) ->
            val interval = cat.intervalMinutes ?: defaultInterval
            val fetchInterval = cat.fetchIntervalMinutes ?: config.fetcher.intervalMinutes
            if (fetchInterval == null) {
                schedule("digest-$name", interval) { digestCycle.runCategory(name) }
                "$name every ${interval}m"
            } else {
                schedule("ingest-$name", fetchInterval) { digestCycle.runIngest(name) }
                schedule("digest-$name", interval) { digestCycle.runDigest(name) }
                "$name ingest every ${fetchInterval}m + digest every ${interval}m"
            }
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

    /** One named single-thread scheduler running [block] now and then every [intervalMinutes]. */
    private fun schedule(threadName: String, intervalMinutes: Long, block: () -> Unit) {
        categoryExecutors.schedule(threadName, intervalMinutes) { guarded("[$threadName]") { block() } }
    }

    /**
     * `/run`: queues a forced run of [name] on its own scheduler thread — behind a run in
     * progress, never beside it — ignoring a `/pause`. Coupled mode runs the whole pipeline;
     * decoupled mode runs the digest, after an ingest on the ingest thread when [withFetch].
     */
    internal fun enqueueManualRun(name: String, withFetch: Boolean, listener: RunListener): RunQueued {
        if (!config.scheduler.perCategory) return RunQueued.NOT_PER_CATEGORY
        val digestThread = "digest-$name"
        val ingestThread = "ingest-$name"
        val decoupled = categoryExecutors.has(ingestThread)
        // The whole manual run (ingest + digest) logs under the runId the /run reply showed.
        val runId = listener.runId ?: RunContext.newRunId(name, "cmd")
        val digestTask = Runnable {
            RunContext.withRun(runId, name, "cmd") {
                guarded("[$digestThread] manual") {
                    listener.started()
                    try {
                        if (decoupled) digestCycle.runDigest(name, force = true) else digestCycle.runCategory(name, force = true)
                    } finally {
                        listener.finished()
                    }
                }
            }
        }
        val queued = if (decoupled && withFetch) {
            categoryExecutors.enqueue(ingestThread) {
                RunContext.withRun(runId, name, "cmd") { guarded("[$ingestThread] manual") { digestCycle.runIngest(name) } }
                if (!categoryExecutors.enqueue(digestThread, digestTask)) logger.warn { "[Admin] /run $name: digest thread gone" }
            }
        } else {
            categoryExecutors.enqueue(digestThread, digestTask)
        }
        return if (queued) RunQueued.QUEUED else RunQueued.NOT_STARTED
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
