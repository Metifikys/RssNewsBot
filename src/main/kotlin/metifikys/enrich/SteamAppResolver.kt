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
 * - [appId]: store search with a strict title match ([matches]) for each of the item's
 *   [candidates] in turn — Step 1's subject is a Ukrainian topic ("Вихід Titan Quest II з раннього
 *   доступу"), so the Latin game title inside it is searched, then the franchise (exact match
 *   only); cached per title in `steam_app`, the appid for 7 days, a confident miss ("") for 24 h.
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
        fun matches(candidate: String?, wanted: String, allowExtension: Boolean = true): Boolean {
            if (candidate == null) return false
            if (candidate == wanted) return true
            val a = candidate.split('_').filter { it.isNotEmpty() }.toSet()
            val b = wanted.split('_').filter { it.isNotEmpty() }.toSet()
            if (a.isEmpty() || b.isEmpty()) return false
            // A franchise fallback takes the same words only: at Jaccard 0.75 "Warhammer 40,000"
            // would still accept "Warhammer 40,000: Darktide" for a story about other games.
            if (!allowExtension) return a == b
            val jaccard = (a intersect b).size.toDouble() / (a union b).size
            if (jaccard >= 0.75) return true
            return b.size >= 2 && candidate.startsWith("${wanted}_")
        }

        /** A title to search the store for; [allowExtension] as in [matches]. */
        data class Candidate(val title: String, val allowExtension: Boolean)

        /** A run of Latin-script words: a game title inside a Ukrainian subject. */
        private val LATIN_RUN = Regex("""[\p{IsLatin}\d][\p{IsLatin}\d :'’&.,!+\-]*""")
        /** Words too common to tie a title to its franchise ("The Boba Teashop" vs "The Elder Scrolls"). */
        private val WEAK_TOKENS = setOf("the", "and", "for", "of", "a", "an", "in", "on", "to", "vs")

        private fun tokens(s: String): Set<String> =
            Hashtags.entityKey(s)?.split('_')?.filter { it.length >= 2 && it !in WEAK_TOKENS }?.toSet().orEmpty()

        /**
         * The titles to search for an event, most specific first. Step 1's subject is a Ukrainian
         * topic and its franchise is `Game / Studio`: the Latin runs of the subject that share a word
         * with the franchise's game ("Titan Quest II" from "Вихід Titan Quest II з раннього доступу",
         * longest first; any run when there is no franchise), then the franchise's game itself with
         * an exact match only — "Grand Theft Auto" must not become "Grand Theft Auto V Enhanced"
         * for a GTA 6 story. A run that names something else ("Valentine's Day" in a Pokémon TCG
         * story, "One Piece" in a Spider-Man crossover) is not searched.
         */
        fun candidates(subject: String, franchise: String): List<Candidate> {
            val game = franchise.substringBefore('/').trim()
            val gameTokens = tokens(game)
            val runs = LATIN_RUN.findAll(subject)
                .map { it.value.trim().trimEnd(',', '.', ':', '-', '+', ' ') }
                .filter { run -> run.count { it.isLetter() } >= 2 }
                .filter { run -> gameTokens.isEmpty() || tokens(run).any { it in gameTokens } }
                .distinctBy { it.lowercase() }
                .sortedByDescending { it.length }
                .map { Candidate(it, allowExtension = true) }
                .toList()
            val exact = game.takeIf { g -> g.isNotEmpty() && runs.none { it.title.equals(g, ignoreCase = true) } }
                ?.let { Candidate(it, allowExtension = false) }
            return runs + listOfNotNull(exact)
        }
    }

    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Any?>>()

    /** The item's Steam appid when it is one of [eventTypes] with a subject Steam knows; null otherwise. */
    fun appId(item: ShortlistItem?, eventTypes: Set<String> = SteamBadgeProvider.EVENT_TYPES): Long? {
        if (item == null || item.eventType.lowercase() !in eventTypes) return null
        return candidates(item.subject, item.franchise).firstNotNullOfOrNull { appIdFor(it.title, it.allowExtension) }
    }

    /**
     * Appid for a game title; null = no confident match (cached). Throws on transport / API failure.
     * Without [allowExtension] only an equal (or near-equal) store title counts; cached apart.
     */
    fun appIdFor(title: String, allowExtension: Boolean = true): Long? {
        val key = Hashtags.entityKey(title) ?: return null
        val cacheKey = if (allowExtension) key else "exact:$key"
        cachedAppId(cacheKey)?.let { return it.toLongOrNull() }
        return once("app:$cacheKey") {
            cachedAppId(cacheKey)?.let { return@once it.toLongOrNull() }
            val found = search(title, key, allowExtension)
            cache.put(APP_NS, cacheKey, found?.toString() ?: NEGATIVE)
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

    private fun search(title: String, key: String, allowExtension: Boolean): Long? {
        val q = URLEncoder.encode(title.trim(), Charsets.UTF_8)
        val search = http.getJson("$storeBaseUrl/api/storesearch/?term=$q&l=$language&cc=$country")
            ?.jsonObject ?: return null
        val items = search["items"]?.jsonArray ?: return null
        val hit = items.map { it.jsonObject }.firstOrNull { app ->
            val name = app["name"]?.jsonPrimitive?.contentOrNull ?: return@firstOrNull false
            matches(Hashtags.entityKey(name), key, allowExtension)
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
