package metifikys.fetch

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.BillingException
import metifikys.ai.LlmClient
import metifikys.ai.LlmClientsFactory
import metifikys.config.AppConfig
import metifikys.config.CategoryConfig
import metifikys.model.Article

private val logger = KotlinLogging.logger {}

/**
 * Per-article summarization step. Mirrors [ArticleFetcher.enrich] in shape so it
 * slots into the same pipeline position (after enrichment, before DB insert).
 *
 * For each article carrying a [Article.summarize] provider name, calls the matching
 * sync chat client and stores the result into [Article.summary]. Failures are silent —
 * the article is returned unchanged with `summary == null`, and the digest path
 * falls back to the raw [Article.description].
 */
class ArticleSummarizer(
    private val config: AppConfig,
    private val categories: Map<String, CategoryConfig>,
    private val llmClientsFactory: LlmClientsFactory
) {

    fun summarize(articles: List<Article>): List<Article> =
        articles.map { a -> if (a.summarize.isNullOrBlank()) a else trySummarize(a) }

    private fun trySummarize(article: Article): Article {
        val provider = article.summarize ?: return article
        val cat = categories[article.category]
        val client: LlmClient = try {
            llmClientsFactory.forSummarize(cat, provider)
        } catch (e: IllegalStateException) {
            logger.warn { "[Summarize] ${e.message} (article ${article.link})" }
            return article
        }
        val sysPrompt = cat?.summarizePrompt ?: DEFAULT_PROMPT
        val userPrompt = buildString {
            append("Title: ").appendLine(article.title)
            appendLine()
            appendLine("Content:")
            append(article.description)
        }
        val ep = client.endpoint
        return try {
            val out = client.complete(sysPrompt, userPrompt).trim()
            if (out.isBlank()) {
                logger.warn { "[Summarize] blank output for ${article.link} via $provider [${ep.model}@${ep.baseUrl}]" }
                article
            } else {
                logger.info { "[Summarize] ${article.link} via $provider [${ep.model}@${ep.baseUrl}] → ${out.length} chars" }
                article.copy(summary = out)
            }
        } catch (e: BillingException) {
            logger.warn { "[Summarize] billing limit on $provider [${ep.model}@${ep.baseUrl}] — skipping ${article.link}" }
            article
        } catch (e: Exception) {
            logger.warn(e) { "[Summarize] failed for ${article.link} via $provider [${ep.model}@${ep.baseUrl}]" }
            article
        }
    }

    companion object {
        private const val DEFAULT_PROMPT =
            "Summarize the following article in 3-5 concise sentences. " +
            "Extract the core news value: what happened, who is involved, why it matters, and any important numbers, dates, locations, named entities, or direct quotes. " +
            "Preserve factual accuracy and avoid speculation, opinions, or information not explicitly stated in the article. " +
            "Reply in the article's original language. " +
            "Return only the summary text, without headings, bullet points, commentary, or meta explanations."
    }
}
