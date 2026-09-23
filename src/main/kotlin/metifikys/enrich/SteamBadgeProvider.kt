package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.format.Hashtags
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.time.Duration
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/**
 * `🎮 [Steam](store page): ₴899 (−20%) · 92% позитивних (12 345) · реліз 14 жовт. 2026`
 *
 * For game events with a subject: the Steam store search resolves the title, `appdetails`
 * gives price / release date and `appreviews` the review split — all public, no key. Search and
 * details go through the shared [SteamAppResolver] (one lookup per game for every Steam badge).
 * The title match is strict on purpose: no badge beats the wrong game. A confident miss is cached
 * as a negative so the same unknown title is not searched again every digest.
 */
class SteamBadgeProvider(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    country: String = "UA",
    language: String = "ukrainian",
    private val storeBaseUrl: String = PUBLIC_STORE,
    /** Shared with the other Steam badges; the default is a private one (tests, one-off runs). */
    private val resolver: SteamAppResolver = SteamAppResolver(http, cache, country, language, storeBaseUrl)
) : BadgeProvider {

    override val id: String = ID

    companion object {
        const val ID = "steam"
        const val PUBLIC_STORE = "https://store.steampowered.com"

        /** Step 1 event types where a store card is worth showing. */
        val EVENT_TYPES = setOf(
            "release_date", "delay", "major_announcement", "major_dlc_expansion",
            "release", "launch", "early_access", "price_change", "free_weekend"
        )
        private val TTL: Duration = Duration.ofHours(24)
        private const val NEGATIVE = ""
    }

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        if (item == null || item.eventType.lowercase() !in EVENT_TYPES) return null
        val key = Hashtags.entityKey(item.subject) ?: return null
        cache.get(id, key, TTL)?.let { cached -> return cached.takeIf { it.isNotEmpty() }?.let(::Badge) }
        val text = try {
            lookup(item.subject)
        } catch (e: Exception) {
            logger.warn { "[Badge:steam] lookup failed for '${item.subject}': ${e.message}" }
            return null   // transient: do not cache
        }
        cache.put(id, key, text ?: NEGATIVE)
        return text?.let(::Badge)
    }

    /** Null = no confident match (cacheable). Throws on transport / API failure. */
    private fun lookup(subject: String): String? {
        val appId = resolver.appIdFor(subject) ?: return null
        val data = resolver.details(appId)
        val reviews = try {
            http.getJson("$storeBaseUrl/appreviews/$appId?json=1&language=all&purchase_type=all&num_per_page=0")
                ?.jsonObject?.get("query_summary")?.jsonObject
        } catch (e: Exception) {
            logger.debug { "[Badge:steam] reviews unavailable for $appId: ${e.message}" }
            null
        }
        return render(appId, data, reviews)
    }

    private fun render(appId: Long, data: JsonObject?, reviews: JsonObject?): String? {
        val parts = mutableListOf<String>()
        if (data != null) {
            val isFree = data["is_free"]?.jsonPrimitive?.booleanOrNull == true
            val price = data["price_overview"]?.jsonObject
            when {
                isFree -> parts += "безкоштовно"
                price != null -> {
                    val formatted = price["final_formatted"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val discount = price["discount_percent"]?.jsonPrimitive?.intOrNull ?: 0
                    if (formatted.isNotEmpty()) parts += if (discount > 0) "$formatted (−$discount%)" else formatted
                }
            }
        }
        val total = reviews?.get("total_reviews")?.jsonPrimitive?.intOrNull ?: 0
        val positive = reviews?.get("total_positive")?.jsonPrimitive?.intOrNull ?: 0
        if (total > 0) {
            val pct = (positive * 100.0 / total).roundToInt()
            parts += "$pct% позитивних (${groupThousands(total.toLong())})"
        }
        val release = data?.get("release_date")?.jsonObject
        if (release?.get("coming_soon")?.jsonPrimitive?.booleanOrNull == true) {
            // For a game without a firm date Steam puts its own wording in `date` ("Незабаром",
            // "Coming soon", "Буде оголошено"); only a value with digits is an actual date.
            val date = release["date"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            parts += when {
                date.any { it.isDigit() } -> "реліз $date"
                date.isNotEmpty() -> date.lowercase()
                else -> "незабаром"
            }
        }
        if (parts.isEmpty()) return null
        return "🎮 [Steam]($PUBLIC_STORE/app/$appId): ${parts.joinToString(" · ")}"
    }

    /** See [SteamAppResolver.matches]. */
    internal fun matches(candidate: String?, wanted: String): Boolean = SteamAppResolver.matches(candidate, wanted)
}
