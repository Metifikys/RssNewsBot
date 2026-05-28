package metifikys.ai

import metifikys.db.NewsDatabase
import metifikys.model.Article
import metifikys.model.CategoryInput
import metifikys.model.ShortlistItem
import java.util.concurrent.CompletableFuture

/**
 * OpenAI client that also services Batch API calls. Sync calls go through the
 * embedded [OpenAI]; batch calls go through the embedded [OpenAIBatch]. Used only
 * when the endpoint targets OpenAI proper — OpenRouter has no compatible Batch API.
 */
class OpenAIWithBatch(
    override val endpoint: LlmEndpoint,
    db: NewsDatabase,
    private val sync: OpenAI = OpenAI(endpoint),
    private val batch: OpenAIBatch = OpenAIBatch(endpoint.apiKey, db, endpoint.model)
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
