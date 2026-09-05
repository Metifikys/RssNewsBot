package metifikys.ai.dedup

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import metifikys.ai.BillingException
import metifikys.ai.LlmClient
import metifikys.config.CategoryConfig
import metifikys.config.DigestConfig
import metifikys.db.NewsDatabase
import metifikys.db.CoveredEventRow
import metifikys.db.RejectedEventRow
import metifikys.feedback.AffinityImpact
import metifikys.feedback.AffinityScores
import metifikys.model.Article
import metifikys.model.CoveredEvent
import metifikys.model.ExtractionResult
import metifikys.model.ShortlistItem
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Return type for [EventExtractor.extract].
 *
 * - [Ready]          — sync extraction succeeded; caller proceeds to Step 2 inline.
 * - [FallbackToLegacy] — extraction unresolvable or failed; caller should run the
 *                      legacy single-step flow for this category.
 * - [PendingBatch]   — batch was submitted; [future] resolves to raw JSON when the
 *                      batch completes. Caller attaches Step 2 callback to [future].
 */
sealed class ExtractOutcome {
    data class Ready(val result: ExtractionResult) : ExtractOutcome()
    object FallbackToLegacy : ExtractOutcome()
    data class PendingBatch(val future: CompletableFuture<String>) : ExtractOutcome()
}

/**
 * Step 1 of the two-step dedup pipeline.
 *
 * Calls an OpenAI-compatible chat.completions endpoint in JSON mode with:
 * - `{{CURRENT_BATCH_JSON}}` — serialized input articles (0-based indexing in the prompt).
 * - `{{PREVIOUSLY_COVERED_EVENTS_JSON}}` — structured events already covered in prior cycles.
 *
 * When [alternateOpenAI] is configured, every second request is sent there so Step 1 can
 * A/B test OpenAI against an alternate provider such as OpenRouter.
 *
 * Returns an [ExtractionResult] with a shortlist of events to be rendered by Step 2.
 * Returns null (without throwing) for any parse/network failure — the caller should
 * fall back to the legacy single-step flow for that category. [BillingException] is
 * propagated so the caller can short-circuit the cycle.
 */
