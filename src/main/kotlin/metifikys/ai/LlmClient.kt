package metifikys.ai

import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import java.util.concurrent.CompletableFuture

/**
 * Provider-agnostic LLM abstraction. Sync chat-completion methods are universal;
 * batch methods throw [UnsupportedOperationException] on providers without a Batch API
 * (e.g. OpenRouter) — only [OpenAIWithBatch] / [AnthropicWithBatch] service them.
 */
interface LlmClient {
    val endpoint: LlmEndpoint

    /** True only for batch-capable wrappers (OpenAIWithBatch, AnthropicWithBatch). */
    val isBatchCapable: Boolean get() = false

    fun complete(systemPrompt: String, userPrompt: String): String

    fun completeJson(systemPrompt: String, userPrompt: String): String

    fun completeJson(systemPrompt: String, userPrompt: String, maxRetry: Int): String

    fun summarizeArticles(
        category: String,
        emoji: String,
        articles: List<Article>,
        systemPrompt: String? = null,
        userPrompt: String? = null,
        previousSummaries: List<String> = emptyList(),
        maxArticles: Int = 30
    ): String

    fun summarizeShortlist(
        category: String,
        emoji: String,
        shortlist: List<ShortlistItem>,
        articles: List<Article>,
        renderSystemPrompt: String,
        renderUserPromptTemplate: String
    ): String

    fun submitCategoryBatch(
        categoryName: String,
        input: CategoryInput
    ): CompletableFuture<String>

    fun resumeBatch(batchId: String): CompletableFuture<Map<String, String>>

    /**
     * Submits a single-request batch for Step 1 event extraction.
     * Returns a future that resolves to the raw JSON content string (same shape as
     * [completeJson]).  [articleLinks] is a newline-separated list of article URLs
     * persisted in the DB so a restart can reconstruct [allowedUrls] deterministically.
     * Throws [UnsupportedOperationException] on sync-only clients.
     */
    fun submitExtractBatch(
        category: String,
        systemPrompt: String,
        userPrompt: String,
        articleLinks: String = ""
    ): CompletableFuture<String>

    /**
     * Resumes polling a previously submitted extract batch.
     * Returns a future that resolves to the raw JSON content (single result).
     * Throws [UnsupportedOperationException] on sync-only clients.
     */
    fun resumeExtractBatch(batchId: String): CompletableFuture<String>
}
