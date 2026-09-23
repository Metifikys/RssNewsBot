package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import metifikys.format.Hashtags
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/**
 * `🏆 [Metacritic](https://www.metacritic.com/game/pc/…): 90 · [OpenCritic](…): 91, рекомендують 97%`
 * — the critics' score for a game that is out, on release and review events.
 *
 * Metacritic has no API and its site may not be scraped; Steam's `appdetails` carries the PC
 * Metascore (`metacritic: {score, url}`), so the badge reads it from the card the shared
 * [SteamAppResolver] already fetched — no extra request. Steam fills the field some days after
 * release, so the first days usually have no line. A game Steam lists as coming soon gets none.
 *
 * OpenCritic only with [openCriticKey] (a RapidAPI key; its free plan allows 25 searches a day):
 * title search (strict match, cached 7 days in `opencritic_id`, at most [maxSearchesPerDay] per
 * process day), then the game (rendered part cached 24 h in `opencritic`). A score of -1 (not
 * collected yet) leaves the OpenCritic part out. Without a key no request goes to OpenCritic.
 */
class CriticBadgeProvider(
    private val resolver: SteamAppResolver,
    private val http: BadgeHttp? = null,
    private val cache: BadgeCache? = null,
    private val openCriticKey: String? = null,
    private val openCriticBaseUrl: String = OPENCRITIC_API,
    private val maxSearchesPerDay: Int = 20
) : BadgeProvider {

    override val id: String = ID

    companion object {
        const val ID = "critic"
        const val OPENCRITIC_API = "https://opencritic-api.p.rapidapi.com"
        private const val OPENCRITIC_HOST = "opencritic-api.p.rapidapi.com"
        /** Release and review events: a score is the news there. */
        val EVENT_TYPES = setOf("release", "launch", "release_date", "review", "review_roundup")
        private const val ID_NS = "opencritic_id"
        private const val GAME_NS = "opencritic"
        private val ID_TTL: Duration = Duration.ofDays(7)
        private val GAME_TTL: Duration = Duration.ofHours(24)
        private const val NEGATIVE = ""
    }

    private val openCritic: Boolean = !openCriticKey.isNullOrBlank() && http != null && cache != null
    private var searchDay: LocalDate = LocalDate.MIN
    private var searchesToday = 0

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        if (item == null || item.eventType.lowercase() !in EVENT_TYPES) return null
        val details = try {
            resolver.appId(item, EVENT_TYPES)?.let(resolver::details)
        } catch (e: Exception) {
            logger.warn { "[Badge:critic] Steam lookup failed for '${item.subject}': ${e.message}" }
            return null
        }
        if (details == null && !openCritic) return null
        if (details != null && comingSoon(details)) return null
        val parts = listOfNotNull(details?.let(::metacritic), if (openCritic) openCriticPart(item.subject) else null)
        if (parts.isEmpty()) return null
        return Badge("🏆 ${parts.joinToString(" · ")}")
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

    /** `[OpenCritic](url): 91, рекомендують 97%`; null when unknown, not scored yet, or unreachable. */
    private fun openCriticPart(subject: String): String? {
        val cache = cache ?: return null
        return try {
            val gameId = openCriticId(subject, cache) ?: return null
            cache.get(GAME_NS, gameId.toString(), GAME_TTL)?.let { return it.takeIf { v -> v.isNotEmpty() } }
            val game = openCriticGet("/game/$gameId")?.jsonObject
            val part = game?.let { renderOpenCritic(gameId, it) }
            cache.put(GAME_NS, gameId.toString(), part ?: NEGATIVE)
            part
        } catch (e: Exception) {
            logger.warn { "[Badge:critic] OpenCritic failed for '$subject': ${e.message}" }
            null
        }
    }

    private fun openCriticId(subject: String, cache: BadgeCache): Long? {
        val key = Hashtags.entityKey(subject) ?: return null
        cache.get(ID_NS, key, ID_TTL)?.let { return it.toLongOrNull() }
        if (!takeSearch()) {
            logger.info { "[Badge:critic] OpenCritic search budget ($maxSearchesPerDay/day) spent — skipping '$subject'" }
            return null
        }
        val q = URLEncoder.encode(subject.trim(), Charsets.UTF_8)
        val hits = openCriticGet("/game/search?criteria=$q") as? JsonArray
        val hit = hits?.map { it.jsonObject }?.firstOrNull { g ->
            SteamAppResolver.matches(Hashtags.entityKey(g["name"]?.jsonPrimitive?.contentOrNull ?: ""), key)
        }
        val id = hit?.get("id")?.jsonPrimitive?.longOrNull
        cache.put(ID_NS, key, id?.toString() ?: NEGATIVE)
        return id
    }

    @Synchronized
    private fun takeSearch(): Boolean {
        val today = LocalDate.now()
        if (today != searchDay) {
            searchDay = today
            searchesToday = 0
        }
        if (searchesToday >= maxSearchesPerDay) return false
        searchesToday++
        return true
    }

    private fun openCriticGet(path: String) = http!!.getJson(
        "$openCriticBaseUrl$path",
        mapOf("X-RapidAPI-Key" to openCriticKey!!, "X-RapidAPI-Host" to OPENCRITIC_HOST)
    )

    internal fun renderOpenCritic(gameId: Long, game: JsonObject): String? {
        val score = game["topCriticScore"]?.jsonPrimitive?.doubleOrNull?.takeIf { it >= 0 }?.roundToInt() ?: return null
        val url = game["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://opencritic.com/") }
            ?: "https://opencritic.com/game/$gameId/${slug(game["name"]?.jsonPrimitive?.contentOrNull.orEmpty())}"
        val recommended = game["percentRecommended"]?.jsonPrimitive?.doubleOrNull?.takeIf { it >= 0 }?.roundToInt()
        return "[OpenCritic]($url): $score" + (recommended?.let { ", рекомендують $it%" } ?: "")
    }

    private fun slug(name: String): String =
        name.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').ifEmpty { "game" }
}
