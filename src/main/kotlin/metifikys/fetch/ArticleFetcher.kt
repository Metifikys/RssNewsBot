package metifikys.fetch

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.model.Article
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Fetches and extracts readable text content from article web pages.
 *
 * Used to enrich RSS feed items that have empty or very short descriptions by fetching
 * the linked article URL and extracting the main text body via jsoup.
 *
 * SSRF protection: reuses [RssFetcher.validateFeedUrl] before any network call.
 * All failures are silent — the original article is always returned unchanged on any error.
 */
class ArticleFetcher(
    private val rssFetcher: RssFetcher,
    private val enforceUrlValidation: Boolean = true,
    /** Articles with descriptions shorter than this are eligible for content fetching. */
    private val minDescriptionLength: Int = 50,
    /** Maximum characters of extracted text to store as the enriched description. */
    private val maxContentLength: Int = 1500
) {

    companion object {
        private const val TIMEOUT_SECONDS = 10L
        private const val MARKDOWN_NEW_BASE_URL = "https://markdown.new/"

        /**
         * CSS selectors tried in priority order to locate the article's main content element.
         * First match yielding substantial text wins.
         */
        private val ARTICLE_SELECTORS = listOf(
            "article",
            "[role=main]",
            "main",
            ".article-body",
            ".article-content",
            ".post-body",
            ".post-content",
            ".entry-content",
            ".content-body",
            ".story-body",
            "#article-body",
            "#content"
        )

        /** Minimum text length for a selector match to be considered valid content. */
        private const val MIN_SELECTOR_TEXT_LENGTH = 100
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Article pages legitimately redirect (http→https, www→non-www); no token leakage risk here
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Separate client for markdown.new — longer timeouts since it proxies and processes the page. */
    private val markdownNewClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Enriches articles that have [Article.fetchFullContent] set and a short/empty description
     * by fetching their linked URL and extracting the article body text.
     *
     * Articles that already have sufficient description, or where fetching is not requested,
     * are returned unchanged. Failures are always silent.
     *
     * @return New list with enriched descriptions where applicable; original articles otherwise.
     */
    fun enrich(articles: List<Article>): List<Article> {
        return articles.map { article ->
            if (article.fetchFullContent) {
                tryFetchContent(article)
            } else {
                article
            }
        }
    }

    private fun tryFetchContent(article: Article): Article {
        if (article.link.isBlank()) return article

        if (enforceUrlValidation) {
            try {
                rssFetcher.validateFeedUrl(article.link)
            } catch (e: IllegalArgumentException) {
                logger.warn { "[ArticleFetcher] Rejected article URL '${article.link}': ${e.message}" }
                return article
            }
        }

        return try {
            // Strategy 1: markdown.new (purpose-built content extraction)
            val markdown = fetchMarkdownNew(article.link)
            if (markdown != null) {
                logger.info { "[ArticleFetcher] Enriched via markdown.new '${article.link}' (${markdown.length} chars)" }
                return article.copy(description = markdown.take(maxContentLength))
            }

            // Strategy 2: Fallback to Jsoup HTML extraction
            logger.info { "[ArticleFetcher] Falling back to Jsoup for '${article.link}'" }
            val html = fetchHtml(article.link) ?: return article
            val extracted = extractText(html, article.link)
            if (extracted.isNullOrBlank()) {
                logger.warn { "[ArticleFetcher] No usable content extracted from '${article.link}'" }
                article
            } else {
                logger.info { "[ArticleFetcher] Enriched via Jsoup '${article.link}' (${extracted.length} chars)" }
                article.copy(description = extracted.take(maxContentLength))
            }
        } catch (e: Exception) {
            logger.error(e) { "[ArticleFetcher] Failed to fetch '${article.link}'" }
            article
        }
    }

    /**
     * Attempts to fetch article content as clean markdown via markdown.new.
     * Returns null on any failure, allowing the caller to fall back to Jsoup.
     */
    private fun fetchMarkdownNew(url: String): String? {
        return try {
            val requestUrl = "${MARKDOWN_NEW_BASE_URL}${url}?retain_images=false"
            val request = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", "Mozilla/5.0 (compatible; RssNewsBot/1.0)")
                .get()
                .build()

            markdownNewClient.newCall(request).execute().use { response ->
                val remaining = response.header("x-rate-limit-remaining")
                if (remaining != null) {
                    logger.debug { "[ArticleFetcher] markdown.new rate limit remaining: $remaining" }
                }
                if (!response.isSuccessful) {
                    logger.warn { "[ArticleFetcher] markdown.new HTTP ${response.code} for $url" }
                    return null
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    logger.warn { "[ArticleFetcher] markdown.new returned empty body for $url" }
                    return null
                }
                body
            }
        } catch (e: Exception) {
            logger.error(e) { "[ArticleFetcher] markdown.new failed for $url" }
            null
        }
    }

    private fun fetchHtml(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; RssNewsBot/1.0)")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "[ArticleFetcher] HTTP ${response.code} for $url" }
                return null
            }
            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.contains("text/html", ignoreCase = true)) {
                logger.warn { "[ArticleFetcher] Skipping non-HTML content-type '$contentType' for $url" }
                return null
            }
            response.body?.string()
        }
    }

    /**
     * Extracts clean readable text from HTML.
     *
     * Strategy:
     * 1. Strip noise elements (scripts, nav, ads, etc.)
     * 2. Try each [ARTICLE_SELECTORS] in order — use first that yields ≥ [MIN_SELECTOR_TEXT_LENGTH] chars
     * 3. Fall back to collecting all `<p>` tags with non-trivial text
     * 4. Return null if nothing substantial found
     */
    internal fun extractText(html: String, baseUrl: String): String? {
        val doc: Document = Jsoup.parse(html, baseUrl)

        // Remove boilerplate noise before extraction
        doc.select("script, style, nav, header, footer, aside, .ad, .advertisement, .cookie-banner, [aria-hidden=true]").remove()

        // Try semantic article selectors first
        for (selector in ARTICLE_SELECTORS) {
            val element = doc.selectFirst(selector) ?: continue
            val text = element.text().trim()
            if (text.length >= MIN_SELECTOR_TEXT_LENGTH) {
                return text
            }
        }

        // Fallback: collect paragraphs with meaningful text
        val paragraphText = doc.select("p")
            .map { it.text().trim() }
            .filter { it.length > 30 }
            .joinToString(" ")

        return paragraphText.takeIf { it.length >= MIN_SELECTOR_TEXT_LENGTH }
    }
}
