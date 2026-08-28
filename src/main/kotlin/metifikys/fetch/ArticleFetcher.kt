package metifikys.fetch

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.model.Article
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min

private val logger = KotlinLogging.logger {}

/**
 * Fetches and extracts readable text content from article web pages.
 *
 * Used to enrich RSS feed items that have empty or very short descriptions by fetching
 * the linked article URL and extracting the main text body via jsoup.
 *
 * SSRF protection: reuses [RssFetcher.validateFeedUrl] before any network call.
 * All failures are silent — the original article is always returned unchanged on any error.
 *
 * Rate limiting: every outbound request passes through the SAME [HostThrottle] instance the RSS
 * fetcher uses. Article pages and feeds routinely live on one host (reddit, a publisher's own
 * domain), so without a shared gate this class would fetch [FETCH_PARALLELISM] pages at once
 * against a host the fetcher is carefully backing off from — and re-earn the 429 the throttle
 * just absorbed.
 */
class ArticleFetcher(
    private val rssFetcher: RssFetcher,
    private val enforceUrlValidation: Boolean = true,
    /** Articles with descriptions shorter than this are eligible for content fetching. */
    private val minDescriptionLength: Int = 50,
    /** Maximum characters of extracted text to store as the enriched description. */
    private val maxContentLength: Int = 1500,
    /**
     * Per-host gate shared with [RssFetcher]. Defaults to a private instance so standalone /
     * test construction keeps working; production passes the fetcher's own so feed and page
     * requests charge one budget.
     */
    private val hostThrottle: HostThrottle = HostThrottle()
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

        /** BUG-021: max concurrent page fetches during enrichment. */
        private const val FETCH_PARALLELISM = 8

        /**
         * Site chrome that jsoup/markdown.new extraction drags in from the page shell —
         * login nudges, share bars, "follow us" plugs, ad markers. It sits at the START of
         * the extracted text on the worst hosts (95% of wired.com rows began with the
         * save-story widget; 81% of XDA rows with the sign-in nudge), so it eats the most
         * valuable chars of the [maxContentLength] budget. Removed BEFORE truncation.
         * Literal, host-agnostic patterns only — when in doubt, leave the text alone.
         */
        private val CHROME_PATTERNS = listOf(
            // wired.com: repeated "Comment Loader Save StorySave this story" prefix
            Regex("""(?:Comment Loader\s*)*(?:Save StorySave this story\s*)+"""),
            // xda-developers.com
            Regex("""Sign in to your XDA account\s*"""),
            // sciencenews.org share bar: "Share this: … (Opens in new window) … Print"
            Regex("""Share this:.{0,400}?\(Opens in new window\)\s*Print\b""", RegexOption.DOT_MATCHES_ALL),
            // sud.ua
            Regex("""Слідкуйте за актуальними новинами у соцмережах SUD\.UA\s*"""),
            Regex("""Тільки актуальне: читайте SUD\.UA у Telegram\s*"""),
            Regex("""Підписуйтесь на наш Telegram-канал[^.!?]{0,120}"""),
            // pravda.com.ua / eurointegration
            Regex("""Підписуйся на [«"]Європейську правду[»"]!?\s*"""),
            Regex("""Якщо ви помітили помилку, виділіть необхідний текст і натисніть Ctrl ?\+ ?Enter[^.!?]*[.!?]?"""),
            Regex("""Шановні читачі, просимо дотримуватись Правил коментування\s*"""),
            Regex("""\bРеклама:\s*"""),
        )

        /** Meta-tag fallbacks tried when body extraction yields nothing; first non-trivial wins. */
        private val META_DESCRIPTION_SELECTORS = listOf(
            "meta[property=og:description]",
            "meta[name=twitter:description]",
            "meta[name=description]"
        )

        /** A meta description shorter than this is a stub ("Read more…") — not worth storing. */
        private const val MIN_META_DESCRIPTION_LENGTH = 40

        private val WHITESPACE_RUNS = Regex("""\s{2,}""")

        /** BUG-021: hard cap on total enrichment wall-clock per call, so the scheduler thread
         * is never blocked for the worst-case N × 20s. Articles not fetched in time are returned
         * unchanged (enrichment is best-effort by contract). */
        private const val FETCH_BUDGET_MILLIS = 90_000L

        /**
         * Longest a worker will sit waiting on [HostThrottle] before giving up on one article.
         * A cooling-down host must not park a pool slot for the whole [FETCH_BUDGET_MILLIS] —
         * enrichment is best-effort, and the article is re-eligible next cycle.
         */
        private const val MAX_HOST_WAIT_MILLIS = 30_000L
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
     * by fetching their linked URL and extracting the article body text, and — for articles
     * [needsPreviewImage] selects — fills [Article.imageUrl] from the page's Open Graph /
     * Twitter Card tags in the SAME pass.
     *
     * Both jobs are done together because they want the same HTML: run as two passes (the old
     * `enrich(...)` then `fillPreviewImages(...)` sequence) an article needing both would fetch
     * its page twice whenever markdown.new was unavailable, parse it twice with jsoup, and spin
     * up two thread pools. The caller supplies [needsPreviewImage] so this class stays decoupled
     * from config (it is the caller that knows which categories have images enabled).
     *
     * Articles that already have sufficient description, or where neither job is requested,
     * are returned unchanged. Failures are always silent.
     *
     * @return New list with enriched descriptions / preview images where applicable;
     *   original articles otherwise.
     */
    fun enrich(
        articles: List<Article>,
        needsPreviewImage: (Article) -> Boolean = { false }
    ): List<Article> =
        mapBounded(articles) { article ->
            val wantsContent = article.fetchFullContent
            val wantsImage = article.imageUrl == null && needsPreviewImage(article)
            when {
                wantsContent && wantsImage -> tryFetchContentAndImage(article)
                wantsContent -> tryFetchContent(article)
                wantsImage -> tryFetchPreviewImage(article)
                else -> article
            }
        }

    /**
     * BUG-021: applies [transform] to each article across a bounded thread pool with a total
     * wall-clock budget, instead of running the network fetches serially on the scheduler thread
     * (worst case N × 20s). Order is preserved. Any article whose transform errors or does not
     * finish within the remaining budget is returned unchanged — enrichment is best-effort.
     */
    private fun mapBounded(articles: List<Article>, transform: (Article) -> Article): List<Article> {
        if (articles.size <= 1) return articles.map(transform)
        val pool = Executors.newFixedThreadPool(min(articles.size, FETCH_PARALLELISM))
        try {
            val futures = articles.map { a -> CompletableFuture.supplyAsync({ transform(a) }, pool) }
            val deadline = System.nanoTime() + FETCH_BUDGET_MILLIS * 1_000_000
            return futures.mapIndexed { i, f ->
                try {
                    val remainingNs = deadline - System.nanoTime()
                    if (remainingNs > 0) f.get(remainingNs, TimeUnit.NANOSECONDS) else articles[i]
                } catch (e: Exception) {
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    logger.debug(e) { "[ArticleFetcher] enrichment fell back to original for ${articles[i].link}" }
                    articles[i]
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Single-fetch path for an article that wants BOTH body text and a preview image.
     * markdown.new is still tried first for the text (it extracts better than our selectors),
     * but the page itself is fetched exactly once and serves both the `og:image` lookup and
     * the text fallback — where the two-pass version fetched and parsed it twice.
     */
    private fun tryFetchContentAndImage(article: Article): Article {
        if (article.link.isBlank() || !passesUrlValidation(article.link)) return article

        return try {
            val markdown = fetchMarkdownNew(article.link)?.let { cleanExtractedText(it) }?.takeIf { it.isNotBlank() }
            val html = fetchHtml(article.link)

            val text = markdown
                ?: html?.let { h ->
                    extractText(h, article.link)?.let { cleanExtractedText(it) }?.takeIf { it.isNotBlank() }
                        ?: extractMetaDescription(h, article.link)
                }
            if (text.isNullOrBlank()) {
                logger.warn { "[ArticleFetcher] No usable content extracted from '${article.link}'" }
            } else {
                val via = if (markdown != null) "markdown.new" else "Jsoup"
                logger.info { "[ArticleFetcher] Enriched via $via '${article.link}' (${text.length} chars)" }
            }

            val image = html?.let { extractOgImage(it, article.link) }?.takeIf { it.isNotBlank() }
            if (image == null) {
                logger.debug { "[ArticleFetcher] No preview image found for '${article.link}'" }
            } else {
                logger.info { "[ArticleFetcher] Preview image for '${article.link}': $image" }
            }

            article.copy(
                description = if (text.isNullOrBlank()) article.description else text.take(maxContentLength),
                imageUrl = image ?: article.imageUrl
            )
        } catch (e: Exception) {
            logFetchFailure(article.link, "content+image", e)
            article
        }
    }

    private fun tryFetchPreviewImage(article: Article): Article {
        if (article.link.isBlank() || !passesUrlValidation(article.link)) return article

        return try {
            val html = fetchHtml(article.link) ?: return article
            val image = extractOgImage(html, article.link)
            if (image.isNullOrBlank()) {
                logger.debug { "[ArticleFetcher] No preview image found for '${article.link}'" }
                article
            } else {
                logger.info { "[ArticleFetcher] Preview image for '${article.link}': $image" }
                article.copy(imageUrl = image)
            }
        } catch (e: Exception) {
            logFetchFailure(article.link, "preview image", e)
            article
        }
    }

    private fun tryFetchContent(article: Article): Article {
        if (article.link.isBlank() || !passesUrlValidation(article.link)) return article

        return try {
            // Strategy 1: markdown.new (purpose-built content extraction)
            val markdown = fetchMarkdownNew(article.link)?.let { cleanExtractedText(it) }?.takeIf { it.isNotBlank() }
            if (markdown != null) {
                logger.info { "[ArticleFetcher] Enriched via markdown.new '${article.link}' (${markdown.length} chars)" }
                return article.copy(description = markdown.take(maxContentLength))
            }

            // Strategy 2: Fallback to Jsoup HTML extraction; when body extraction yields
            // nothing, fall through to the page's own meta description (paywall/JS-shell
            // pages usually still serve the lede there).
            logger.info { "[ArticleFetcher] Falling back to Jsoup for '${article.link}'" }
            val html = fetchHtml(article.link) ?: return article
            val extracted = extractText(html, article.link)?.let { cleanExtractedText(it) }?.takeIf { it.isNotBlank() }
                ?: extractMetaDescription(html, article.link)?.also {
                    logger.info { "[ArticleFetcher] Body extraction empty — using meta description for '${article.link}'" }
                }
            if (extracted.isNullOrBlank()) {
                logger.warn { "[ArticleFetcher] No usable content extracted from '${article.link}'" }
                article
            } else {
                logger.info { "[ArticleFetcher] Enriched via Jsoup '${article.link}' (${extracted.length} chars)" }
                article.copy(description = extracted.take(maxContentLength))
            }
        } catch (e: Exception) {
            logFetchFailure(article.link, "content", e)
            article
        }
    }

    /** SSRF guard shared by every fetch path. Returns false (and logs) when the URL is rejected. */
    private fun passesUrlValidation(url: String): Boolean {
        if (!enforceUrlValidation) return true
        return try {
            rssFetcher.validateFeedUrl(url)
            true
        } catch (e: IllegalArgumentException) {
            logger.warn { "[ArticleFetcher] Rejected article URL '$url': ${e.message}" }
            false
        }
    }

    /**
     * Worker interrupted (cycle deadline / pool shutdownNow) — okhttp surfaces it as
     * InterruptedIOException with the flag still set. Cancellation, not a fetch failure.
     */
    private fun logFetchFailure(url: String, what: String, e: Exception) {
        if (Thread.currentThread().isInterrupted) {
            logger.debug { "[ArticleFetcher] $what fetch cancelled for '$url'" }
        } else {
            logger.error(e) { "[ArticleFetcher] Failed to fetch $what for '$url'" }
        }
    }

    /**
     * Blocks until the shared [HostThrottle] lets this worker hit [url]'s host, giving up after
     * [MAX_HOST_WAIT_MILLIS]. Unlike the RSS fetcher — which re-queues onto a scheduled pool —
     * enrichment runs on short-lived workers under their own budget, so a bounded wait in place
     * is both simpler and adequate.
     *
     * @return true when a slot was reserved and the caller MUST perform the request.
     */
    private fun awaitHostSlot(url: String): Boolean {
        val host = HostThrottle.hostKey(url)
        var waitedMs = 0L
        while (true) {
            val delayMs = hostThrottle.acquireDelayMs(host)
            if (delayMs <= 0L) return true
            if (waitedMs + delayMs > MAX_HOST_WAIT_MILLIS) {
                logger.debug { "[ArticleFetcher] $host throttled past the per-article budget — skipping $url" }
                return false
            }
            try {
                Thread.sleep(delayMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            waitedMs += delayMs
        }
    }

    /**
     * Feeds a response back into the shared per-host gate. A 429 cools the whole host down —
     * the same cooldown the RSS fetcher then obeys for that host's feeds — and returns false so
     * the caller abandons this article rather than reading a rate-limit body.
     */
    private fun recordHostOutcome(url: String, code: Int, retryAfter: String?): Boolean {
        val host = HostThrottle.hostKey(url)
        if (code == 429) {
            val cooldownMs = hostThrottle.onRateLimit(host, RssFetcher.parseRetryAfterMs(retryAfter))
            logger.warn { "[ArticleFetcher] HTTP 429 from $host — cooling the whole host down for ${cooldownMs / 1000}s" }
            return false
        }
        hostThrottle.onSuccess(host)
        return true
    }

    /**
     * Attempts to fetch article content as clean markdown via markdown.new.
     * Returns null on any failure, allowing the caller to fall back to Jsoup.
     */
    private fun fetchMarkdownNew(url: String): String? {
        val requestUrl = "${MARKDOWN_NEW_BASE_URL}${url}?retain_images=false"
        // markdown.new is a shared third-party proxy with its own rate limit — gate it like any
        // other host so a 429 there backs every worker off instead of only the one that saw it.
        if (!awaitHostSlot(requestUrl)) return null
        return try {
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
                if (!recordHostOutcome(requestUrl, response.code, response.header("Retry-After"))) return null
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
            if (Thread.currentThread().isInterrupted()) {
                logger.debug { "[ArticleFetcher] markdown.new cancelled for $url" }
            } else {
                logger.error(e) { "[ArticleFetcher] markdown.new failed for $url" }
            }
            null
        }
    }

    private fun fetchHtml(url: String): String? {
        if (!awaitHostSlot(url)) return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; RssNewsBot/1.0)")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!recordHostOutcome(url, response.code, response.header("Retry-After"))) return null
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
     * Scrubs known site chrome (see [CHROME_PATTERNS]) out of extracted article text and
     * collapses the whitespace the removals leave behind. Applied to BOTH extraction paths
     * (markdown.new and jsoup) before the [maxContentLength] cut, so the budget goes to
     * article content rather than page shell.
     */
    internal fun cleanExtractedText(text: String): String {
        var out = text
        for (pattern in CHROME_PATTERNS) out = pattern.replace(out, " ")
        return out.replace(WHITESPACE_RUNS, " ").trim()
    }

    /**
     * Last-resort text source when body extraction yields nothing: the page's own
     * `og:description` / `twitter:description` / `meta description`. Paywalled and
     * JS-shell pages (Reuters/Bloomberg teasers behind reddit rewrites, YouTube watch
     * pages) usually still serve the lede there, which beats storing an empty
     * description that leaves the digest LLM with only a title.
     */
    internal fun extractMetaDescription(html: String, baseUrl: String): String? {
        val doc: Document = Jsoup.parse(html, baseUrl)
        for (selector in META_DESCRIPTION_SELECTORS) {
            val content = doc.selectFirst(selector)?.attr("content")?.trim().orEmpty()
            if (content.length >= MIN_META_DESCRIPTION_LENGTH) return cleanExtractedText(content)
        }
        return null
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

    /**
     * Extracts the page's link-preview image from Open Graph / Twitter Card meta tags.
     *
     * Tried in priority order: `og:image`, `og:image:secure_url`, `twitter:image`,
     * `twitter:image:src`. The first tag with a non-blank `content` wins. Relative URLs are
     * resolved against [baseUrl] via jsoup's `absUrl`, falling back to the raw attribute.
     *
     * @return Absolute image URL, or null when no usable preview image meta tag is present.
     */
    internal fun extractOgImage(html: String, baseUrl: String): String? {
        val doc: Document = Jsoup.parse(html, baseUrl)

        val selectors = listOf(
            "meta[property=og:image]",
            "meta[property=og:image:secure_url]",
            "meta[name=twitter:image]",
            "meta[name=twitter:image:src]"
        )

        for (selector in selectors) {
            val element = doc.selectFirst(selector) ?: continue
            val resolved = element.absUrl("content").takeIf { it.isNotBlank() }
            val raw = element.attr("content").trim().takeIf { it.isNotBlank() }
            val image = resolved ?: raw ?: continue
            return image
        }

        return null
    }
}
