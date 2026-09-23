package metifikys.feeds

import metifikys.db.FeedCandidateSeen
import java.net.URI

/**
 * Candidate new sources (plan §24, option A): domains the channel keeps publishing stories from
 * although no configured feed is on them — they arrive through reddit, hnrss or a converter. Ranked
 * by how many published links came from them, not by raw volume. Pure; no network.
 */
object FeedCandidates {

    /** Second-level labels under which a registrable domain has three labels (`bbc.co.uk`, `pravda.com.ua`). */
    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "ac", "edu", "in", "kiev", "kyiv")

    /** Lowercase host without `www.`, or null when [url] has none. */
    fun hostOf(url: String): String? = try {
        URI(url.trim()).host?.lowercase()?.removePrefix("www.")?.takeIf { it.contains('.') }
    } catch (e: Exception) {
        null
    }

    /** `feeds.arstechnica.com` → `arstechnica.com`, `news.bbc.co.uk` → `bbc.co.uk`; an IP stays itself. */
    fun registrableDomain(host: String): String {
        val labels = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2 || labels.last().all(Char::isDigit)) return labels.joinToString(".")
        val keep = if (labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    /**
     * @param publishedLinks every link of every post sent in the window
     * @param feedUrls the configured feeds: their registrable domains are "already followed"
     * @param ignoreDomains `feedHealth.candidates.ignoreDomains` (a domain covers its subdomains)
     * @return one entry per registrable domain with at least [minPublished] published links, most published first
     */
    fun rank(
        publishedLinks: Collection<String>,
        feedUrls: Collection<String>,
        ignoreDomains: Collection<String>,
        minPublished: Int
    ): List<FeedCandidateSeen> {
        val followed = feedUrls.mapNotNull { hostOf(it) }.map { registrableDomain(it) }.toSet()
        val ignored = ignoreDomains.map { it.trim().lowercase().removePrefix("www.") }.filter { it.isNotEmpty() }
        return publishedLinks.distinct()
            .mapNotNull { link -> hostOf(link)?.let { host -> Triple(registrableDomain(host), host, link) } }
            .filter { (domain, host, _) ->
                domain !in followed && ignored.none { host == it || host.endsWith(".$it") || domain == it }
            }
            .groupBy { it.first }
            .filterValues { it.size >= minPublished }
            .map { (domain, hits) ->
                val host = hits.groupingBy { it.second }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.length })
                    .first().key
                FeedCandidateSeen(domain, host, hits.size, hits.first().third)
            }
            .sortedWith(compareByDescending<FeedCandidateSeen> { it.published }.thenBy { it.domain })
    }
}
