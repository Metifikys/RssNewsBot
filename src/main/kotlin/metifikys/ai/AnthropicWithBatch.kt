package metifikys.ai

import metifikys.db.NewsDatabase
import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import java.util.concurrent.CompletableFuture

/**
 * Anthropic client that also services Batch API calls. Sync calls go through the
 * embedded [Anthropic]; batch calls go through the embedded [AnthropicBatch].
 * Used when an endpoint targets the Anthropic API and the caller asked for a
 * batch-capable client (`batchCapable=true`).
 */
class AnthropicWithBatch(
    override val endpoint: LlmEndpoint,
    db: NewsDatabase,
    maxTokens: Int,
    private val sync: Anthropic = Anthropic(endpoint, maxTokens),
    private val batch: AnthropicBatch = AnthropicBatch(endpoint, db, maxTokens)
) : LlmClient {

    override val isBatchCapable: Boolean get() = true

    override fun complete(systemPrompt: String, userPrompt: String): String =
        sync.complete(systemPrompt, userPrompt)

    override fun completeJson(systemPrompt: String, userPrompt: String): String =
        sync.completeJson(systemPrompt, userPrompt)

    override fun completeJson(systemPrompt: String, userPrompt: String, maxRetry: Int): String =
        sync.completeJson(systemPrompt, userPrompt, maxRetry)

    override fun summarizeArticles(
        category: String,
        emoji: String,
        articles: List<Article>,
        systemPrompt: String?,
        userPrompt: String?,
        previousSummaries: List<String>,
        maxArticles: Int
    ): String = sync.summarizeArticles(
        category, emoji, articles, systemPrompt, userPrompt, previousSummaries, maxArticles
    )

    override fun summarizeShortlist(
        category: String,
        emoji: String,
        shortlist: List<ShortlistItem>,
        articles: List<Article>,
        renderSystemPrompt: String,
        renderUserPromptTemplate: String
    ): String = sync.summarizeShortlist(
        category, emoji, shortlist, articles, renderSystemPrompt, renderUserPromptTemplate
    )

    override fun submitCategoryBatch(
        categoryName: String,
        input: CategoryInput
    ): CompletableFuture<String> = batch.submitCategory(categoryName, input)

    override fun resumeBatch(batchId: String): CompletableFuture<Map<String, String>> =
        batch.resumeBatch(batchId)

    override fun submitExtractBatch(
        category: String,
        systemPrompt: String,
        userPrompt: String,
        articleLinks: String
    ): CompletableFuture<String> = batch.submitExtract(category, systemPrompt, userPrompt, articleLinks)

    override fun resumeExtractBatch(batchId: String): CompletableFuture<String> =
        batch.resumeExtract(batchId)
}
