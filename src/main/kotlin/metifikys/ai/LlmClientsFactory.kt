package metifikys.ai

import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.config.LlmOverride
import metifikys.db.NewsDatabase
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves an [LlmClient] for a given category + use case. Honors per-category
 * `llm.{extract,render,batch,summarize}` overrides; falls back to current global
 * defaults when the override is absent.
 *
 * Caches clients by `(baseUrl, model, batchCapable)` so repeated calls reuse the
 * same instance — avoids re-creating OkHttp clients per category cycle. Provider
 * dispatch is driven by [LlmEndpoint.provider].
 */
class LlmClientsFactory(
    private val config: AppConfig,
    private val db: NewsDatabase,
    private val recorder: LlmCallRecorder? = null
) {
    private val cache = ConcurrentHashMap<Triple<String, String, Boolean>, LlmClient>()

    fun forRender(category: CategoryConfig?): LlmClient {
        val ovr = category?.llm?.render
        val primary = meter(
            client(resolve(ovr) { LlmEndpoint.forSync(config) }, batchCapable = false),
            category, LlmUseCase.RENDER
        )
        return withFallback(ovr, primary, category, LlmUseCase.RENDER)
    }

    /**
     * Sync client for the `pending == 1` fallback path. Returns null when the category
     * has no `llm.batchFallback` override — caller must branch accordingly.
     */
    fun forBatchFallback(category: CategoryConfig?): LlmClient? {
        val ovr = category?.llm?.batchFallback ?: return null
        val primary = meter(client(overrideEndpoint(ovr), batchCapable = false), category, LlmUseCase.RENDER)
        return withFallback(ovr, primary, category, LlmUseCase.RENDER)
    }

    /**
     * Step 1 extractor: returns (primary, alternate?). When the category has an
     * `extract:` override, `ovr.batch` controls whether the client is batch-capable.
     * Alternation is suppressed when only `extract` is set (no `extractAlternate`).
     */
    fun forExtract(category: CategoryConfig?): Pair<LlmClient, LlmClient?> {
        val ovr = category?.llm?.extract
        val altOvr = category?.llm?.extractAlternate
        return if (ovr != null) {
            val primary = withFallback(
                ovr,
                meter(client(overrideEndpoint(ovr), batchCapable = ovr.batch), category, LlmUseCase.EXTRACT),
                category, LlmUseCase.EXTRACT
            )
            val alternate = altOvr?.let {
                withFallback(
                    it,
                    meter(client(overrideEndpoint(it), batchCapable = it.batch), category, LlmUseCase.EXTRACT),
                    category, LlmUseCase.EXTRACT
                )
            }
            primary to alternate
        } else {
            val primary = meter(client(LlmEndpoint.forOpenAI(config), batchCapable = false), category, LlmUseCase.EXTRACT)
            val alternate = LlmEndpoint.forOpenRouter(config)?.let {
                meter(client(it, batchCapable = false), category, LlmUseCase.EXTRACT)
            }
            primary to alternate
        }
    }

    /**
     * Batch path. When no override: defaults to OpenAI batch model. When override
     * is present: dispatches to either OpenAI or Anthropic batch (validated upstream
     * — OpenRouter is rejected by [metifikys.config.ConfigLoader] for batch).
     */
    fun forBatch(category: CategoryConfig?): LlmClient {
        val ovr = category?.llm?.batch
        val ep = if (ovr != null) {
            overrideEndpoint(ovr)
        } else {
            LlmEndpoint.forOpenAI(config).copy(model = config.openai.batchModel)
        }
        return meter(client(ep, batchCapable = true), category, LlmUseCase.BATCH)
    }

    /**
     * Per-article summarize path. The per-feed `summarize:` provider always wins;
     * a category-level `summarize:` override only refines the model when its
     * provider matches the feed's (i.e. it acts as "use this model for feeds in
     * this category that ask for provider X"). A category override pointing at a
     * different provider is ignored — it must not silently re-route a feed that
     * explicitly opted into another provider.
     */
    fun forSummarize(category: CategoryConfig?, feedProvider: String): LlmClient {
        val ovr = category?.llm?.summarize
        val matchedOvr = ovr?.takeIf { it.provider == feedProvider }
        val ep = if (matchedOvr != null) {
            overrideEndpoint(matchedOvr)
        } else when (feedProvider) {
            "openai" -> LlmEndpoint.forOpenAI(config)
            "openrouter" -> LlmEndpoint.forOpenRouter(config)
                ?: error("feed.summarize=openrouter but no openrouter: block configured")
            "anthropic" -> LlmEndpoint.forAnthropic(config)
                ?: error("feed.summarize=anthropic but no anthropic: block configured")
            "claudecli" -> LlmEndpoint.forClaudeCli(config)
                ?: error("feed.summarize=claudecli but no claudeCli: block configured")
            "codexcli" -> LlmEndpoint.forCodexCli(config)
                ?: error("feed.summarize=codexcli but no codexCli: block configured")
            else -> error("Unknown summarize provider '$feedProvider'")
        }
        val primary = meter(client(ep, batchCapable = false), category, LlmUseCase.SUMMARIZE)
        // Fallback only applies when a category `summarize` override actually drove the
        // choice; the per-feed default path has no LlmOverride to carry one.
        return withFallback(matchedOvr, primary, category, LlmUseCase.SUMMARIZE)
    }

    /**
     * Wraps a cached client in a [MeteredLlmClient] so its calls show up in `/status`.
     * No-op when this factory was constructed without a [LlmCallRecorder] — that's the
     * case in unit tests, which want to assert on the underlying concrete client type.
     */
    private fun meter(inner: LlmClient, category: CategoryConfig?, useCase: LlmUseCase): LlmClient {
        val rec = recorder ?: return inner
        val categoryName = config.categories.entries.firstOrNull { it.value === category }?.key
        return MeteredLlmClient(inner, rec, categoryName, useCase)
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
        val meteredFallback = meter(client(overrideEndpoint(fb), batchCapable = false), category, useCase)
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
                    ClaudeCli(ep, cli.command, cli.timeoutSeconds)
                }
                LlmEndpoint.Provider.CODEX_CLI -> {
                    val cli = config.codexCli
                        ?: error("Codex CLI endpoint constructed without codexCli: config block")
                    require(!batchCapable) { "Codex CLI provider has no Batch API — it cannot be batch-capable" }
                    CodexCli(ep, cli.command, cli.timeoutSeconds)
                }
            }
        }

    private fun unsupportedReasonFor(ep: LlmEndpoint): String =
        if (ep.baseUrl == "https://api.openai.com/v1") {
            "OpenAI sync client does not handle batch — construct OpenAIWithBatch instead"
        } else {
            "OpenRouter does not support the Batch API"
        }

    private fun resolve(o: LlmOverride?, default: () -> LlmEndpoint): LlmEndpoint =
        if (o != null) overrideEndpoint(o) else default()

    private fun overrideEndpoint(o: LlmOverride): LlmEndpoint = when (o.provider) {
        "openai" -> LlmEndpoint.forOpenAI(config).copy(model = o.model)
        "openrouter" -> {
            val or = LlmEndpoint.forOpenRouter(config)
                ?: error("Override uses provider 'openrouter' but no openrouter: block configured")
            or.copy(model = o.model)
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
}
