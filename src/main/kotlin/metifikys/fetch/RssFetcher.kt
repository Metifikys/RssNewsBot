package metifikys.fetch

import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.CategoryConfig
import metifikys.config.FeedConfig
import metifikys.model.Article
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * Queue-model RSS fetcher. Every feed gets ONE HTTP attempt per pass on a shared bounded
 * pool; a retryable failure (408/429/5xx/transport) re-enters the queue after a delay
 * instead of sleeping in place, so a dead feed never blocks the feeds behind it. The whole
 * stage is capped by a wall-clock deadline — feeds still failing at the deadline are
 * skipped for this cycle (link-dedup catches their articles up next cycle).
 */
class RssFetcher(
    /**
     * Controls whether SSRF URL validation is enforced.
     * Always `true` in production; can be set to `false` in tests that spin up
     * a local HTTP server to serve fixture RSS feeds.
     */
    private val enforceUrlValidation: Boolean = true,
    /** Total attempts per feed per cycle: the first try + requeued retries. */
    private val maxAttempts: Int = 3,
    /** Fallback delay before a retryable feed re-enters the queue; a server Retry-After wins. */
    private val retryDelayMs: Long = 15_000L,
    /**
     * When true, RFC-1918 / loopback / link-local hosts are accepted. Intended for
     * operators who self-host RSS sources on their LAN (e.g. an RSSHub instance).
     */
    private val allowPrivateHosts: Boolean = false,
    /** Max simultaneous HTTP requests across ALL categories (feeds usually share one RSSHub host). */
    private val maxConcurrentFetches: Int = 3,
    /** Wall-clock budget for one fetch stage started via [fetchAll]/[fetchFeed]/[newFetchDeadline]. */
    private val fetchDeadlineMs: Long = 240_000L
) {

    companion object {
        /** Allowed URL schemes for RSS feeds. */
        private val ALLOWED_SCHEMES = setOf("http", "https")

        /** Upper bound on a server-supplied `Retry-After` wait, so a broken/hostile header can't stall a cycle. */
        private const val MAX_RETRY_AFTER_MS = 300_000L

        /** Extra wait past the deadline so in-flight tasks can finish their deadline check and complete. */
        private const val DEADLINE_GRACE_MS = 2_000L

        /** HTTP statuses worth retrying: request timeout (408), rate-limit (429), and any server-side 5xx. */
        internal fun isRetryableStatus(code: Int): Boolean =
            code == 408 || code == 429 || code in 500..599

        /**
         * Parses a `Retry-After` header (delta-seconds form) into milliseconds, clamped to
         * [MAX_RETRY_AFTER_MS]. Returns null when the header is absent or in HTTP-date form
         * (rare for feeds) so the caller falls back to its fixed retry delay.
         */
        internal fun parseRetryAfterMs(header: String?): Long? {
            val seconds = header?.trim()?.takeUnless { it.isEmpty() }?.toLongOrNull() ?: return null
            return (seconds.coerceAtLeast(0) * 1000).coerceAtMost(MAX_RETRY_AFTER_MS)
        }

        /**
         * Delay before the next queue pass for a retryable feed: server Retry-After when
         * present (already clamped), else [fallbackDelayMs]. Returns null when the wait
         * would overrun the remaining stage budget — the feed is then deferred to the
         * next cycle instead of burning a pool slot on a doomed retry.
         */
        internal fun nextRetryDelayMs(retryAfterMs: Long?, fallbackDelayMs: Long, remainingMs: Long): Long? {
            val delay = (retryAfterMs ?: fallbackDelayMs).coerceAtMost(MAX_RETRY_AFTER_MS)
            return if (delay >= remainingMs) null else delay
        }

        /**
         * Regex matching loopback, link-local, and RFC-1918 private IP ranges.
         * Blocks SSRF attempts pointing at internal services.
         */
        private val PRIVATE_HOST_REGEX = Regex(
            "^(localhost" +
            "|127\\..*" +
            "|10\\..*" +
            "|192\\.168\\..*" +
            "|172\\.(1[6-9]|2[0-9]|3[01])\\..*" +
            "|169\\.254\\..*" +
            ")$",
            RegexOption.IGNORE_CASE
        )
    }

    /** Result of a single HTTP attempt against one feed. */
    private sealed interface FetchOutcome {
        data class Success(val articles: List<Article>) : FetchOutcome
        data class Retryable(val reason: String, val retryAfterMs: Long?, val cause: Exception? = null) : FetchOutcome
        data class Fatal(val reason: String) : FetchOutcome
    }

    // Lazy so the validation-only instance embedded in ArticleFetcher never spins threads.
    private val fetchPoolDelegate = lazy {
        ScheduledThreadPoolExecutor(maxConcurrentFetches) { r ->
            Thread(r, "rss-fetch").apply { isDaemon = true }
        }
    }
    private val fetchPool: ScheduledThreadPoolExecutor by fetchPoolDelegate

    /** Stops the fetch pool (if it ever started). In-flight attempts are interrupted. */
    fun shutdown() {
        if (fetchPoolDelegate.isInitialized()) {
            fetchPoolDelegate.value.shutdownNow()
        }
    }

    /** Deadline (in [System.nanoTime] terms) for a fetch stage starting now. */
    fun newFetchDeadline(): Long = System.nanoTime() + fetchDeadlineMs * 1_000_000

    /**
     * Fetches every category's feeds through the shared bounded pool under ONE stage
     * deadline. Kept for compatibility; the per-category pipeline uses [fetchCategory].
     */
    fun fetchAll(categories: Map<String, CategoryConfig>): List<Article> {
        val deadlineNanos = newFetchDeadline()
        val futures = categories.flatMap { (categoryName, categoryConfig) ->
            @Suppress("UNCHECKED_CAST")
            scheduleFeeds(categoryName, categoryConfig.feeds as List<FeedConfig?>, deadlineNanos)
        }
        return awaitFetches(futures, deadlineNanos, "all categories")
    }

    /**
     * Fetches one category's feeds through the shared pool. Returns whatever succeeded by
     * [deadlineNanos]; feeds still failing then yield nothing and are retried next cycle.
     */
    fun fetchCategory(category: String, feeds: List<FeedConfig?>, deadlineNanos: Long): List<Article> {
        return awaitFetches(scheduleFeeds(category, feeds, deadlineNanos), deadlineNanos, category)
    }

    /** Single-feed convenience wrapper (used by tests and ad-hoc callers). */
    fun fetchFeed(feedConfig: FeedConfig, category: String): List<Article> {
        return fetchCategory(category, listOf(feedConfig), newFetchDeadline())
    }

    /** Fans the feeds into the pool (first attempt immediately) and returns their futures. */
    private fun scheduleFeeds(
        category: String,
        feeds: List<FeedConfig?>,
        deadlineNanos: Long
    ): List<CompletableFuture<List<Article>>> {
        return feeds.mapNotNull { feedConfig ->
            if (feedConfig == null) {
                logger.warn { "Skipping null feed entry in category '$category' — check config.yaml for empty list items" }
                return@mapNotNull null
            }
            // SSRF guard: validate URL scheme and host before making any network call
            if (enforceUrlValidation) {
                try {
                    validateFeedUrl(feedConfig.url)
                } catch (e: IllegalArgumentException) {
                    logger.warn { "Rejected feed URL '${feedConfig.url}': ${e.message}" }
                    return@mapNotNull null
                }
            }
            val result = CompletableFuture<List<Article>>()
            scheduleAttempt(feedConfig, category, attempt = 1, delayMs = 0, deadlineNanos = deadlineNanos, result = result)
            result
        }
    }

    /**
     * Schedules one attempt for one feed. On a retryable failure the feed re-enqueues
     * itself with a delay — the pool slot frees up immediately for other feeds ("back of
     * the queue") and no thread ever sleeps.
     */
    private fun scheduleAttempt(
        feedConfig: FeedConfig,
        category: String,
        attempt: Int,
        delayMs: Long,
        deadlineNanos: Long,
        result: CompletableFuture<List<Article>>
    ) {
        fetchPool.schedule({
            if (result.isDone) return@schedule
            if (System.nanoTime() >= deadlineNanos) {
                logger.warn { "Fetch deadline reached before attempt $attempt for ${feedConfig.url} — deferred to next cycle" }
                result.complete(emptyList())
                return@schedule
            }
            when (val outcome = fetchFeedOnce(feedConfig, category)) {
                is FetchOutcome.Success -> result.complete(outcome.articles)
                is FetchOutcome.Fatal -> result.complete(emptyList())
                is FetchOutcome.Retryable -> {
                    val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
                    val nextDelay = if (attempt >= maxAttempts) null
                        else nextRetryDelayMs(outcome.retryAfterMs, retryDelayMs, remainingMs)
                    if (nextDelay == null) {
                        if (outcome.cause != null) {
                            logger.error(outcome.cause) { "${outcome.reason} from ${feedConfig.url} (attempt $attempt/$maxAttempts) — deferred to next cycle" }
                        } else {
                            logger.warn { "${outcome.reason} from ${feedConfig.url} (attempt $attempt/$maxAttempts) — deferred to next cycle" }
                        }
                        result.complete(emptyList())
                    } else {
                        logger.warn { "${outcome.reason} from ${feedConfig.url} (attempt $attempt/$maxAttempts), requeued in ${nextDelay / 1000}s" }
                        scheduleAttempt(feedConfig, category, attempt + 1, nextDelay, deadlineNanos, result)
                    }
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Waits for the fanned-out fetches until the deadline and gathers partial results. */
    private fun awaitFetches(
        futures: List<CompletableFuture<List<Article>>>,
        deadlineNanos: Long,
        label: String
    ): List<Article> {
        if (futures.isEmpty()) return emptyList()
        val waitMs = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0) + DEADLINE_GRACE_MS
        try {
            CompletableFuture.allOf(*futures.toTypedArray()).get(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            logger.warn { "[Fetch:$label] deadline expired with fetches still in flight — deferring stragglers to next cycle" }
            futures.forEach { it.complete(emptyList()) }
        } catch (e: InterruptedException) {
            // Shutdown: restore the flag and hand back whatever already finished.
            Thread.currentThread().interrupt()
            futures.forEach { it.complete(emptyList()) }
        } catch (e: Exception) {
            // Individual fetch tasks never complete their future exceptionally; keep the guard anyway.
            logger.error(e) { "[Fetch:$label] unexpected failure while waiting for fetches" }
        }
        return futures.flatMap { it.getNow(emptyList()) }
    }

    /** ONE HTTP attempt against one feed. Never sleeps, never loops. */
    private fun fetchFeedOnce(feedConfig: FeedConfig, category: String): FetchOutcome {
        val url = feedConfig.url
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; RssNewsBot/1.0)")
            }

            val responseCode = connection.responseCode
            // BUG-006: retry the whole transient-error family (408 timeout, 429 rate-limit,
            // every 5xx), not just 502. Honor a Retry-After header when the server sends one.
            if (isRetryableStatus(responseCode)) {
                return FetchOutcome.Retryable(
                    reason = "Got HTTP $responseCode",
                    retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                )
            }

            val contentType = connection.contentType
            // BUG-006: open the input stream OUTSIDE the parse try — an IOException while
            // opening the stream is a transient transport error and must reach the retry
            // path, not be swallowed as an unrecoverable parse failure.
            val stream = connection.inputStream
            val input = SyndFeedInput().apply { isAllowDoctypes = false }
            val feed = try {
                input.build(XmlReader(stream, contentType, true))
            } catch (parseEx: Exception) {
                // Parser-side failures (malformed XML, Rome bugs like NPE in MediaModuleParser
                // when a <media:thumbnail> lacks a `url` attribute) won't recover on retry.
                logger.warn(parseEx) { "Failed to parse RSS from $url — skipping this cycle" }
                return FetchOutcome.Fatal("parse failure")
            }
            return FetchOutcome.Success(toArticles(feed, feedConfig, category))
        } catch (e: Exception) {
            return FetchOutcome.Retryable(
                reason = "Failed to fetch feed (${e.javaClass.simpleName}: ${e.message})",
                retryAfterMs = null,
                cause = e
            )
        } finally {
            connection?.disconnect()
        }
    }

    /** Maps a parsed feed's entries to [Article]s (unchanged from the sequential fetcher). */
    private fun toArticles(feed: SyndFeed, feedConfig: FeedConfig, category: String): List<Article> {
        val url = feedConfig.url
        val articles = feed.entries
            .filter { it.link?.isNotBlank() == true }
            .mapNotNull { entry ->
                try {
                    val description = entry.description?.value
                        ?: entry.contents.firstOrNull()?.value
                        ?: ""
                    val pubDate = entry.publishedDate
                        ?.toInstant()
                        ?.atZone(ZoneId.systemDefault())
                        ?.toLocalDateTime()
                        ?: LocalDateTime.now()
                    Article(
                        category = category,
                        title = entry.title ?: "",
                        // BUG-020: canonicalize the link at ingestion so utm/fbclid/trailing-slash
                        // variants of the same story dedup to one row.
                        link = LinkNormalizer.normalize(entry.link),
                        description = description,
                        pubDate = pubDate,
                        imageUrl = extractImageUrl(entry, description),
                        fetchFullContent = feedConfig.fetchFullContent,
                        summarize = feedConfig.summarize
                    )
                } catch (e: Exception) {
                    logger.warn { "Skipping malformed entry in $url: ${e.message}" }
                    null
                }
            }
        val withImage = articles.count { it.imageUrl != null }
        logger.info { "[RSS] $url → ${articles.size} entries, $withImage with images, ${articles.size - withImage} without." }
        return articles
    }

    internal fun extractImageUrl(entry: SyndEntry, description: String): String? {
        // 1. Enclosure
        entry.enclosures
            ?.firstOrNull { it.type?.startsWith("image/", ignoreCase = true) == true }
            ?.url
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // 2. Media RSS module
        val mediaModule = entry.getModule("http://search.yahoo.com/mrss/") as? MediaEntryModule
        if (mediaModule != null) {
            mediaModule.mediaContents
                ?.firstOrNull { mc ->
                    val medium = mc.medium?.lowercase()
                    val type = mc.type?.lowercase()
                    medium == "image" || (type?.startsWith("image/") == true)
                }
                ?.reference
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }

            mediaModule.metadata?.thumbnail
                ?.firstOrNull()
                ?.url
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        // 3. First <img> in description HTML
        if (description.isNotBlank() && description.contains("<img", ignoreCase = true)) {
            try {
                val src = Jsoup.parseBodyFragment(description)
                    .selectFirst("img[src]")
                    ?.attr("src")
                    ?.trim()
                if (!src.isNullOrBlank()) return src
            } catch (e: Exception) {
                logger.debug(e) { "[RSS image] HTML parse failed for ${entry.link}" }
            }
        }

        logger.debug {
            val encCount = entry.enclosures?.size ?: 0
            val mediaContentsCount = mediaModule?.mediaContents?.size ?: 0
            val mediaThumbsCount = mediaModule?.metadata?.thumbnail?.size ?: 0
            val hasImgTag = description.contains("<img", ignoreCase = true)
            "[RSS image] MISS for ${entry.link}: enclosures=$encCount, mediaContents=$mediaContentsCount, " +
                "mediaThumbs=$mediaThumbsCount, hasImgInDesc=$hasImgTag, descLen=${description.length}"
        }
        return null
    }

    /**
     * Validates that a feed URL is safe to fetch:
     * - Must be parseable as a URL
     * - Scheme must be http or https (blocks file://, ftp://, etc.)
     * - Host must not be a private/loopback address (blocks SSRF)
     *
     * @throws IllegalArgumentException if validation fails
     */
    internal fun validateFeedUrl(url: String) {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed URL: $url")
        }
        require(parsed.protocol in ALLOWED_SCHEMES) {
            "Only http/https feeds are allowed (got '${parsed.protocol}'): $url"
        }
        val host = parsed.host?.lowercase() ?: throw IllegalArgumentException("URL has no host: $url")
        if (!allowPrivateHosts) {
            require(!host.matches(PRIVATE_HOST_REGEX)) {
                "Private or loopback hosts are not allowed: $host"
            }
        }
    }
}
