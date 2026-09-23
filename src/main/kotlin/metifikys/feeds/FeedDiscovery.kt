package metifikys.feeds

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.fetch.HostThrottle
import metifikys.fetch.LinkNormalizer
import metifikys.fetch.RssFetcher
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Feed autodiscovery for one candidate host (plan §24): the homepage's
 * `<link rel="alternate" type="application/rss+xml|atom+xml">`, else the usual paths
 * (`/feed`, `/rss`, `/feed.xml`, `/rss.xml`, `/atom.xml`, `/index.xml`). A URL counts as a feed only
 * when Rome parses it into at least one entry.
 *
 * Network hygiene, since the hosts come from article links (untrusted): every URL — redirect
 * targets included — goes through the fetcher's SSRF check with private hosts REJECTED (unlike the
 * feed fetcher, which allows the LAN converter), redirects are followed by hand (at most
 * [MAX_REDIRECTS]), bodies are capped at [MAX_BODY_BYTES], a host the shared [HostThrottle] has
 * cooling down is skipped rather than waited for, and each call spends from a [Budget].
 */
class FeedDiscovery(
    private val hostThrottle: HostThrottle = HostThrottle(),
    private val http: Http = UrlConnectionHttp(),
    /** Strict validator: never private hosts. Tests pass a lenient one for a local server. */
    private val validator: RssFetcher = RssFetcher(allowPrivateHosts = false),
    /** Scheme of the homepage probe; `http` only for tests against a local server. */
    private val scheme: String = "https"
) {

    /** One HTTP GET without redirects. */
    fun interface Http {
        fun get(url: String): Response?
    }

    data class Response(val status: Int, val contentType: String?, val location: String?, val body: ByteArray)

    /** Requests left for this run; discovery stops probing when it is spent. */
    class Budget(var requests: Int)

    sealed interface Result {
        data class Found(val feedUrl: String, val entries: Int, val itemsPerDay: Double?, val links: List<String>) : Result
        data class None(val tried: Int) : Result
        data class Error(val reason: String) : Result
    }

    fun discover(host: String, budget: Budget): Result {
        val home = "$scheme://$host/"
        val page = try {
            get(home, budget)
        } catch (e: Exception) {
            return Result.Error("homepage: ${e.javaClass.simpleName}: ${e.message}".take(200))
        }
        var tried = 1
        val candidates = mutableListOf<String>()
        if (page != null && page.status == 200) {
            val ct = page.contentType.orEmpty().lowercase()
            if ("xml" in ct || "rss" in ct || "atom" in ct) {
                parseFeed(home, page.body)?.let { return it }
            }
            candidates += alternateLinks(String(page.body, Charsets.UTF_8), home).take(MAX_ALTERNATES)
        }
        val base = page?.finalUrl ?: home
        candidates += FALLBACK_PATHS.map { URI(base).resolve(it).toString() }
        for (url in candidates.distinct()) {
            if (budget.requests <= 0) return Result.Error("request budget spent after $tried request(s)")
            tried++
            val r = try {
                get(url, budget)
            } catch (e: Exception) {
                logger.debug(e) { "[FeedDiscovery] $url failed" }
                null
            }
            if (r == null || r.status != 200) continue
            parseFeed(r.finalUrl, r.body)?.let { return it }
        }
        return Result.None(tried)
    }

    /** A response after redirects, with the URL that finally answered. */
    private data class Final(val status: Int, val contentType: String?, val body: ByteArray, val finalUrl: String)

    /** GET with manual, re-validated redirects; null when the host is throttled or the budget is gone. */
    private fun get(start: String, budget: Budget): Final? {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            validator.validateFeedUrl(url)
            if (budget.requests <= 0) return null
            val host = HostThrottle.hostKey(url)
            if (hostThrottle.acquireDelayMs(host) > 0) return null
            budget.requests--
            val r = http.get(url) ?: return null
            when {
                r.status == 429 -> {
                    hostThrottle.onRateLimit(host, null)
                    return Final(r.status, r.contentType, r.body, url)
                }
                r.status in 300..399 && r.location != null -> {
                    hostThrottle.onSuccess(host)
                    url = URI(url).resolve(r.location).toString()
                }
                else -> {
                    hostThrottle.onSuccess(host)
                    return Final(r.status, r.contentType, r.body, url)
                }
            }
        }
        return null
    }

    companion object {
        val FALLBACK_PATHS = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml")
        const val MAX_ALTERNATES = 2
        const val MAX_REDIRECTS = 3
        const val MAX_BODY_BYTES = 1_000_000

        private val FEED_TYPE = Regex("application/(rss|atom)\\+xml", RegexOption.IGNORE_CASE)

        /** Absolute hrefs of the page's RSS / Atom `<link rel="alternate">`s, in page order. */
        fun alternateLinks(html: String, baseUrl: String): List<String> = try {
            Jsoup.parse(html, baseUrl).select("link[href]")
                .filter { l -> l.attr("rel").split(' ').any { it.equals("alternate", true) } && FEED_TYPE.matches(l.attr("type").trim()) }
                .map { it.absUrl("href") }
                .filter { it.startsWith("http") }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }

        /** A feed with at least one entry, or null (HTML, empty, unparseable). */
        fun parseFeed(url: String, body: ByteArray): Result.Found? {
            val feed = try {
                SyndFeedInput().apply { isAllowDoctypes = false }.build(XmlReader(ByteArrayInputStream(body)))
            } catch (e: Exception) {
                return null
            }
            val entries = feed.entries.orEmpty()
            if (entries.isEmpty()) return null
            val dates = entries.mapNotNull { (it.publishedDate ?: it.updatedDate)?.toInstant() }.sorted()
            val perDay = if (dates.size >= 2) {
                val days = Duration.between(dates.first(), dates.last()).toMinutes() / 1440.0
                if (days > 0) dates.size / days else null
            } else null
            val links = entries.mapNotNull { it.link?.takeIf { l -> l.isNotBlank() } }.take(20).map { LinkNormalizer.normalize(it) }
            return Result.Found(url, entries.size, perDay, links)
        }
    }

    /** [Http] over `HttpURLConnection`: no redirects, 10 s timeouts, body capped at [MAX_BODY_BYTES]. */
    class UrlConnectionHttp : Http {
        override fun get(url: String): Response? {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; RssNewsBot/1.0; feed discovery)")
                c.setRequestProperty("Accept", "text/html,application/rss+xml,application/atom+xml,application/xml;q=0.9,*/*;q=0.5")
                val status = c.responseCode
                val stream = if (status >= 400) c.errorStream else c.inputStream
                val body = stream?.use { s -> s.readNBytes(MAX_BODY_BYTES) } ?: ByteArray(0)
                return Response(status, c.contentType, c.getHeaderField("Location"), body)
            } finally {
                c.disconnect()
            }
        }
    }
}
