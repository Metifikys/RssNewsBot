package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * `🔥 [HN](thread): 512 · 340 коментарів`
 *
 * For a bullet whose article came through an hnrss feed (see `articles.meta_json`). The
 * counts seen at fetch time are hours stale by delivery, so the official Firebase API
 * (`/v0/item/<id>.json`, no key) refreshes `score` / `descendants`; when it is unavailable the
 * fetch-time counts are shown instead — a slightly old number beats no badge.
 */
class HackerNewsBadgeProvider(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    private val apiBaseUrl: String = "https://hacker-news.firebaseio.com",
    /** Below this score the badge is left out (and re-checked after [TTL]): "🔥 1" advertises nothing. */
    private val minPoints: Int = 0
) : BadgeProvider {

    override val id: String = ID

    companion object {
        const val ID = "hn"
        private val TTL: Duration = Duration.ofHours(1)
    }

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        val hn = articles.firstNotNullOfOrNull { it.meta()?.hn } ?: return null
        val key = hn.id.toString()
        cache.get(id, key, TTL)?.let { cached -> return cached.takeIf { it.isNotEmpty() }?.let(::Badge) }

        var score = hn.points
        var comments = hn.comments
        try {
            val json = http.getJson("$apiBaseUrl/v0/item/${hn.id}.json")?.jsonObject
            if (json != null) {
                json["score"]?.jsonPrimitive?.intOrNull?.let { score = it }
                json["descendants"]?.jsonPrimitive?.intOrNull?.let { comments = it }
            }
        } catch (e: Exception) {
            logger.debug { "[Badge:hn] live counts unavailable for ${hn.id}, using fetch-time values: ${e.message}" }
        }
        val points = score ?: return null
        if (points < minPoints) {
            cache.put(id, key, "")
            return null
        }
        val text = buildString {
            append("🔥 [HN](").append(hn.discussionUrl).append("): ").append(points)
            comments?.let { append(" · ").append(ukPlural(it, "коментар", "коментарі", "коментарів")) }
        }
        cache.put(id, key, text)
        return Badge(text)
    }
}