class EventExtractor(
    private val openAI: LlmClient,
    private val promptLoader: PromptLoader,
    private val db: NewsDatabase,
    private val json: Json = defaultJson,
    private val alternateOpenAI: LlmClient? = null,
    private val requestCounter: AtomicLong = AtomicLong(0)
) {

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    @Serializable
    private data class PromptArticle(
        val index: Int,            // 0-based
        val title: String,
        val url: String,
        val description: String,
        val pubDate: String
    )

    private data class SelectedClient(
        val client: LlmClient,
        val providerLabel: String,
        val requestNumber: Long
    )

    private data class CooldownDrop(
        val item: ShortlistItem,
        val previous: CoveredEventRow,
        val minutesSince: Long
    )

    /**
     * @return [ExtractOutcome.Ready] on sync success; [ExtractOutcome.FallbackToLegacy] when
     *         dedup is unresolvable or the LLM call failed; [ExtractOutcome.PendingBatch] when
     *         the selected client is batch-capable (future resolves to raw JSON).
     */
    fun extract(
        category: String,
        cat: CategoryConfig,
        articles: List<Article>
    ): ExtractOutcome {
        val prompts = promptLoader.resolve(cat) ?: return ExtractOutcome.FallbackToLegacy

        val coveredRows = db.fetchRecentEvents(category, prompts.contextDays, prompts.maxContextEvents)
        val covered = coveredRows
            .map {
                CoveredEvent(
                    eventKey = it.eventKey,
                    subject = it.subject,
                    franchise = it.franchise,
                    eventType = it.eventType,
                    coreFact = it.coreFact,
                    importance = it.importance,
                    newsworthiness = it.newsworthiness,
                    digestFit = it.digestFit,
                    url = it.url,
                    coveredAt = it.coveredAt.toString()
                )
            }

        val promptArticles = articles.mapIndexed { i, article ->
            PromptArticle(
                index = i,                               // 0-based in the prompt and schema
                title = article.title,
                url = article.link,
                description = article.promptText(),
                pubDate = article.pubDate.toString()
            )
        }

        val batchJson = json.encodeToString(promptArticles)
        val coveredJson = json.encodeToString(covered)

        // Reaction-feedback lever A, prompt channel: resolves only when the template actually
        // contains {{AUDIENCE_SIGNALS}} (opt-in per prompt file); "" otherwise / on any failure.
        val audienceSignals = if (prompts.extractSystem.contains("{{AUDIENCE_SIGNALS}}") ||
            prompts.extractUser.contains("{{AUDIENCE_SIGNALS}}")
        ) {
            try {
                affinityScoresFor(category).buildAudienceSignals().also {
                    if (it.isNotEmpty()) logger.info {
                        "[Category:$category][Dedup] AUDIENCE_SIGNALS block injected into the Step 1 prompt " +
                            "(${it.lines().size} line(s))"
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "[Category:$category][Dedup] AUDIENCE_SIGNALS build failed — injecting empty block" }
                ""
            }
        } else ""

        val vars = mapOf(
            "CURRENT_BATCH_JSON" to batchJson,
            "PREVIOUSLY_COVERED_EVENTS_JSON" to coveredJson,
            "CATEGORY" to category,
            "EMOJI" to cat.emoji,
            "AUDIENCE_SIGNALS" to audienceSignals
        )
        val userPrompt = promptLoader.substitute(prompts.extractUser, vars)
        val systemPrompt = promptLoader.substitute(prompts.extractSystem, vars)
        val selectedClient = selectClient()

        logger.info {
            "[Category:$category][Dedup] Step 1 request#${selectedClient.requestNumber} " +
                "via ${selectedClient.providerLabel}: articles=${articles.size}, " +
                "coveredContext=${covered.size}, promptBytes=${userPrompt.length}"
        }

        // Batch path: submit asynchronously, return PendingBatch for the caller to handle
        if (selectedClient.client.isBatchCapable) {
            val articleLinksStr = articles.map { it.link }.joinToString("\n")
            val future = selectedClient.client.submitExtractBatch(
                category = category,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                articleLinks = articleLinksStr
            )
            return ExtractOutcome.PendingBatch(future)
        }

        // Sync path
        val raw = try {
            selectedClient.client.completeJson(systemPrompt = systemPrompt, userPrompt = userPrompt, maxRetry = 3)
        } catch (e: BillingException) {
            throw e
        } catch (e: InterruptedException) {
            // Cancellation (cycle deadline / shutdown) — abort the category; the legacy
            // fallback would only spawn more doomed calls on an interrupted worker.
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            logger.warn(e) {
                "[Category:$category][Dedup] Step 1 LLM call failed via ${selectedClient.providerLabel} - falling back to legacy"
            }
            return ExtractOutcome.FallbackToLegacy
        }

        if (raw.isBlank()) {
            logger.warn {
                "[Category:$category][Dedup] Step 1 returned blank content via ${selectedClient.providerLabel} - falling back to legacy"
            }
            return ExtractOutcome.FallbackToLegacy
        }

        return postProcess(category, cat, articles, raw, coveredRows)
            ?.let { ExtractOutcome.Ready(it) }
            ?: ExtractOutcome.FallbackToLegacy
    }

    /**
     * Processes raw JSON from a completed extract batch, applying the same
     * URL filter, cooldown, and ranker passes as the inline sync path.
     * Returns null on parse failure (caller should mark articles UNPROCESSED).
     */
    fun resumeFromBatch(
        category: String,
        cat: CategoryConfig,
        articles: List<Article>,
        rawJson: String
    ): ExtractionResult? = postProcess(category, cat, articles, rawJson, coveredRows = null)

    private fun postProcess(
        category: String,
        cat: CategoryConfig,
        articles: List<Article>,
        raw: String,
        coveredRows: List<CoveredEventRow>?
    ): ExtractionResult? {
        val parsed = try {
            json.decodeFromString<ExtractionResult>(raw)
        } catch (e: Exception) {
            logger.warn(e) {
                "[Category:$category][Dedup] Step 1 JSON parse failed. Raw (truncated): ${raw.take(500)}"
            }
            return null
        }

        val now = LocalDateTime.now()
        val rejectedItems = parsed.extractions.filter { it.status == "rejected" || it.status == "duplicate" }
        if (rejectedItems.isNotEmpty()) {
            db.saveRejectedEvents(rejectedItems.map { item ->
                RejectedEventRow(
                    category = category,
                    eventKey = item.eventKey,
                    subject = item.subject,
                    franchise = item.franchise,
                    eventType = item.eventType,
                    coreFact = item.coreFact,
                    importance = item.importance,
                    newsworthiness = item.newsworthiness,
                    digestFit = item.digestFit,
                    url = item.url,
                    status = item.status,
                    relatedPreviousEventKey = item.relatedPreviousEventKey,
                    articleIndex = item.articleIndex,
                    extractedAt = now
                )
            })
            logger.debug { "[Category:$category][Dedup] saved ${rejectedItems.size} rejected/duplicate event(s) for prompt tuning" }
        }

        val allowedUrls = articles.map { it.link }.toSet()
        val urlFilteredShortlist = parsed.shortlist.filter { item ->
            val ok = item.url in allowedUrls
            if (!ok) {
                logger.warn {
                    "[Category:$category][Dedup] Dropping shortlist item with hallucinated URL '${item.url}' (event_key=${item.eventKey})"
                }
            }
            ok
        }

        val effectiveCoveredRows = coveredRows
            ?: db.fetchRecentEvents(category, 7, 200)  // fallback for batch-resume path

        val digestConfig = cat.dedup?.digest
        val cooldownFilteredShortlist = if (digestConfig != null) {
            val (kept, droppedByCooldown) = applyMeaningfulUpdateCooldown(
                category = category,
                shortlist = urlFilteredShortlist,
                coveredRows = effectiveCoveredRows,
                config = digestConfig
            )
            if (droppedByCooldown.isNotEmpty()) {
                logger.info {
                    "[Category:$category][Dedup] meaningful_update cooldown dropped=${droppedByCooldown.size}: " +
                        droppedByCooldown.joinToString { drop ->
                            "${drop.item.eventKey}<-${drop.previous.eventKey}@${drop.minutesSince}m"
                        }
                }
            }
            kept
        } else {
            urlFilteredShortlist
        }

        val finalShortlist = if (digestConfig != null && digestConfig.ranker.enabled) {
            val ranked = ShortlistRanker.rank(cooldownFilteredShortlist, digestConfig)
            if (ranked.dropped.isNotEmpty()) {
                val byReason = ranked.dropped.groupingBy { it.reason.substringBefore(" ") }.eachCount()
                logger.info {
                    "[Category:$category][Ranker] kept=${ranked.kept.size}, dropped=${ranked.dropped.size}, " +
                        "franchiseConcentration=${"%.2f".format(ranked.franchiseConcentration())}, " +
                        "dropReasons=$byReason"
                }
                for (d in ranked.dropped) {
                    logger.debug { "[Category:$category][Ranker] drop ${d.item.eventKey}: ${d.reason}" }
                }
            } else {
                logger.info {
                    "[Category:$category][Ranker] kept=${ranked.kept.size} (no drops), " +
                        "franchiseConcentration=${"%.2f".format(ranked.franchiseConcentration())}"
                }
            }
            applyAffinity(category, cooldownFilteredShortlist, ranked, digestConfig)
        } else {
            cooldownFilteredShortlist
        }

        val statuses = parsed.extractions.groupingBy { it.status }.eachCount()
        logger.info {
            "[Category:$category][Dedup] Step 1: extracted=${parsed.extractions.size}, " +
                "shortlisted=${finalShortlist.size}, byStatus=$statuses"
        }

        return ExtractionResult(extractions = parsed.extractions, shortlist = finalShortlist)
    }

    /**
     * Reaction-feedback lever A (phase 2). When `ranker.reactionWeight > 0`, re-ranks the
     * shortlist with the audience-affinity term and logs EXACTLY what the term changed:
     *
     *   [AffinityRank][LOG-ONLY] — would-be changes; the published digest keeps [baseline]
     *   [AffinityRank][APPLIED]  — changes that ARE in the published digest
     *   "no effect"              — the term ran but moved nothing this cycle
     *
     * Per-item contributions land in the same line so the operator can see WHY. Any failure
     * (e.g. affinity fetch) logs a warning and returns the baseline — feedback must never
     * break a digest.
     */
    private fun applyAffinity(
        category: String,
        shortlist: List<ShortlistItem>,
        baseline: ShortlistRanker.Result,
        digestConfig: DigestConfig
    ): List<ShortlistItem> {
        val ranker = digestConfig.ranker
        if (ranker.reactionWeight <= 0.0) return baseline.kept
        return try {
            val scores = affinityScoresFor(category)
            if (scores.isEmpty()) {
                logger.info { "[Category:$category][AffinityRank] no affinity data yet — term inactive" }
                return baseline.kept
            }
            val scorer: (ShortlistItem) -> Double = { scores.itemScore(it) }
            val reRanked = ShortlistRanker.rank(shortlist, digestConfig, scorer)
            val mode = if (ranker.reactionLogOnly) "LOG-ONLY" else "APPLIED"
            val impact = AffinityImpact.describe(baseline.kept, reRanked.kept)
            if (impact == null) {
                logger.info {
                    "[Category:$category][AffinityRank][$mode] no effect on selection this cycle " +
                        "(weight=${ranker.reactionWeight}, kept=${baseline.kept.size})"
                }
            } else {
                val verb = if (ranker.reactionLogOnly) "WOULD change selection" else "changed selection"
                val contributions = reRanked.kept.union(baseline.kept)
                    .map { it to ShortlistRanker.affinityContribution(it, digestConfig, scorer) }
                    .filter { kotlin.math.abs(it.second) >= 0.05 }
                    .sortedByDescending { it.second }
                    .joinToString(", ") { (item, c) -> "'${item.eventKey}'=${"%+.2f".format(c)}pts" }
                logger.info {
                    "[Category:$category][AffinityRank][$mode] $verb: $impact " +
                        "(weight=${ranker.reactionWeight}; contributions: $contributions)"
                }
            }
            if (ranker.reactionLogOnly) baseline.kept else reRanked.kept
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$category][AffinityRank] failed — using baseline ranking" }
            baseline.kept
        }
    }

    /** One category's affinity rows as a scorer; separate fun so tests can seed the table. */
    private fun affinityScoresFor(category: String): AffinityScores =
        AffinityScores.of(db.fetchAudienceAffinity().filter { it.category == category })

    private fun selectClient(): SelectedClient {
        val requestNumber = requestCounter.incrementAndGet()
        val alternate = alternateOpenAI
        return if (alternate != null && requestNumber % 2L == 0L) {
            SelectedClient(
                client = alternate,
                providerLabel = "alternate-openrouter",
                requestNumber = requestNumber
            )
        } else {
            SelectedClient(
                client = openAI,
                providerLabel = "primary-openai",
                requestNumber = requestNumber
            )
        }
    }

    /**
     * Drops `meaningful_update` items whose related event was covered less than
     * `meaningfulUpdateCooldownMinutes` ago, unless the item clears `newsworthinessOverride`.
     *
     * The previous event is looked up in the prompt context first ([coveredRows]) and then, for
     * keys missing there, directly in `covered_events` by key. The context list is capped by
     * `maxContextEvents` (≈1–2 days of a busy category), which would otherwise silently cap a
     * multi-day cooldown at the context depth; the keyed lookup costs no prompt tokens.
     */
    private fun applyMeaningfulUpdateCooldown(
        category: String,
        shortlist: List<ShortlistItem>,
        coveredRows: List<CoveredEventRow>,
        config: DigestConfig,
        now: LocalDateTime = LocalDateTime.now()
    ): Pair<List<ShortlistItem>, List<CooldownDrop>> {
        if (config.meaningfulUpdateCooldownMinutes <= 0) return shortlist to emptyList()

        val coveredByKey = coveredRows.associateBy { it.eventKey }.toMutableMap()
        val missingKeys = shortlist
            .filter { it.status == "meaningful_update" }
            .map { it.relatedPreviousEventKey ?: it.eventKey }
            .filter { it !in coveredByKey }
            .toSet()
        if (missingKeys.isNotEmpty()) {
            val since = now.minusMinutes(config.meaningfulUpdateCooldownMinutes)
            try {
                db.fetchCoveredEventsByKeys(category, missingKeys, since).forEach { coveredByKey.putIfAbsent(it.eventKey, it) }
            } catch (e: Exception) {
                logger.warn(e) {
                    "[Category:$category][Dedup] cooldown lookup of ${missingKeys.size} key(s) outside the prompt context failed — " +
                        "checking against the context only"
                }
            }
        }

        val dropped = mutableListOf<CooldownDrop>()
        val kept = shortlist.filter { item ->
            if (item.status != "meaningful_update") return@filter true

            val relatedKey = item.relatedPreviousEventKey ?: item.eventKey
            val previous = coveredByKey[relatedKey] ?: return@filter true
            val minutesSince = Duration.between(previous.coveredAt, now).toMinutes().coerceAtLeast(0)
            val withinCooldown = minutesSince < config.meaningfulUpdateCooldownMinutes
            val strongEnough = effectiveNewsworthiness(item) >= config.newsworthinessOverride

            val keep = !withinCooldown || strongEnough
            if (!keep) dropped += CooldownDrop(item, previous, minutesSince)
            keep
        }
        return kept to dropped
    }

    private fun effectiveNewsworthiness(item: ShortlistItem): Int =
        if (item.newsworthiness > 0) item.newsworthiness else item.importance
}
