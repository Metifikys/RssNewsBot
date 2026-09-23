package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.fetch.VideoLinks
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * `▶️ [Трейлер](https://youtu.be/<id>)` — the YouTube video the article embeds, for announcements
 * and releases ([eventTypes]). The video comes from the topic articles' ingest meta
 * ([metifikys.model.ArticleMeta.video]) or, with [pageHtml], from the post article's page fetched
 * now ([VideoLinks.fromPage]). It is shown only when YouTube's public oEmbed endpoint (no key) knows
 * it and its title names the item's subject or franchise — a site's promo clip or an unrelated
 * embed gets no line. `Трейлер` when the title says trailer / трейлер, else `Відео`.
 *
 * Cached 7 days in `badge_cache` (`trailer`): the page → video id (a page without one as ""), and
 * the video → its oEmbed title ("" for an unknown / private / non-embeddable one). A transport
 * failure is not cached.
 */
class TrailerBadgeProvider(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    eventTypes: Collection<String>,
    /** Article page HTML for the delivery-time lookup; null = ingest meta only. */
    private val pageHtml: ((String) -> String?)? = null,
    private val youtubeBaseUrl: String = PUBLIC_YOUTUBE
) : BadgeProvider {

    override val id: String = ID

    private val eventTypes: Set<String> = eventTypes.map { it.trim().lowercase() }.toSet()

    companion object {
        const val ID = "trailer"
        const val PUBLIC_YOUTUBE = "https://www.youtube.com"
        private val TTL: Duration = Duration.ofDays(7)
        private const val NEGATIVE = ""
        /** oEmbed checks per bullet at most (the ids of the topic's articles, in order). */
        private const val MAX_VIDEOS = 2
        private val TRAILER_WORD = Regex("""trailer|трейлер""", RegexOption.IGNORE_CASE)
        private val TOKEN_SPLIT = Regex("""[^\p{L}\p{N}]+""")
    }

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        if (item == null || item.eventType.trim().lowercase() !in eventTypes) return null
        val wanted = listOf(item.subject, item.franchise).map(::tokens).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return null
        val ids = articles.mapNotNull { it.meta()?.video?.youtubeId }.distinct()
            .ifEmpty { listOfNotNull(fromPage(item, articles)) }
        for (videoId in ids.take(MAX_VIDEOS)) {
            val title = titleOf(videoId) ?: continue
            if (!matches(title, wanted)) {
                logger.info { "[Badge:trailer] ${item.eventKey}: video $videoId '$title' does not name '${item.subject}' — skipped" }
                continue
            }
            val label = if (TRAILER_WORD.containsMatchIn(title)) "Трейлер" else "Відео"
            return Badge("▶️ [$label](https://youtu.be/$videoId)")
        }
        return null
    }

    /** The video id on the post article's page (the item's url when the topic links it, else the first article). */
    private fun fromPage(item: ShortlistItem, articles: List<Article>): String? {
        val fetch = pageHtml ?: return null
        val link = articles.firstOrNull { it.link == item.url }?.link ?: articles.firstOrNull()?.link ?: return null
        val key = "page:$link"
        cache.get(id, key, TTL)?.let { return it.takeIf { v -> v.isNotEmpty() } }
        val html = fetch(link) ?: return null   // failed fetch: not cached
        val videoId = VideoLinks.fromPage(html, link)
        cache.put(id, key, videoId ?: NEGATIVE)
        return videoId
    }

    /** The video's title from oEmbed; null when YouTube does not know it (cached) or is unreachable (not cached). */
    private fun titleOf(videoId: String): String? {
        val key = "oembed:$videoId"
        cache.get(id, key, TTL)?.let { return it.takeIf { v -> v.isNotEmpty() } }
        val watch = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", Charsets.UTF_8)
        val title = try {
            http.getJson("$youtubeBaseUrl/oembed?url=$watch&format=json")
                ?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            // 401 / 403 / 400: private, not embeddable or malformed — a stable answer, cache it.
            if (e.message?.startsWith("HTTP 4") == true) {
                null
            } else {
                logger.warn { "[Badge:trailer] oEmbed failed for $videoId: ${e.message}" }
                return null
            }
        }
        cache.put(id, key, title ?: NEGATIVE)
        return title
    }

    /** Every word of the subject (or of the franchise) occurs in the video title. */
    internal fun matches(title: String, wanted: List<Set<String>>): Boolean {
        val have = tokens(title)
        return wanted.any { it.isNotEmpty() && have.containsAll(it) }
    }

    /** Lower-case words of 2+ characters (any digit run counts). */
    internal fun tokens(s: String): Set<String> =
        TOKEN_SPLIT.split(s.lowercase()).filter { it.length >= 2 || it.any(Char::isDigit) }.toSet()
}
