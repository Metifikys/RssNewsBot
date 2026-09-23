package metifikys.ai.dedup

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import metifikys.ai.BillingException
import metifikys.ai.LlmClient
import metifikys.config.CategoryConfig
import metifikys.config.DigestConfig
import metifikys.config.StoryIntelConfig
import metifikys.db.NewsDatabase
import metifikys.db.CoveredEventRow
import metifikys.db.EventArticleRole
import metifikys.db.EventArticleRow
import metifikys.db.RejectedEventRow
import metifikys.digest.TrackerDates
import metifikys.feedback.AffinityImpact
import metifikys.feedback.AffinityMath
import metifikys.feedback.AffinityScores
import metifikys.story.EventMembership
import metifikys.model.Article
import metifikys.model.CoveredEvent
import metifikys.model.ExtractionResult
import metifikys.model.ShortlistItem
import metifikys.model.effectiveNewsworthiness
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
 * A shortlist item Step 1 post-processing removed, and why ([reason] is one of the constants; the
 * ranker's carries its own detail). Keyed by [url]: event keys are the model's and unstable.
 */
data class Step1Drop(val url: String, val eventKey: String, val reason: String) {
    companion object {
        const val HALLUCINATED_URL = "hallucinated_url"
        const val COOLDOWN = "cooldown"
        const val MAX_POSTS_PER_EVENT = "max_posts_per_event"
        const val RANKER = "ranker"
        const val AFFINITY = "affinity"
    }
}

/** [EventExtractor.evaluate]'s output: the result the digest would use, plus what was dropped. */
data class Step1Evaluation(
    val result: ExtractionResult,
    val drops: List<Step1Drop>,
    /** Article → event membership, for the `event_articles` rows (`storyIntel`). */
    val members: List<EventMembership.Member>,
    val evaluatedAt: LocalDateTime
)

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
    private val requestCounter: AtomicLong = AtomicLong(0),
    /** With `enabled`, every Step 1 result is also recorded as story clusters (`event_articles`). */
    private val storyIntel: StoryIntelConfig = StoryIntelConfig(),
    /** "Now" of the post-processing (cooldown, posts cap, anti-bubble, row stamps); golden tests pin it. */
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    companion object {
        /** Placeholders substituted in both extract prompts (system and user) — see [extract]. */
        val PLACEHOLDERS: Set<String> =
            setOf("CURRENT_BATCH_JSON", "PREVIOUSLY_COVERED_EVENTS_JSON", "CATEGORY", "EMOJI", "AUDIENCE_SIGNALS")

        private val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    private data class PromptArticle(
        val index: Int,            // 0-based
        val title: String,
        val url: String,
        val description: String,
        val pubDate: String,
        /**
         * Converted-feed hint (`official account: Some Studio`, from `feeds[].kind` + `label`), so
         * the model can tell a studio's own statement from a retelling. Absent from the JSON for
         * every other article — the prompt of a config without `kind` is byte-for-byte unchanged.
         */
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val source: String? = null
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
                pubDate = article.pubDate.toString(),
                source = article.meta()?.source?.promptHint()
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
        val evaluation = evaluate(category, cat, articles, raw, coveredRows) ?: return null
        saveRejected(category, evaluation)
        if (storyIntel.enabled) recordEventArticles(category, evaluation.members, evaluation.evaluatedAt)
        return evaluation.result
    }

    /**
     * Step 1 post-processing of [raw] without any writes: parse, URL filter, `eventDate` guard,
     * cooldown, posts-per-event cap, ranker — and, with [withAudience], the affinity term and the
     * anti-bubble pick (both only read the DB). Null when [raw] does not parse. The sync and the
     * batch path go through [postProcess], which adds the writes (rejected events, story
     * clusters); golden tests and shadow runs call this directly, so another model's output is
     * judged by exactly the same code without touching the tables. [tag] prefixes the log lines.
     */
    fun evaluate(
        category: String,
        cat: CategoryConfig,
        articles: List<Article>,
        raw: String,
        coveredRows: List<CoveredEventRow>?,
        withAudience: Boolean = true,
        tag: String = "[Category:$category]"
    ): Step1Evaluation? {
        val parsed = try {
            json.decodeFromString<ExtractionResult>(raw)
        } catch (e: Exception) {
            logger.warn(e) {
                "$tag[Dedup] Step 1 JSON parse failed. Raw (truncated): ${raw.take(500)}"
            }
            return null
        }

        val now = clock()
        val drops = mutableListOf<Step1Drop>()
        var members: List<EventMembership.Member> = emptyList()

        val allowedUrls = articles.map { it.link }.toSet()
        val urlFilteredShortlist = parsed.shortlist.filter { item ->
            val ok = item.url in allowedUrls
            if (!ok) {
                logger.warn {
                    "$tag[Dedup] Dropping shortlist item with hallucinated URL '${item.url}' (event_key=${item.eventKey})"
                }
                drops += Step1Drop(item.url, item.eventKey, Step1Drop.HALLUCINATED_URL)
            }
            ok
        }.map { item -> guardEventDate(tag, item, articles) }
            // `lane` is code's field (the anti-bubble pick below), never the model's.
            .map { item -> if (item.lane == null) item else item.copy(lane = null) }
            .let { list ->
                // Article → event, resolved once while `articles` is still the prompt's list: the
                // shortlist carries its sources to delivery, and (storyIntel) the clusters are stored.
                members = EventMembership.resolve(parsed.extractions, list, articles)
                list.map { it.copy(sourceLinks = EventMembership.sourceLinks(it, members, articles)) }
            }

        val effectiveCoveredRows = coveredRows
            ?: db.fetchRecentEvents(category, 7, 200)  // fallback for batch-resume path

        val digestConfig = cat.dedup?.digest
        val cooldownFilteredShortlist = if (digestConfig != null) {
            val (kept, droppedByCooldown) = applyMeaningfulUpdateCooldown(
                category = category,
                shortlist = urlFilteredShortlist,
                coveredRows = effectiveCoveredRows,
                config = digestConfig,
                now = now,
                tag = tag
            )
            if (droppedByCooldown.isNotEmpty()) {
                logger.info {
                    "$tag[Dedup] meaningful_update cooldown dropped=${droppedByCooldown.size}: " +
                        droppedByCooldown.joinToString { drop ->
                            "${drop.item.eventKey}<-${drop.previous.eventKey}@${drop.minutesSince}m"
                        }
                }
                droppedByCooldown.forEach { drops += Step1Drop(it.item.url, it.item.eventKey, Step1Drop.COOLDOWN) }
            }
            kept
        } else {
            urlFilteredShortlist
        }

        val cappedShortlist = if (digestConfig?.maxPostsPerEvent != null) {
            applyPostsPerEventCap(category, cooldownFilteredShortlist, digestConfig, now, tag).also { kept ->
                cooldownFilteredShortlist.filter { it !in kept }
                    .forEach { drops += Step1Drop(it.url, it.eventKey, Step1Drop.MAX_POSTS_PER_EVENT) }
            }
        } else {
            cooldownFilteredShortlist
        }

        val finalShortlist = if (digestConfig != null && digestConfig.ranker.enabled) {
            val ranked = ShortlistRanker.rank(cappedShortlist, digestConfig)
            if (ranked.dropped.isNotEmpty()) {
                val byReason = ranked.dropped.groupingBy { it.reason.substringBefore(" ") }.eachCount()
                logger.info {
                    "$tag[Ranker] kept=${ranked.kept.size}, dropped=${ranked.dropped.size}, " +
                        "franchiseConcentration=${"%.2f".format(ranked.franchiseConcentration())}, " +
                        "dropReasons=$byReason"
                }
                for (d in ranked.dropped) {
                    logger.debug { "$tag[Ranker] drop ${d.item.eventKey}: ${d.reason}" }
                }
            } else {
                logger.info {
                    "$tag[Ranker] kept=${ranked.kept.size} (no drops), " +
                        "franchiseConcentration=${"%.2f".format(ranked.franchiseConcentration())}"
                }
            }
            val final = if (withAudience) {
                val kept = applyAffinity(category, cappedShortlist, ranked, digestConfig)
                applyAntiBubble(category, cappedShortlist, kept, ranked, digestConfig, now)
            } else {
                ranked.kept
            }
            val finalIds = final.map { it.eventKey to it.url }.toSet()
            val rankerReasons = ranked.dropped.associate { (it.item.eventKey to it.item.url) to it.reason }
            for (item in cappedShortlist) {
                val id = item.eventKey to item.url
                if (id in finalIds) continue
                val reason = rankerReasons[id]?.let { "${Step1Drop.RANKER}: $it" } ?: Step1Drop.AFFINITY
                drops += Step1Drop(item.url, item.eventKey, reason)
            }
            final
        } else {
            cappedShortlist
        }

        val statuses = parsed.extractions.groupingBy { it.status }.eachCount()
        logger.info {
            "$tag[Dedup] Step 1: extracted=${parsed.extractions.size}, " +
                "shortlisted=${finalShortlist.size}, byStatus=$statuses"
        }

        return Step1Evaluation(
            result = ExtractionResult(extractions = parsed.extractions, shortlist = finalShortlist),
            drops = drops,
            members = members,
            evaluatedAt = now
        )
    }

    /** The model's `rejected` / `duplicate` decisions, kept for prompt tuning (`rejected_events`). */
    private fun saveRejected(category: String, evaluation: Step1Evaluation) {
        val rejectedItems = evaluation.result.extractions.filter { it.status == "rejected" || it.status == "duplicate" }
        if (rejectedItems.isEmpty()) return
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
                extractedAt = evaluation.evaluatedAt
            )
        })
        logger.debug { "[Category:$category][Dedup] saved ${rejectedItems.size} rejected/duplicate event(s) for prompt tuning" }
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

    /**
     * `digest.antiBubble`: when the category's interval has passed (`bot_state`, written by the
     * delivery only once the post went out), appends the [AntiBubble] pick from the events the
     * ranker left out — past `maxDigestItems`, with `lane = explore`. [baseline] only names the
     * drop reason in the log. No affinity rows, no pick, or any failure → [kept] unchanged.
     */
    private fun applyAntiBubble(
        category: String,
        pool: List<ShortlistItem>,
        kept: List<ShortlistItem>,
        baseline: ShortlistRanker.Result,
        digestConfig: DigestConfig,
        now: LocalDateTime
    ): List<ShortlistItem> {
        val cfg = digestConfig.antiBubble?.takeIf { it.enabled } ?: return kept
        if (kept.isEmpty() || pool.size <= kept.size) return kept
        return try {
            val last = db.getState(AntiBubble.stateKey(category))
                ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            if (!AntiBubble.isDue(last, cfg, now)) {
                logger.debug { "[Category:$category][AntiBubble] last explore post $last — next after ${cfg.intervalDays} day(s)" }
                return kept
            }
            val scores = affinityScoresFor(category)
            if (scores.isEmpty()) {
                logger.info { "[Category:$category][AntiBubble] no affinity data yet — nothing picked" }
                return kept
            }
            val pick = AntiBubble.pick(pool, kept, digestConfig, cfg) { scores.itemScore(it) }
            if (pick == null) {
                logger.debug { "[Category:$category][AntiBubble] no candidate among ${pool.size - kept.size} left-out event(s)" }
                return kept
            }
            val reason = baseline.dropped.firstOrNull { it.item.eventKey == pick.item.eventKey }
                ?.reason?.substringBefore("=") ?: "affinity"
            logger.info {
                "[Category:$category][AntiBubble] ${pick.item.eventKey} nw=${pick.item.effectiveNewsworthiness} " +
                    "affinity=${"%+.2f".format(java.util.Locale.ROOT, pick.affinity)} (dropped: $reason)"
            }
            kept + pick.item.copy(lane = ShortlistItem.LANE_EXPLORE)
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$category][AntiBubble] pick failed — digest without it" }
            kept
        }
    }

    /**
     * `storyIntel`: stores [members] as `event_articles` rows (`role = step1`), timed by the
     * articles' own `pub_date` / `fetched_at`. A failure only logs — the clusters are analytics and
     * must never cost a digest.
     */
    private fun recordEventArticles(category: String, members: List<EventMembership.Member>, now: LocalDateTime) {
        if (members.isEmpty()) return
        try {
            val stamps = db.fetchArticleStamps(members.map { it.link })
            val rows = members.mapNotNull { m ->
                val stamp = stamps[m.link] ?: return@mapNotNull null
                EventArticleRow(
                    category = category,
                    articleId = stamp.id,
                    eventKey = m.eventKey,
                    link = m.link,
                    domain = AffinityMath.sourceDomain(m.link),
                    pubDate = stamp.pubDate,
                    seenAt = stamp.fetchedAt,
                    role = EventArticleRole.STEP1,
                    step1Status = m.step1Status,
                    recordedAt = now
                )
            }
            db.upsertEventArticles(rows)
            logger.debug { "[Category:$category][StoryIntel] recorded ${rows.size} article(s) in ${rows.map { it.eventKey }.distinct().size} event(s)" }
        } catch (e: Exception) {
            logger.warn(e) { "[Category:$category][StoryIntel] recording event_articles failed — digest unaffected" }
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
     * Hallucination guard for `eventDate` (the trackers' input, same spirit as the URL filter):
     * the value must be in the known vocabulary (`YYYY-MM-DD` / `YYYY-MM` / `YYYY-Qn` / `YYYY`)
     * AND its year must appear in the text of the item's source articles; otherwise the field is
     * nulled — a story without a date is fine, a story with an invented one is not.
     */
    private fun guardEventDate(tag: String, item: ShortlistItem, articles: List<Article>): ShortlistItem {
        val raw = item.eventDate ?: return item
        val normalized = TrackerDates.normalize(raw)
        val year = normalized?.let { TrackerDates.yearOf(it) }
        if (normalized == null || year == null) {
            logger.debug { "$tag[Dedup] eventDate '$raw' for ${item.eventKey} is not a known form — dropped" }
            return item.copy(eventDate = null)
        }
        val sourceLinks = metifikys.ai.PromptBuilder.sourceLinksOf(item, articles).toSet()
        val sources = articles.filter { it.link in sourceLinks }.ifEmpty { articles.filter { it.link == item.url } }
        val yearText = year.toString()
        val grounded = sources.any { a ->
            yearText in a.title || yearText in a.description || yearText in (a.summary ?: "")
        }
        if (!grounded) {
            logger.info { "$tag[Dedup] eventDate '$raw' for ${item.eventKey}: year $year not in the article text — dropped" }
            return item.copy(eventDate = null)
        }
        return if (normalized == raw) item else item.copy(eventDate = normalized)
    }

    /**
     * `digest.maxPostsPerEvent`: drops a `meaningful_update` when the channel already carries
     * that many posts about the story within `updateWindowDays` — counted in `digest_messages`
     * under the item's own key AND its `relatedPreviousEventKey` (Step 1 sometimes reuses the
     * key for an update, sometimes mints a new one). No newsworthiness override on purpose: the
     * repeats this exists for all cleared it. A DB failure keeps the shortlist unchanged.
     */
    private fun applyPostsPerEventCap(
        category: String,
        shortlist: List<ShortlistItem>,
        config: DigestConfig,
        now: LocalDateTime,
        tag: String
    ): List<ShortlistItem> {
        val cap = config.maxPostsPerEvent ?: return shortlist
        val updates = shortlist.filter { it.status == "meaningful_update" }
        if (updates.isEmpty()) return shortlist
        val keys = updates.flatMap { listOfNotNull(it.eventKey, it.relatedPreviousEventKey) }.toSet()
        val counts = try {
            db.countDigestPostsByEventKeys(category, keys, now.minusDays(config.updateWindowDays))
        } catch (e: Exception) {
            logger.warn(e) { "$tag[Ranker] maxPostsPerEvent lookup failed — cap not applied this run" }
            return shortlist
        }
        return shortlist.filter { item ->
            if (item.status != "meaningful_update") return@filter true
            val posts = listOfNotNull(item.eventKey, item.relatedPreviousEventKey).distinct().sumOf { counts[it] ?: 0 }
            val keep = posts < cap
            if (!keep) {
                logger.info {
                    "$tag[Ranker] drop ${item.eventKey}: maxPostsPerEvent=$cap reached " +
                        "($posts posts in ${config.updateWindowDays} days)"
                }
            }
            keep
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
        now: LocalDateTime,
        tag: String
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
                    "$tag[Dedup] cooldown lookup of ${missingKeys.size} key(s) outside the prompt context failed — " +
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
            val strongEnough = item.effectiveNewsworthiness >= config.newsworthinessOverride

            val keep = !withinCooldown || strongEnough
            if (!keep) dropped += CooldownDrop(item, previous, minutesSince)
            keep
        }
        return kept to dropped
    }
}
