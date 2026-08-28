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
import metifikys.model.HtmlText
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}

/**
 * Queue-model RSS fetcher. Every feed gets ONE HTTP attempt per pass on a shared bounded
 * pool; a retryable failure (408/429/5xx/transport) re-enters the queue after a delay
 * instead of sleeping in place, so a dead feed never blocks the feeds behind it. Rate
 * limiting is handled per HOST through [HostThrottle]: a 429 cools the whole host down
 * and teaches it a request spacing, so feeds sharing a host (e.g. reddit subreddits
 * across categories) stop hammering it in lockstep. The whole stage is capped by a
 * wall-clock deadline — feeds still failing at the deadline are skipped for this cycle
 * (link-dedup catches their articles up next cycle).
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
    private val fetchDeadlineMs: Long = 240_000L,
    /**
     * Per-host gate shared by ALL feeds and categories: a 429 cools the whole host down
     * and teaches it a request spacing. Self-tuning — injectable only for tests.
     */
    private val hostThrottle: HostThrottle = HostThrottle()
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

    /**
     * Cache validators a feed handed us on its last successful 200, replayed as `If-None-Match` /
     * `If-Modified-Since` on the next attempt. Process-lifetime and in-memory on purpose — the
     * same tradeoff [HostThrottle] makes: the bot runs continuously, so a restart costing one
     * full re-fetch per feed is not worth a DB column.
     */
    private data class FeedValidators(val eTag: String?, val lastModified: String?)

    private val feedValidators = ConcurrentHashMap<String, FeedValidators>()

    /** Result of a single HTTP attempt against one feed. */
    private sealed interface FetchOutcome {
        data class Success(val articles: List<Article>) : FetchOutcome

        /** HTTP 304 — the feed is byte-for-byte what we already parsed; nothing new to ingest. */
        object NotModified : FetchOutcome

        data class Retryable(
            val reason: String,
            val retryAfterMs: Long?,
            val cause: Exception? = null,
            /** True for HTTP 429 — the signal that must throttle the whole HOST, not just this feed. */
            val rateLimited: Boolean = false
        ) : FetchOutcome
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
     * the queue") and no thread ever sleeps. Before touching the network the attempt asks
     * the per-host gate: a host in cooldown / spacing-busy re-queues the attempt WITHOUT
     * consuming it, so only real HTTP attempts count toward [maxAttempts].
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
            val host = hostKey(feedConfig.url)
            val gateDelayMs = hostThrottle.acquireDelayMs(host)
            if (gateDelayMs > 0) {
                val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
                if (gateDelayMs >= remainingMs) {
                    logger.warn { "Host $host is cooling down past the stage deadline — ${feedConfig.url} deferred to next cycle" }
                    result.complete(emptyList())
                } else {
                    logger.debug { "Host $host throttled — ${feedConfig.url} re-queued in ${gateDelayMs / 1000}s (attempt $attempt not consumed)" }
                    scheduleAttempt(feedConfig, category, attempt, withJitter(gateDelayMs), deadlineNanos, result)
                }
                return@schedule
            }
            when (val outcome = fetchFeedOnce(feedConfig, category)) {
                is FetchOutcome.Success -> {
                    hostThrottle.onSuccess(host)
                    result.complete(outcome.articles)
                }
                // 304: the feed is unchanged since our last successful parse. Its entries are
                // already in the DB, so an empty result is exactly what link-dedup would have
                // produced anyway — and it still counts as a healthy response for the host.
                FetchOutcome.NotModified -> {
                    hostThrottle.onSuccess(host)
                    result.complete(emptyList())
                }
                is FetchOutcome.Fatal -> {
                    // HTTP itself succeeded (the failure was parse-side), so it still counts
                    // as a success for the host's rate limiting.
                    hostThrottle.onSuccess(host)
                    result.complete(emptyList())
                }
                is FetchOutcome.Retryable -> {
                    val hostCooldownMs = if (outcome.rateLimited) {
                        val cooldown = hostThrottle.onRateLimit(host, outcome.retryAfterMs)
                        logger.warn { "HTTP 429 from $host — cooling the whole host down for ${cooldown / 1000}s" }
                        cooldown
                    } else null
                    val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
                    val nextDelay = if (attempt >= maxAttempts) null
                        else nextRetryDelayMs(hostCooldownMs ?: outcome.retryAfterMs, retryDelayMs, remainingMs)
                    if (nextDelay == null) {
                        if (outcome.cause != null) {
                            logger.error(outcome.cause) { "${outcome.reason} from ${feedConfig.url} (attempt $attempt/$maxAttempts) — deferred to next cycle" }
                        } else {
                            logger.warn { "${outcome.reason} from ${feedConfig.url} (attempt $attempt/$maxAttempts) — deferred to next cycle" }
                        }
                        result.complete(emptyList())
                    } else {
                        logger.warn { "${outcome.reason} from ${feedConfig.url} (attempt $attempt/$maxAttempts), requeued in ${nextDelay / 1000}s" }
                        scheduleAttempt(feedConfig, category, attempt + 1, withJitter(nextDelay), deadlineNanos, result)
                    }
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Adds 0–20% random jitter so same-host feeds never re-fire in a synchronized burst. */
    private fun withJitter(delayMs: Long): Long =
        delayMs + ThreadLocalRandom.current().nextLong(delayMs / 5 + 1)

    /** Throttle key for a feed URL: lowercase authority (host:port). Falls back to the raw URL. */
    private fun hostKey(url: String): String = HostThrottle.hostKey(url)

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
            val cached = feedValidators[url]
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; RssNewsBot/1.0)")
                // Conditional GET: a feed that hasn't changed since the last cycle answers 304 with
                // an empty body — no transfer, no XML parse, and one less full request counted
                // against the host's rate limit (the main source of the 429s HostThrottle absorbs).
                cached?.eTag?.let { setRequestProperty("If-None-Match", it) }
                cached?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                logger.debug { "[RSS] $url → 304 Not Modified, skipping parse" }
                return FetchOutcome.NotModified
            }
            // BUG-006: retry the whole transient-error family (408 timeout, 429 rate-limit,
            // every 5xx), not just 502. Honor a Retry-After header when the server sends one.
            if (isRetryableStatus(responseCode)) {
                return FetchOutcome.Retryable(
                    reason = "Got HTTP $responseCode",
                    retryAfterMs = parseRetryAfterMs(connection.getHeaderField("Retry-After")),
                    rateLimited = responseCode == 429
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
            // Remember the validators only after a clean parse, so a 304 can never stand in for
            // a response we failed to turn into articles.
            rememberValidators(url, connection)
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

    /**
     * Stores this response's `ETag` / `Last-Modified` for the next cycle's conditional GET.
     * A response carrying neither drops any previously cached pair, so we never replay a stale
     * validator against a server that stopped sending them.
     */
    private fun rememberValidators(url: String, connection: HttpURLConnection) {
        val eTag = connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
        val lastModified = connection.getHeaderField("Last-Modified")?.takeIf { it.isNotBlank() }
        if (eTag == null && lastModified == null) {
            feedValidators.remove(url)
        } else {
            feedValidators[url] = FeedValidators(eTag, lastModified)
        }
    }

    /** Maps a parsed feed's entries to [Article]s (unchanged from the sequential fetcher). */
    private fun toArticles(feed: SyndFeed, feedConfig: FeedConfig, category: String): List<Article> {
        val url = feedConfig.url
        var rewritten = 0
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
                    // BUG-020: canonicalize the link at ingestion so utm/fbclid/trailing-slash
                    // variants of the same story dedup to one row.
                    val permalink = LinkNormalizer.normalize(entry.link)
                    // A reddit link post carries no content of its own — only a boilerplate card
                    // with a `[link]` anchor to the real article. Follow it, so the story (and not
                    // reddit's markup) is what gets enriched, deduped and published. The image is
                    // taken from the ORIGINAL description: reddit's preview thumbnail is the one
                    // piece of the card worth keeping.
                    val reddit = RedditLinkExtractor.rewrite(permalink, description)
                    if (reddit != null) rewritten++
                    Article(
                        category = category,
                        title = entry.title ?: "",
                        link = reddit?.link ?: permalink,
                        // Store plain text: feed HTML (t.me mirrors, WordPress excerpts, reddit
                        // self-post bodies) otherwise eats a large share of the per-article
                        // prompt budget downstream. Image extraction below still sees the RAW
                        // description — the <img>/thumbnail lives in the markup being stripped.
                        description = HtmlText.strip(reddit?.description ?: description),
                        pubDate = pubDate,
                        imageUrl = extractImageUrl(entry, description),
                        // A rewritten entry has no body of its own, so the page behind the
                        // outbound link is the only source of text — fetch it regardless of
                        // what the feed's own `fetchFullContent` says.
                        fetchFullContent = feedConfig.fetchFullContent || reddit != null,
                        summarize = feedConfig.summarize
                    )
                } catch (e: Exception) {
                    logger.warn { "Skipping malformed entry in $url: ${e.message}" }
                    null
                }
            }
        val withImage = articles.count { it.imageUrl != null }
        logger.info { "[RSS] $url → ${articles.size} entries, $withImage with images, ${articles.size - withImage} without." }
        if (rewritten > 0) {
            logger.info { "[RSS] $url → $rewritten reddit link post(s) repointed at their outbound article." }
        }
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
