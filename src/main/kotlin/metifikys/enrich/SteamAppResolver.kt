package metifikys.enrich

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import metifikys.format.Hashtags
import metifikys.model.ShortlistItem
import java.net.URLEncoder
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

/**
 * Shared Steam lookups for every Steam-based badge (price, localization, critics, Deck, trend):
 * the subject → appid store search and the `appdetails` card, each done once per game and kept
 * in `badge_cache`, so five badges on one bullet cost one search and one details call.
 *
 * - [appId]: store search with a strict title match ([matches]); cached in `steam_app`, the
 *   appid for 7 days, a confident miss ("") for 24 h.
 * - [details]: `appdetails` narrowed to the fields the badges read ([DETAIL_FIELDS]) — the full
 *   answer is tens of KB of descriptions and screenshots; cached 24 h in `steam_appdetails`.
 *
 * Both throw on transport / API failure (not cached). The providers of one bullet start at the
 * same moment, so a lookup already in flight for the same key is awaited instead of repeated.
 */
class SteamAppResolver(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    private val country: String = "UA",
    private val language: String = "ukrainian",
    private val storeBaseUrl: String = SteamBadgeProvider.PUBLIC_STORE
) {
    companion object {
        const val APP_NS = "steam_app"
        const val DETAILS_NS = "steam_appdetails"
        /** The `badge_cache` prune age: a longer TTL would never be reached. */
        private val APP_TTL: Duration = Duration.ofDays(7)
        /** A miss is searched again after a day: a game gets its store page some time after the announcement. */
        private val MISS_TTL: Duration = Duration.ofHours(24)
        private val DETAILS_TTL: Duration = Duration.ofHours(24)
        private const val NEGATIVE = ""
        /** The `appdetails` fields kept in the cache. */
        val DETAIL_FIELDS = listOf("is_free", "price_overview", "release_date", "metacritic", "supported_languages")

        /**
         * Strict title match on the normalized keys: equal, or the same words up to a small
         * difference (token Jaccard ≥ 0.75), or the store title EXTENDING a multi-word subject
         * ("cyberpunk 2077" → "cyberpunk 2077 ultimate edition"). The other direction is rejected:
         * "hollow knight" is not "hollow knight silksong", and "doom" is not "doom eternal".
         */
        fun matches(candidate: String?, wanted: String): Boolean {
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

    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Any?>>()

    /** The item's Steam appid when it is one of [eventTypes] with a subject Steam knows; null otherwise. */
    fun appId(item: ShortlistItem?, eventTypes: Set<String> = SteamBadgeProvider.EVENT_TYPES): Long? {
        if (item == null || item.eventType.lowercase() !in eventTypes) return null
        return appIdFor(item.subject)
    }

    /** Appid for a game title; null = no confident match (cached). Throws on transport / API failure. */
    fun appIdFor(subject: String): Long? {
        val key = Hashtags.entityKey(subject) ?: return null
        cachedAppId(key)?.let { return it.toLongOrNull() }
        return once("app:$key") {
            cachedAppId(key)?.let { return@once it.toLongOrNull() }
            val found = search(subject, key)
            cache.put(APP_NS, key, found?.toString() ?: NEGATIVE)
            found
        } as Long?
    }

    /** The cached appid (or "" for a miss still within [MISS_TTL]); null = look it up. */
    private fun cachedAppId(key: String): String? {
        val cached = cache.get(APP_NS, key, APP_TTL) ?: return null
        return if (cached.isNotEmpty()) cached else cache.get(APP_NS, key, MISS_TTL)
    }

    /**
     * The narrowed `appdetails` data object; null when Steam has no card for the app (cached).
     * Throws on transport / API failure.
     */
    fun details(appId: Long): JsonObject? {
        val key = appId.toString()
        val cached = cache.get(DETAILS_NS, key, DETAILS_TTL)
        if (cached != null) return parse(cached)
        return once("details:$key") {
            cache.get(DETAILS_NS, key, DETAILS_TTL)?.let { return@once parse(it) }
            val body = http.getJson("$storeBaseUrl/api/appdetails?appids=$appId&cc=$country&l=$language")
                ?.jsonObject?.get(key)?.jsonObject
            val data = body?.takeIf { it["success"]?.jsonPrimitive?.booleanOrNull == true }?.get("data")?.jsonObject
            val narrow = data?.let { d -> JsonObject(DETAIL_FIELDS.mapNotNull { f -> d[f]?.let { f to it } }.toMap()) }
            cache.put(DETAILS_NS, key, narrow?.toString() ?: NEGATIVE)
            narrow
        } as JsonObject?
    }

    private fun search(subject: String, key: String): Long? {
        val q = URLEncoder.encode(subject.trim(), Charsets.UTF_8)
        val search = http.getJson("$storeBaseUrl/api/storesearch/?term=$q&l=$language&cc=$country")
            ?.jsonObject ?: return null
        val items = search["items"]?.jsonArray ?: return null
        val hit = items.map { it.jsonObject }.firstOrNull { app ->
            val name = app["name"]?.jsonPrimitive?.contentOrNull ?: return@firstOrNull false
            matches(Hashtags.entityKey(name), key)
        } ?: return null
        return hit["id"]?.jsonPrimitive?.longOrNull
    }

    private fun parse(cached: String): JsonObject? =
        cached.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject }

    /** Runs [load] once per [key] at a time; concurrent callers wait for that result (or failure). */
    private fun once(key: String, load: () -> Any?): Any? {
        val mine = CompletableFuture<Any?>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) {
            try {
                return running.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
        try {
            val value = load()
            mine.complete(value)
            return value
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }
}
