package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import metifikys.model.Article
import metifikys.model.ShortlistItem

private val logger = KotlinLogging.logger {}

/**
 * `🏆 [Metacritic](https://www.metacritic.com/game/pc/…): 90` — the critics' score for a game
 * that is out, on release and review events.
 *
 * Metacritic has no API and its site may not be scraped; Steam's `appdetails` carries the PC
 * Metascore (`metacritic: {score, url}`), so the badge reads it from the card the shared
 * [SteamAppResolver] already fetched — no extra request. Steam fills the field some days after
 * release, so the first days usually have no line. A game Steam lists as coming soon gets none.
 */
class CriticBadgeProvider(
    private val resolver: SteamAppResolver
) : BadgeProvider {

    override val id: String = ID

    companion object {
        const val ID = "critic"
        /** Release and review events: a score is the news there. */
        val EVENT_TYPES = setOf("release", "launch", "release_date", "review", "review_roundup")
    }

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        if (item == null || item.eventType.lowercase() !in EVENT_TYPES) return null
        val details = try {
            resolver.appId(item, EVENT_TYPES)?.let(resolver::details)
        } catch (e: Exception) {
            logger.warn { "[Badge:critic] Steam lookup failed for '${item.subject}': ${e.message}" }
            return null
        } ?: return null
        if (comingSoon(details)) return null
        return metacritic(details)?.let { Badge("🏆 $it") }
    }

    private fun comingSoon(details: JsonObject): Boolean =
        (details["release_date"] as? JsonObject)?.get("coming_soon")?.jsonPrimitive?.booleanOrNull == true

    /** `[Metacritic](url): 90`, or null without a score. */
    internal fun metacritic(details: JsonObject): String? {
        val mc = details["metacritic"] as? JsonObject ?: return null
        val score = mc["score"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..100 } ?: return null
        val url = mc["url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.startsWith("https://") }?.let(::withoutTracking)
        return if (url != null) "[Metacritic]($url): $score" else "Metacritic: $score"
    }

    /** Drops Steam's `ftag=` tracking parameter from the Metacritic link. */
    internal fun withoutTracking(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val kept = url.substring(q + 1).split('&').filter { it.isNotEmpty() && !it.startsWith("ftag=") }
        return url.substring(0, q) + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }
}
