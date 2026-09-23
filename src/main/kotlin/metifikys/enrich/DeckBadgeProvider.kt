package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

private val logger = KotlinLogging.logger {}

/**
 * `🕹 Steam Deck: ✅ перевірено · [ProtonDB](https://www.protondb.com/app/1030300): Platinum (354 звіти)`
 *
 * "Will it run on my Deck / Linux" for a Steam game (appid from the shared [SteamAppResolver]),
 * on the Steam badge's event types plus `patch`. Two public, keyless, undocumented endpoints,
 * asked in parallel, each optional:
 * - Valve's Deck compatibility report (the store page's own AJAX): `resolved_category` 3 Verified,
 *   2 Playable, 1 Unsupported, 0 Unknown (not shown); an unknown app answers `results: []`;
 * - ProtonDB's report summary: `tier` when at least [MIN_REPORTS] reports and not `pending`; an
 *   unknown app is a 404.
 *
 * The line is cached 24 h per appid in `deck` (nothing to say = ""); when either request failed
 * the result is not cached, so a transient error does not hide a part for a day.
 */
class DeckBadgeProvider(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    private val resolver: SteamAppResolver,
    private val storeBaseUrl: String = SteamBadgeProvider.PUBLIC_STORE,
    private val protonBaseUrl: String = PUBLIC_PROTONDB
) : BadgeProvider {

    override val id: String = ID

    companion object {
        const val ID = "deck"
        const val PUBLIC_PROTONDB = "https://www.protondb.com"
        val EVENT_TYPES = SteamBadgeProvider.EVENT_TYPES + "patch"
        const val MIN_REPORTS = 5
        private val TTL: Duration = Duration.ofHours(24)
        private const val NEGATIVE = ""
    }

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        val appId = try {
            resolver.appId(item, EVENT_TYPES) ?: return null
        } catch (e: Exception) {
            logger.warn { "[Badge:deck] appid lookup failed for '${item?.subject}': ${e.message}" }
            return null
        }
        val key = appId.toString()
        cache.get(id, key, TTL)?.let { cached -> return cached.takeIf { it.isNotEmpty() }?.let(::Badge) }
        val deckCall = badgeAsync { deckCategory(appId) }
        val protonCall = badgeAsync { protonSummary(appId) }
        val deck = await(deckCall, "Deck report", appId)
        val proton = await(protonCall, "ProtonDB", appId)
        val text = render(appId, deck.getOrNull(), proton.getOrNull())
        if (deck.isSuccess && proton.isSuccess) cache.put(id, key, text ?: NEGATIVE)
        return text?.let(::Badge)
    }

    private fun <T> await(call: Future<T>, what: String, appId: Long): Result<T> = try {
        Result.success(call.get())
    } catch (e: InterruptedException) {
        call.cancel(true)
        throw e
    } catch (e: ExecutionException) {
        logger.debug { "[Badge:deck] $what unavailable for $appId: ${e.cause?.message}" }
        Result.failure(e.cause ?: e)
    }

    /** `resolved_category`, 0 when Valve has no report (`results: []`). */
    private fun deckCategory(appId: Long): Int {
        val results = http.getJson("$storeBaseUrl/saleaction/ajaxgetdeckappcompatibilityreport?nAppID=$appId")
            ?.jsonObject?.get("results") as? JsonObject ?: return 0
        return results["resolved_category"]?.jsonPrimitive?.intOrNull ?: 0
    }

    private fun protonSummary(appId: Long): JsonObject? =
        http.getJson("$protonBaseUrl/api/v1/reports/summaries/$appId.json")?.jsonObject

    internal fun render(appId: Long, deckCategory: Int?, proton: JsonObject?): String? {
        val parts = mutableListOf<String>()
        when (deckCategory) {
            3 -> parts += "Steam Deck: ✅ перевірено"
            2 -> parts += "Steam Deck: 🟡 можна грати"
            1 -> parts += "Steam Deck: ❌ не підтримується"
        }
        val tier = proton?.get("tier")?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        val total = proton?.get("total")?.jsonPrimitive?.intOrNull ?: 0
        if (tier.isNotEmpty() && tier != "pending" && total >= MIN_REPORTS) {
            val reports = ukPlural(total, "звіт", "звіти", "звітів").substringAfter(' ')
            parts += "[ProtonDB]($PUBLIC_PROTONDB/app/$appId): ${tier.replaceFirstChar { it.uppercase() }} " +
                "(${groupThousands(total.toLong())} $reports)"
        }
        if (parts.isEmpty()) return null
        return "🕹 ${parts.joinToString(" · ")}"
    }
}
