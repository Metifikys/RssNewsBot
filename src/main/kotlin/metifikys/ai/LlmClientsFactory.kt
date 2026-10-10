package metifikys.ai

import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.FeedConfig
import metifikys.config.LlmOverride
import metifikys.db.NewsDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves an [LlmClient] for a given category + use case. Honors per-category
 * `llm.{extract,render,batch,summarize,…}` overrides; falls back to current global
 * defaults when the override is absent.
 *
 * An override may be a weighted `providers:` split: every `forX` call then picks one entry via
 * [splitter] (persistent per-day tallies per category + slot) and builds that entry's client, so
 * one call of the slot = one pick. Callers resolve per call / per cycle — never cache the result.
 *
 * Caches clients by `(baseUrl, model, batchCapable)` so repeated calls reuse the
 * same instance — avoids re-creating OkHttp clients per category cycle. Provider
 * dispatch is driven by [LlmEndpoint.provider].
 */
class LlmClientsFactory(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val recorder: LlmCallRecorder? = null,
    /** Outcome sink for the `claude` / `codex` clients (`health.enabled`); null = not tracked. */
    private val cliHealth: CliOutcomeSink? = null,
    private val splitter: LlmSplitter = LlmSplitter(db)
) {
    private val cache = ConcurrentHashMap<Triple<String, String, Boolean>, LlmClient>()

    fun forRender(category: CategoryConfig?): LlmClient =
        category?.llm?.render?.let { build(it, category, LlmUseCase.RENDER) }
            ?: syncDefault(category, LlmUseCase.RENDER)

    /**
     * The editor-persona remark (`post.persona`): `llm.persona`, else the `llm.render` override,
     * else the global sync default — always a sync client, metered as [LlmUseCase.PERSONA] so its
     * calls show apart from the render ones in `llm_calls`.
     */
    fun forPersona(category: CategoryConfig?): LlmClient =
        (category?.llm?.persona ?: category?.llm?.render)?.let { build(it, category, LlmUseCase.PERSONA) }
            ?: syncDefault(category, LlmUseCase.PERSONA)

    /**
     * The update diff (`dedup.digest.updateDiff`): `llm.updateDiff`, else the `llm.render` override,
     * else the global sync default — always a sync client, metered as [LlmUseCase.UPDATE_DIFF] so
     * its calls show apart from the render ones in `llm_calls`.
     */
    fun forUpdateDiff(category: CategoryConfig?): LlmClient =
        (category?.llm?.updateDiff ?: category?.llm?.render)?.let { build(it, category, LlmUseCase.UPDATE_DIFF) }
            ?: syncDefault(category, LlmUseCase.UPDATE_DIFF)

    /**
     * The B side of `llm.shadow` for Step 1 ([LlmUseCase.SHADOW_EXTRACT]) or the render
     * ([LlmUseCase.SHADOW_RENDER]); null when the category shadows nothing at that stage. Always a
     * sync client (config validation rejects `batch: true` there), with its own `fallback` chain.
     */
    fun forShadow(category: CategoryConfig?, useCase: LlmUseCase): LlmClient? {
        val shadow = category?.llm?.shadow?.takeIf { it.enabled } ?: return null
        val ovr = when (useCase) {
            LlmUseCase.SHADOW_EXTRACT -> shadow.extract
            LlmUseCase.SHADOW_RENDER -> shadow.render
            else -> throw IllegalArgumentException("not a shadow use case: $useCase")
        } ?: return null
        return build(ovr, category, useCase)
    }

    /**
     * The `factCheck.judge` client, metered as [LlmUseCase.JUDGE] under [category] so `/status`
     * shows its calls per category; null when no judge is configured. Sync, with its fallback chain.
     * A split judge keeps one global tally (the judge is a global setting).
     */
    fun forJudge(category: CategoryConfig?): LlmClient? {
        val ovr = config.factCheck.judge ?: return null
        val leg = splitter.pick("$GLOBAL_SCOPE/judge", ovr.legs())
        return buildLeg(leg, category, LlmUseCase.JUDGE, batchCapable = false)
    }

    /** Global sync default when a slot has no override: the OpenRouter ladder when configured, else OpenAI. */
    private fun syncDefault(category: CategoryConfig?, useCase: LlmUseCase): LlmClient =
        forOpenRouterDefault(category, useCase)
            ?: meter(client(LlmEndpoint.forOpenAI(config), batchCapable = false), category, useCase)

    /**
     * Client for the global `openrouter:` default, honoring the `models:` priority ladder:
     * the first model is primary and each later one serves as the on-failure fallback for
     * the one before it ([FallbackLlmClient] chain). Free-tier endpoints come and go by
     * the hour — a dead first choice must not take the whole use case down with it. Each
     * link is metered separately, so `/status` attributes every call to the model that
     * actually answered. Null when no `openrouter:` block is configured; a single-model
     * config degenerates to today's plain client.
     */
    private fun forOpenRouterDefault(category: CategoryConfig?, useCase: LlmUseCase): LlmClient? {
        val or = config.openrouter ?: return null
        val base = LlmEndpoint.forOpenRouter(config) ?: return null
        return or.modelPriority
            .map { m -> meter(client(base.copy(model = m), batchCapable = false), category, useCase) }
            .reduceRight { primary, fallbackChain -> FallbackLlmClient(primary, fallbackChain) }
    }

    /**
     * Sync client for the `pending == 1` fallback path. Returns null when the category
     * has no `llm.batchFallback` override — caller must branch accordingly.
     */
    fun forBatchFallback(category: CategoryConfig?): LlmClient? {
        val ovr = category?.llm?.batchFallback ?: return null
        return build(ovr, category, LlmUseCase.RENDER, slot = "batchFallback")
    }

    /**
     * Step 1 extractor for the next call. With an `extract:` override, the (picked) entry's
     * `batch` flag decides whether the client is batch-capable. Without one, the default is the
     * old A/B pair as a built-in 50/50 split — OpenAI and the OpenRouter ladder — when an
     * `openrouter:` block is configured, else plain OpenAI.
     */
    fun forExtract(category: CategoryConfig?): LlmClient {
        val ovr = category?.llm?.extract
        if (ovr != null) return build(ovr, category, LlmUseCase.EXTRACT, batchCapable = true)
        val openAi = LlmOverride("openai", LlmEndpoint.forOpenAI(config).model, percent = 50)
        if (config.openrouter == null) return buildLeg(openAi, category, LlmUseCase.EXTRACT, batchCapable = false)
        val leg = splitter.pick(scopeOf(category, "extract"), listOf(openAi, LlmOverride("openrouter", "", percent = 50)))
        return buildLeg(leg, category, LlmUseCase.EXTRACT, batchCapable = false)
    }

    /**
     * The client that polls an extract batch submitted in an earlier cycle — no pick (it does not
     * count as a call of the split): the `batch: true` entry of the category's `extract`, and when
     * a split has two (OpenAI and Anthropic) the one whose id format matches [batchId]
     * (`msgbatch_…` = Anthropic). Without any batch entry: the plain OpenAI client, as before.
     */
    fun forExtractResume(category: CategoryConfig?, batchId: String): LlmClient {
        val batchLegs = category?.llm?.extract?.legs().orEmpty().filter { it.batch }
        val leg = batchLegs.firstOrNull { (it.provider == "anthropic") == batchId.startsWith("msgbatch_") }
            ?: batchLegs.firstOrNull()
            ?: return meter(client(LlmEndpoint.forOpenAI(config), batchCapable = false), category, LlmUseCase.EXTRACT)
        return meter(client(overrideEndpoint(leg), batchCapable = true), category, LlmUseCase.EXTRACT)
    }

    /**
     * Batch path. When no override: defaults to OpenAI batch model. When override
     * is present: dispatches to either OpenAI or Anthropic batch (validated upstream
     * — OpenRouter is rejected by [metifikys.config.ConfigLoader] for batch).
     */
    fun forBatch(category: CategoryConfig?): LlmClient {
        val ovr = category?.llm?.batch
        val ep = if (ovr != null) {
            overrideEndpoint(pick(ovr, category, "batch"))
        } else {
            LlmEndpoint.forOpenAI(config).copy(model = config.openai.batchModel)
        }
        return meter(client(ep, batchCapable = true), category, LlmUseCase.BATCH)
    }

    /**
     * Per-article summarize path. The feed's `summarize:` decides first:
     * - `auto` hands the choice to the category's `llm.summarize` — a plain override or a weighted
     *   `providers:` split (one pick per article); without one, the global sync default.
     * - a provider name pins that provider. The category override only refines the model when it
     *   is on the same provider — for a split, the entry on that provider (no pick, no tally). An
     *   override on a different provider is ignored: it must not silently re-route a feed that
     *   explicitly opted into another provider.
     */
    fun forSummarize(category: CategoryConfig?, feedProvider: String): LlmClient {
        val ovr = category?.llm?.summarize
        if (feedProvider == FeedConfig.SUMMARIZE_AUTO) {
            return ovr?.let { build(it, category, LlmUseCase.SUMMARIZE) } ?: syncDefault(category, LlmUseCase.SUMMARIZE)
        }
        val matchedOvr = ovr?.legs()?.firstOrNull { it.provider == feedProvider }
        if (matchedOvr != null) return buildLeg(matchedOvr, category, LlmUseCase.SUMMARIZE, batchCapable = false)
        val ep = when (feedProvider) {
            "openai" -> LlmEndpoint.forOpenAI(config)
            // Default openrouter path honors the models: priority ladder (already metered).
            "openrouter" -> return forOpenRouterDefault(category, LlmUseCase.SUMMARIZE)
                ?: error("feed.summarize=openrouter but no openrouter: block configured")
            "anthropic" -> LlmEndpoint.forAnthropic(config)
                ?: error("feed.summarize=anthropic but no anthropic: block configured")
            "claudecli" -> LlmEndpoint.forClaudeCli(config)
                ?: error("feed.summarize=claudecli but no claudeCli: block configured")
            "codexcli" -> LlmEndpoint.forCodexCli(config)
                ?: error("feed.summarize=codexcli but no codexCli: block configured")
            else -> error("Unknown summarize provider '$feedProvider'")
        }
        // The per-feed default path has no LlmOverride to carry a fallback.
        return meter(client(ep, batchCapable = false), category, LlmUseCase.SUMMARIZE)
    }

    /**
     * The client for one call of a slot: picks the entry of a `providers:` split (or takes the
     * plain override) and builds it. [batchCapable] = the slot may run on the Batch API — the
     * picked entry's own `batch` flag then decides. [slot] names the split's tally; it defaults
     * to the use case, and differs only where two slots share a metering use case.
     */
    private fun build(
        ovr: LlmOverride,
        category: CategoryConfig?,
        useCase: LlmUseCase,
        batchCapable: Boolean = false,
        slot: String = useCase.name.lowercase()
    ): LlmClient = buildLeg(pick(ovr, category, slot), category, useCase, batchCapable)

    private fun pick(ovr: LlmOverride, category: CategoryConfig?, slot: String): LlmOverride =
        ovr.providers?.let { splitter.pick(scopeOf(category, slot), it) } ?: ovr

    /** One concrete override → its metered client, wrapped in its on-failure `fallback` chain. */
    private fun buildLeg(leg: LlmOverride, category: CategoryConfig?, useCase: LlmUseCase, batchCapable: Boolean): LlmClient =
        withFallback(leg, meteredLeg(leg, category, useCase, batchCapable && leg.batch), category, useCase)

    /** A blank OpenRouter model means the `openrouter.models` ladder (already metered per link). */
    private fun meteredLeg(leg: LlmOverride, category: CategoryConfig?, useCase: LlmUseCase, batchCapable: Boolean): LlmClient =
        if (leg.provider == "openrouter" && leg.model.isBlank()) {
            forOpenRouterDefault(category, useCase)
                ?: error("Override uses provider 'openrouter' but no openrouter: block configured")
        } else {
            meter(client(overrideEndpoint(leg), batchCapable), category, useCase)
        }

    private fun categoryName(category: CategoryConfig?): String? =
        config.categories.entries.firstOrNull { it.value === category }?.key

    private fun scopeOf(category: CategoryConfig?, slot: String): String = "${categoryName(category) ?: GLOBAL_SCOPE}/$slot"

    /**
     * Wraps a cached client in a [MeteredLlmClient] so its calls show up in `/status`.
     * No-op when this factory was constructed without a [LlmCallRecorder] — that's the
     * case in unit tests, which want to assert on the underlying concrete client type.
     */
    private fun meter(inner: LlmClient, category: CategoryConfig?, useCase: LlmUseCase): LlmClient {
        val rec = recorder ?: return inner
        return MeteredLlmClient(inner, rec, categoryName(category), useCase)
    }

    /**
     * Wraps [meteredPrimary] in a [FallbackLlmClient] when [primaryOvr] declares an on-failure
     * `fallback` (and is itself a sync leg). Each link is metered separately — meter-then-wrap —
     * so `/status` attributes a fallback-served call to the provider that actually answered it;
     * the recursion supports a fallback chain. A `batch=true` primary leg is never wrapped: it
     * takes the async Batch API path where a sync fallback can't help (config validation also
     * rejects a fallback there). Returns [meteredPrimary] unchanged when there is no fallback.
     */
    private fun withFallback(
        primaryOvr: LlmOverride?,
        meteredPrimary: LlmClient,
        category: CategoryConfig?,
        useCase: LlmUseCase
    ): LlmClient {
        val fb = primaryOvr?.fallback
        if (fb == null || primaryOvr.batch) return meteredPrimary
        val meteredFallback = meteredLeg(fb, category, useCase, batchCapable = false)
        return FallbackLlmClient(meteredPrimary, withFallback(fb, meteredFallback, category, useCase))
    }

    private fun client(ep: LlmEndpoint, batchCapable: Boolean): LlmClient =
        cache.getOrPut(Triple(ep.baseUrl, ep.model, batchCapable)) {
            when (ep.provider) {
                LlmEndpoint.Provider.ANTHROPIC -> {
                    val maxTokens = config.anthropic?.maxTokens
                        ?: error("Anthropic endpoint constructed without anthropic: config block")
                    if (batchCapable) AnthropicWithBatch(ep, db, maxTokens)
                    else Anthropic(ep, maxTokens)
                }
                LlmEndpoint.Provider.OPENAI_COMPATIBLE -> {
                    if (batchCapable) OpenAIWithBatch(ep, db)
                    else OpenAI(ep, batchUnsupportedReason = unsupportedReasonFor(ep))
                }
                LlmEndpoint.Provider.CLAUDE_CLI -> {
                    val cli = config.claudeCli
                        ?: error("Claude CLI endpoint constructed without claudeCli: config block")
                    require(!batchCapable) { "Claude CLI provider has no Batch API — it cannot be batch-capable" }
                    ClaudeCli(ep, cli.command, cli.timeoutSeconds, health = cliHealth)
                }
                LlmEndpoint.Provider.CODEX_CLI -> {
                    val cli = config.codexCli
                        ?: error("Codex CLI endpoint constructed without codexCli: config block")
                    require(!batchCapable) { "Codex CLI provider has no Batch API — it cannot be batch-capable" }
                    CodexCli(ep, cli.command, cli.timeoutSeconds, health = cliHealth)
                }
            }
        }

    private fun unsupportedReasonFor(ep: LlmEndpoint): String =
        if (ep.baseUrl == "https://api.openai.com/v1") {
            "OpenAI sync client does not handle batch — construct OpenAIWithBatch instead"
        } else {
            "OpenRouter does not support the Batch API"
        }

    private fun overrideEndpoint(o: LlmOverride): LlmEndpoint = when (o.provider) {
        "openai" -> LlmEndpoint.forOpenAI(config).copy(model = o.model)
        "openrouter" -> {
            val or = LlmEndpoint.forOpenRouter(config)
                ?: error("Override uses provider 'openrouter' but no openrouter: block configured")
            // Blank model = the ladder; callers route that to forOpenRouterDefault, this keeps its first rung.
            if (o.model.isBlank()) or else or.copy(model = o.model)
        }
        "anthropic" -> {
            val a = LlmEndpoint.forAnthropic(config)
                ?: error("Override uses provider 'anthropic' but no anthropic: block configured")
            a.copy(model = o.model)
        }
        "claudecli" -> {
            val c = LlmEndpoint.forClaudeCli(config)
                ?: error("Override uses provider 'claudecli' but no claudeCli: block configured")
            // Blank model means "CLI default" — keep the endpoint's model rather than overriding with "".
            if (o.model.isBlank()) c else c.copy(model = o.model)
        }
        "codexcli" -> {
            val c = LlmEndpoint.forCodexCli(config)
                ?: error("Override uses provider 'codexcli' but no codexCli: block configured")
            // Blank model means "CLI default" — keep the endpoint's model rather than overriding with "".
            if (o.model.isBlank()) c else c.copy(model = o.model)
        }
        else -> error("Unknown override provider '${o.provider}' (validated upstream)")
    }

    companion object {
        /** Tally scope of a slot outside any category (the global judge, a category-less call). */
        const val GLOBAL_SCOPE = "*"
    }
}
