package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.net.URLEncoder
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * `🇺🇦 Українська: інтерфейс, субтитри, озвучка` — whether a Steam game has Ukrainian, and how
 * much of it, for the same game events as the Steam badge (appid from the shared [SteamAppResolver]).
 *
 * Source: the official Web API `IStoreBrowseService/GetItems` (no key) — per language
 * `supported` (interface), `subtitles` and `full_audio`; Ukrainian is `elanguage` 26. Cached 7 days
 * per appid in `uk_lang` (localizations are rarely added). When GetItems fails or knows nothing,
 * the `supported_languages` HTML of `appdetails` is the fallback: it only tells "has Ukrainian"
 * and, by the asterisk, "with voice-over".
 *
 * No Ukrainian = no line, unless [showMissing] (`🇺🇦 Без української`).
 */
class UkLocalizationBadgeProvider(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    private val resolver: SteamAppResolver,
    private val showMissing: Boolean = false,
    private val country: String = "UA",
    private val apiBaseUrl: String = PUBLIC_API
) : BadgeProvider {

    override val id: String = ID

    companion object {
        const val ID = "uk_lang"
        const val PUBLIC_API = "https://api.steampowered.com"
        /** Steam's ELanguage id for Ukrainian. */
        const val UKRAINIAN = 26
        private val TTL: Duration = Duration.ofDays(7)
        /** Cached GetItems answers: letters `i` interface, `s` subtitles, `a` voice-over; [NONE] / [UNKNOWN]. */
        private const val NONE = "-"
        private const val UNKNOWN = "?"
        private val UK_IN_LIST = Regex("""(українська|ukrainian)\s*(<strong>\s*\*\s*</strong>|\*)?""", RegexOption.IGNORE_CASE)
    }

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        val appId = try {
            resolver.appId(item) ?: return null
        } catch (e: Exception) {
            logger.warn { "[Badge:uk_lang] appid lookup failed for '${item?.subject}': ${e.message}" }
            return null
        }
        val flags = languageFlags(appId)
        if (flags != null && flags != UNKNOWN) return render(flags)
        // GetItems unreachable or silent about this app: the appdetails language list.
        val list = try {
            resolver.details(appId)?.get("supported_languages")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            logger.debug { "[Badge:uk_lang] appdetails unavailable for $appId: ${e.message}" }
            null
        } ?: return null
        return fromLanguageList(list)
    }

    /** Cached / fetched GetItems flags; null when the API failed (not cached). */
    private fun languageFlags(appId: Long): String? {
        val key = appId.toString()
        cache.get(id, key, TTL)?.let { return it }
        val flags = try {
            fetchFlags(appId)
        } catch (e: Exception) {
            logger.debug { "[Badge:uk_lang] GetItems failed for $appId: ${e.message}" }
            return null
        }
        cache.put(id, key, flags)
        return flags
    }

    private fun fetchFlags(appId: Long): String {
        val input = """{"ids":[{"appid":$appId}],"context":{"language":"english","country_code":"$country"},""" +
            """"data_request":{"include_supported_languages":true}}"""
        val url = "$apiBaseUrl/IStoreBrowseService/GetItems/v1/?input_json=${URLEncoder.encode(input, Charsets.UTF_8)}"
        val app = http.getJson(url)?.jsonObject?.get("response")?.jsonObject
            ?.get("store_items")?.jsonArray?.firstOrNull()?.jsonObject ?: return UNKNOWN
        if (app["success"]?.jsonPrimitive?.intOrNull != 1) return UNKNOWN
        val languages = app["supported_languages"]?.jsonArray?.map { it.jsonObject }
        if (languages.isNullOrEmpty()) return UNKNOWN
        return flagsOf(languages.firstOrNull { it["elanguage"]?.jsonPrimitive?.intOrNull == UKRAINIAN })
    }

    private fun flagsOf(uk: JsonObject?): String {
        if (uk == null) return NONE
        fun on(field: String) = uk[field]?.jsonPrimitive?.booleanOrNull == true
        val flags = buildString {
            if (on("supported")) append('i')
            if (on("subtitles")) append('s')
            if (on("full_audio")) append('a')
        }
        return flags.ifEmpty { NONE }
    }

    internal fun render(flags: String): Badge? {
        if (flags == NONE) return if (showMissing) Badge("🇺🇦 Без української") else null
        val audio = 'a' in flags
        val parts = listOfNotNull("інтерфейс".takeIf { 'i' in flags }, "субтитри".takeIf { 's' in flags })
        val text = when {
            audio && parts.size == 2 -> "інтерфейс, субтитри, озвучка"
            audio -> (parts + "озвучка").joinToString(" і ")
            else -> parts.joinToString(" і ") + ", без озвучки"
        }
        return Badge("🇺🇦 Українська: $text")
    }

    /** Fallback on the appdetails HTML list: `…, українська<strong>*</strong>, …` (asterisk = voice-over). */
    internal fun fromLanguageList(list: String): Badge? {
        if (list.isBlank()) return null
        val match = UK_IN_LIST.find(list)
            ?: return if (showMissing) Badge("🇺🇦 Без української") else null
        return Badge(if (match.groupValues[2].isNotEmpty()) "🇺🇦 Українська з озвучкою" else "🇺🇦 Українська: є")
    }
}
