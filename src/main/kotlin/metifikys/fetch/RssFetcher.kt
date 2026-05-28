package metifikys.fetch

import com.rometools.modules.mediarss.MediaEntryModule
import com.rometools.rome.feed.synd.SyndEntry
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

private val logger = KotlinLogging.logger {}

class RssFetcher(
    /**
     * Controls whether SSRF URL validation is enforced.
     * Always `true` in production; can be set to `false` in tests that spin up
     * a local HTTP server to serve fixture RSS feeds.
     */
    private val enforceUrlValidation: Boolean = true,
    /** Maximum number of retry attempts for transient HTTP errors (e.g. 502). */
    private val maxRetries: Int = 5,
    /** Delay in milliseconds between retry attempts. */
    private val retryDelayMs: Long = 30_000L,
    /**
     * When true, RFC-1918 / loopback / link-local hosts are accepted. Intended for
     * operators who self-host RSS sources on their LAN (e.g. an RSSHub instance).
     */
    private val allowPrivateHosts: Boolean = false
) {

    companion object {
        /** Allowed URL schemes for RSS feeds. */
        private val ALLOWED_SCHEMES = setOf("http", "https")

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

    fun fetchAll(categories: Map<String, CategoryConfig>): List<Article> {
        val articles = mutableListOf<Article>()
        for ((categoryName, categoryConfig) in categories) {
            @Suppress("UNCHECKED_CAST")
            val feeds = categoryConfig.feeds as List<FeedConfig?>
            for (feedConfig in feeds) {
                if (feedConfig == null) {
                    logger.warn { "Skipping null feed entry in category '$categoryName' — check config.yaml for empty list items" }
                    continue
                }
                articles.addAll(fetchFeed(feedConfig, categoryName))
            }
        }
        return articles
    }

    fun fetchFeed(feedConfig: FeedConfig, category: String): List<Article> {
        val url = feedConfig.url
        // SSRF guard: validate URL scheme and host before making any network call
        if (enforceUrlValidation) {
            try {
                validateFeedUrl(url)
            } catch (e: IllegalArgumentException) {
                logger.warn { "Rejected feed URL '$url': ${e.message}" }
                return emptyList()
            }
        }

        val feed = try {
            var lastException: Exception? = null
            var result: com.rometools.rome.feed.synd.SyndFeed? = null

            for (attempt in 1..maxRetries) {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; RssNewsBot/1.0)")

                    try {
                        val responseCode = connection.responseCode

                        if (responseCode == HttpURLConnection.HTTP_BAD_GATEWAY) {
                            logger.warn { "Got 502 from $url (attempt $attempt/$maxRetries), retrying in ${retryDelayMs / 1000}s..." }
                            if (attempt < maxRetries) {
                                Thread.sleep(retryDelayMs)
                            }
                            continue
                        }

                        val contentType = connection.contentType
                        val input = SyndFeedInput().apply { isAllowDoctypes = false }
                        try {
                            result = input.build(XmlReader(connection.inputStream, contentType, true))
                        } catch (parseEx: Exception) {
                            // Parser-side failures (malformed XML, Rome bugs like NPE in MediaModuleParser
                            // when a <media:thumbnail> lacks a `url` attribute) won't recover on retry.
                            // Log and skip this feed for this cycle.
                            logger.warn(parseEx) { "Failed to parse RSS from $url — skipping this cycle" }
                            return emptyList()
                        }
                    } finally {
                        connection.disconnect()
                    }
                    break
                } catch (e: Exception) {
                    lastException = e
                    logger.error(e) { "Failed to fetch feed $url (attempt $attempt/$maxRetries)" }
                    if (attempt < maxRetries) {
                        Thread.sleep(retryDelayMs)
                    }
                }
            }

            result ?: throw (lastException ?: RuntimeException("Failed to fetch feed after $maxRetries attempts"))
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch feed $url after $maxRetries attempts" }
            return emptyList()
        }

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
                        link = entry.link,
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
