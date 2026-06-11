package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import java.util.concurrent.CompletableFuture

private val logger = KotlinLogging.logger {}

/**
 * Decorator that routes a call to [primary]; if the primary throws a failover-worthy
 * exception, it logs a WARN and retries the SAME call against [fallback]. This is a
 * genuine on-failure fallback — distinct from the A/B alternation (`extractAlternate`)
 * and the queue-backpressure routing (`batchFallback`), neither of which reacts to a
 * provider actually failing.
 *
 * Wired in by [LlmClientsFactory] for the sync use cases (render / extract legs /
 * summarize / stuck-batch sync). Both legs are independently wrapped in
 * [MeteredLlmClient] BEFORE composition, so `/status` attributes each served call to
 * the provider that actually answered it.
 *
 * Failover policy:
 *   - [UnsupportedOperationException] — a batch method on a sync-only client: a wiring
 *     contract violation, not a runtime failure. Rethrown, never failed over.
 *   - [InterruptedException] — the category worker was cancelled (the 15-min barrier in
 *     [metifikys.digest.CategoryProcessor]). The interrupt flag is restored and the
 *     exception rethrown; we do NOT start a fresh, long fallback call on a dying thread.
 *   - any other [Exception] ([BillingException], [NonRetryableCliException], an
 *     exhausted-retry IOException, …) — logged at WARN, then the fallback serves the call.
 *
 * Batch methods delegate to [primary] only — a sync fallback cannot serve a Batch API
 * request, and this wrapper is never installed on a batch-capable leg anyway.
 */
class FallbackLlmClient(
    val primary: LlmClient,
    val fallback: LlmClient
) : LlmClient {

    init {
        require(primary !== fallback) {
            "FallbackLlmClient primary and fallback must differ — a fallback that resolves to the " +
                "same provider+model (hence the same cached client) can never help"
        }
    }

    override val endpoint: LlmEndpoint get() = primary.endpoint
    override val isBatchCapable: Boolean get() = primary.isBatchCapable

    override fun complete(systemPrompt: String, userPrompt: String): String =
        withFailover("complete") { it.complete(systemPrompt, userPrompt) }

    override fun completeJson(systemPrompt: String, userPrompt: String): String =
        withFailover("completeJson") { it.completeJson(systemPrompt, userPrompt) }

    override fun completeJson(systemPrompt: String, userPrompt: String, maxRetry: Int): String =
        withFailover(
            "completeJson(maxRetry=$maxRetry)",
            primaryCall = { primary.completeJson(systemPrompt, userPrompt, maxRetry) },
            // The fallback leg gets a single attempt (maxRetry=0): by the time the primary
            // exhausted its own ladder the system is degraded; we want a fast second-provider
            // try, not another deep retry loop (which could blow the category deadline).
            fallbackCall = { fallback.completeJson(systemPrompt, userPrompt, 0) }
        )

    override fun summarizeArticles(
        category: String,
        emoji: String,
        articles: List<Article>,
        systemPrompt: String?,
        userPrompt: String?,
        previousSummaries: List<String>,
        maxArticles: Int
    ): String = withFailover("summarizeArticles") {
        it.summarizeArticles(category, emoji, articles, systemPrompt, userPrompt, previousSummaries, maxArticles)
    }

    override fun summarizeShortlist(
        category: String,
        emoji: String,
        shortlist: List<ShortlistItem>,
        articles: List<Article>,
        renderSystemPrompt: String,
        renderUserPromptTemplate: String
    ): String = withFailover("summarizeShortlist") {
        it.summarizeShortlist(category, emoji, shortlist, articles, renderSystemPrompt, renderUserPromptTemplate)
    }

    // --- Batch methods: primary only, no failover (fallback is a sync concept). ---

    override fun submitCategoryBatch(categoryName: String, input: CategoryInput): CompletableFuture<String> =
        primary.submitCategoryBatch(categoryName, input)

    override fun resumeBatch(batchId: String): CompletableFuture<Map<String, String>> =
        primary.resumeBatch(batchId)

    override fun submitExtractBatch(
        category: String,
        systemPrompt: String,
        userPrompt: String,
        articleLinks: String
    ): CompletableFuture<String> =
        primary.submitExtractBatch(category, systemPrompt, userPrompt, articleLinks)

    override fun resumeExtractBatch(batchId: String): CompletableFuture<String> =
        primary.resumeExtractBatch(batchId)

    /**
     * Runs [primaryCall]; on a failover-worthy failure logs and runs [fallbackCall].
     * Used directly only by `completeJson(maxRetry)`, where the two legs differ (the
     * fallback leg drops `maxRetry` to 0). Every other method uses the single-lambda
     * overload below.
     */
    private inline fun <T> withFailover(
        op: String,
        primaryCall: () -> T,
        fallbackCall: () -> T
    ): T {
        try {
            return primaryCall()
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            logger.warn {
                "[LLM][fallback] $op failed on primary ${tag(primary)} " +
                    "(${e.javaClass.simpleName}: ${e.message}) — failing over to ${tag(fallback)}"
            }
            return fallbackCall()
        }
    }

    /** Same client for both legs (the common case — only the retry budget would ever differ). */
    private inline fun <T> withFailover(op: String, call: (LlmClient) -> T): T =
        withFailover(op, primaryCall = { call(primary) }, fallbackCall = { call(fallback) })

    private fun tag(c: LlmClient): String {
        val ep = c.endpoint
        return "${MeteredLlmClient.providerKeyOf(ep)}/${ep.model.ifBlank { "<cli-default>" }}"
    }
}
