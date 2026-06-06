package metifikys.ai

import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import java.util.concurrent.CompletableFuture

/**
 * Decorator that wraps an [LlmClient], estimates token counts from string lengths
 * (chars / 4), and emits one [LlmCallRecorder] row per successful call. The
 * underlying client's behavior is unchanged.
 *
 * Token counts are approximate by design — they are proportional, not exact — and
 * are good enough to spot cost trends in `/status`. Switching to exact API-reported
 * `usage` values would require parser changes in [OpenAI] / [Anthropic] and is
 * deferred (see phase1 doc).
 */
class MeteredLlmClient(
    val inner: LlmClient,
    private val recorder: LlmCallRecorder,
    private val category: String?,
    private val useCase: LlmUseCase
) : LlmClient {

    override val endpoint: LlmEndpoint get() = inner.endpoint
    override val isBatchCapable: Boolean get() = inner.isBatchCapable

    private val provider: String get() = providerKeyOf(inner.endpoint)
    private val model: String get() = inner.endpoint.model

    override fun complete(systemPrompt: String, userPrompt: String): String {
        val result = inner.complete(systemPrompt, userPrompt)
        record(systemPrompt.length + userPrompt.length, result.length)
        return result
    }

    override fun completeJson(systemPrompt: String, userPrompt: String): String {
        val result = inner.completeJson(systemPrompt, userPrompt)
        record(systemPrompt.length + userPrompt.length, result.length)
        return result
    }

    override fun completeJson(systemPrompt: String, userPrompt: String, maxRetry: Int): String {
        val result = inner.completeJson(systemPrompt, userPrompt, maxRetry)
        record(systemPrompt.length + userPrompt.length, result.length)
        return result
    }

    override fun summarizeArticles(
        category: String,
        emoji: String,
        articles: List<Article>,
        systemPrompt: String?,
        userPrompt: String?,
        previousSummaries: List<String>,
        maxArticles: Int
    ): String {
        val result = inner.summarizeArticles(
            category, emoji, articles, systemPrompt, userPrompt, previousSummaries, maxArticles
        )
        val promptChars = category.length + emoji.length +
            (systemPrompt?.length ?: 0) + (userPrompt?.length ?: 0) +
            previousSummaries.sumOf { it.length } +
            articles.sumOf { it.title.length + it.description.length }
        record(promptChars, result.length)
        return result
    }

    override fun summarizeShortlist(
        category: String,
        emoji: String,
        shortlist: List<ShortlistItem>,
        articles: List<Article>,
        renderSystemPrompt: String,
        renderUserPromptTemplate: String
    ): String {
        val result = inner.summarizeShortlist(
            category, emoji, shortlist, articles, renderSystemPrompt, renderUserPromptTemplate
        )
        val promptChars = category.length + emoji.length +
            renderSystemPrompt.length + renderUserPromptTemplate.length +
            shortlist.sumOf { it.toString().length } +
            articles.sumOf { it.title.length + it.description.length }
        record(promptChars, result.length)
        return result
    }

    override fun submitCategoryBatch(
        categoryName: String,
        input: CategoryInput
    ): CompletableFuture<String> {
        val promptChars = categoryName.length + input.toString().length
        return inner.submitCategoryBatch(categoryName, input)
            .whenComplete { result, _ -> record(promptChars, result?.length ?: 0, isBatch = true) }
    }

    override fun resumeBatch(batchId: String): CompletableFuture<Map<String, String>> {
        return inner.resumeBatch(batchId)
            .whenComplete { result, _ ->
                val out = result?.values?.sumOf { it.length } ?: 0
                record(0, out, isBatch = true)
            }
    }

    override fun submitExtractBatch(
        category: String,
        systemPrompt: String,
        userPrompt: String,
        articleLinks: String
    ): CompletableFuture<String> {
        val promptChars = category.length + systemPrompt.length + userPrompt.length + articleLinks.length
        return inner.submitExtractBatch(category, systemPrompt, userPrompt, articleLinks)
            .whenComplete { result, _ -> record(promptChars, result?.length ?: 0, isBatch = true) }
    }

    override fun resumeExtractBatch(batchId: String): CompletableFuture<String> {
        return inner.resumeExtractBatch(batchId)
            .whenComplete { result, _ -> record(0, result?.length ?: 0, isBatch = true) }
    }

    private fun record(promptChars: Int, completionChars: Int, isBatch: Boolean = false) {
        recorder.record(
            provider = provider,
            model = model,
            category = category,
            useCase = useCase,
            promptTokens = estimateTokens(promptChars),
            completionTokens = estimateTokens(completionChars),
            isBatch = isBatch
        )
    }

    companion object {
        /** Rough chars-to-tokens conversion. ~4 chars per token across English/Ukrainian/JSON. */
        internal fun estimateTokens(chars: Int): Int = if (chars <= 0) 0 else chars / 4

        /**
         * Stable provider tag used for pricing lookups and aggregation in `/status`.
         * `OPENAI_COMPATIBLE` covers both OpenAI and OpenRouter; the baseUrl
         * disambiguates them.
         */
        internal fun providerKeyOf(endpoint: LlmEndpoint): String = when (endpoint.provider) {
            LlmEndpoint.Provider.ANTHROPIC -> "anthropic"
            LlmEndpoint.Provider.CLAUDE_CLI -> "claudecli"
            LlmEndpoint.Provider.OPENAI_COMPATIBLE ->
                if (endpoint.baseUrl == "https://api.openai.com/v1") "openai" else "openrouter"
        }
    }
}
