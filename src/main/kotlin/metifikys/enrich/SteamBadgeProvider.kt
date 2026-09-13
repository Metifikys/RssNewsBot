package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import metifikys.format.Hashtags
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.net.URLEncoder
import java.time.Duration
import kotlin.math.roundToInt

private val logger = KotlinLogging.logger {}

/**
 * `🎮 [Steam](store page): ₴899 (−20%) · 92% позитивних (12 345) · реліз 14 жовт. 2026`
 *
 * For game events with a subject: the Steam store search resolves the title, `appdetails`
 * gives price / release date and `appreviews` the review split — all public, no key. The title
 * match is strict on purpose: no badge beats the wrong game. A confident miss is cached as a
 * negative so the same unknown title is not searched again every digest.
 */
class SteamBadgeProvider(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    private val country: String = "UA",
    private val language: String = "ukrainian",
    private val storeBaseUrl: String = PUBLIC_STORE
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
            lookup(item.subject, key)
        } catch (e: Exception) {
            logger.warn { "[Badge:steam] lookup failed for '${item.subject}': ${e.message}" }
            return null   // transient: do not cache
        }
        cache.put(id, key, text ?: NEGATIVE)
        return text?.let(::Badge)
    }

    /** Null = no confident match (cacheable). Throws on transport / API failure. */
    private fun lookup(subject: String, key: String): String? {
        val q = URLEncoder.encode(subject.trim(), Charsets.UTF_8)
        val search = http.getJson("$storeBaseUrl/api/storesearch/?term=$q&l=$language&cc=$country")
            ?.jsonObject ?: return null
        val items = search["items"]?.jsonArray ?: return null
        val hit = items.map { it.jsonObject }.firstOrNull { app ->
            val name = app["name"]?.jsonPrimitive?.contentOrNull ?: return@firstOrNull false
            matches(Hashtags.entityKey(name), key)
        } ?: return null
        val appId = hit["id"]?.jsonPrimitive?.longOrNull ?: return null

        val details = http.getJson("$storeBaseUrl/api/appdetails?appids=$appId&cc=$country&l=$language")
            ?.jsonObject?.get(appId.toString())?.jsonObject
        val data = details?.takeIf { it["success"]?.jsonPrimitive?.booleanOrNull == true }?.get("data")?.jsonObject
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

    /**
     * Strict title match on the normalized keys: equal, or the same words up to a small
     * difference (token Jaccard ≥ 0.75), or the store title EXTENDING a multi-word subject
     * ("cyberpunk 2077" → "cyberpunk 2077 ultimate edition"). The other direction is rejected:
     * "hollow knight" is not "hollow knight silksong", and "doom" is not "doom eternal".
     */
    internal fun matches(candidate: String?, wanted: String): Boolean {
        if (candidate == null) return false
        if (candidate == wanted) return true
        val a = candidate.split('_').filter { it.isNotEmpty() }.toSet()
        val b = wanted.split('_').filter { it.isNotEmpty() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        val jaccard = (a intersect b).size.toDouble() / (a union b).size
        if (jaccard >= 0.75) return true
        return b.size >= 2 && candidate.startsWith("${wanted}_")
    }
}
