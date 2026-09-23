package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

private val logger = KotlinLogging.logger {}

/**
 * `📈 Steam: №4 у хітах продажу · №12 серед найочікуваніших` — the game is in the top [topN] of a
 * Steam chart, one fact that says why the news matters. Same game events as the Steam badge, appid
 * from the shared [SteamAppResolver]; at most two charts, in [CHARTS] order.
 *
 * Charts (keyless): top sellers, most wishlisted and popular upcoming come from the store search
 * page's own JSON (`search/results/?filter=…&json=1` — undocumented; the appid is only in the item's
 * logo URL, `/apps/<id>/`; an item without one keeps its position), the most played from the
 * official `ISteamChartsService/GetMostPlayedGames`. Each chart is cached whole for 3 h in
 * `steam_chart` (appids in rank order) and fetched once however many bullets ask at the same time,
 * all missing ones in parallel. A failed chart is left out and not retried for
 * [RETRY_AFTER_MS]. The badge itself is not cached: it is derived from the charts and the appid.
 */
class SteamTrendBadgeProvider(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    private val resolver: SteamAppResolver,
    private val topN: Int = 25,
    private val country: String = "UA",
    private val storeBaseUrl: String = SteamBadgeProvider.PUBLIC_STORE,
    private val apiBaseUrl: String = UkLocalizationBadgeProvider.PUBLIC_API
) : BadgeProvider {

    override val id: String = ID

    /** [filter] = the store search filter, null for the Web API most-played chart. */
    internal data class Chart(val key: String, val filter: String?, val label: String)

    companion object {
        const val ID = "steam_trend"
        private const val CHART_NS = "steam_chart"
        private val TTL: Duration = Duration.ofHours(3)
        private const val RETRY_AFTER_MS = 15 * 60_000L
        private const val MAX_CHARTS = 2
        private val APP_IN_LOGO = Regex("""/apps/(\d+)/""")

        internal val CHARTS = listOf(
            Chart("topsellers", "topsellers", "у хітах продажу"),
            Chart("popularwishlist", "popularwishlist", "серед найочікуваніших"),
            Chart("popularcomingsoon", "popularcomingsoon", "серед популярних майбутніх"),
            Chart("mostplayed", null, "за онлайном")
        )
    }

    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Future<List<Long>?>>()

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        val appId = try {
            resolver.appId(item) ?: return null
        } catch (e: Exception) {
            logger.warn { "[Badge:steam_trend] appid lookup failed for '${item?.subject}': ${e.message}" }
            return null
        }
        val charts = charts()
        val ranks = CHARTS.mapNotNull { chart ->
            val rank = charts[chart.key]?.indexOf(appId)?.takeIf { it >= 0 }?.plus(1)
            rank?.takeIf { it <= topN }?.let { "№$it ${chart.label}" }
        }
        if (ranks.isEmpty()) return null
        return Badge("📈 Steam: ${ranks.take(MAX_CHARTS).joinToString(" · ")}")
    }

    /**
     * Every chart available now: cached, else fetched — the missing ones in parallel, a fetch already
     * running (another bullet's) awaited rather than repeated. A fetch finishes and fills the cache
     * even when this bullet's badge time runs out first.
     */
    private fun charts(): Map<String, List<Long>> {
        val out = HashMap<String, List<Long>>()
        val now = System.currentTimeMillis()
        val pending = mutableListOf<Pair<Chart, Future<List<Long>?>>>()
        for (chart in CHARTS) {
            val cached = cache.get(CHART_NS, cacheKey(chart), TTL)
            if (cached != null) {
                out[chart.key] = decode(cached)
                continue
            }
            if (failedAt[chart.key]?.let { now - it < RETRY_AFTER_MS } == true) continue
            val call = inFlight.compute(chart.key) { _, running ->
                if (running != null && !running.isDone) running else badgeAsync { load(chart) }
            }!!
            pending += chart to call
        }
        for ((chart, call) in pending) {
            try {
                call.get()?.let { out[chart.key] = it }
            } catch (e: ExecutionException) {
                logger.debug { "[Badge:steam_trend] chart ${chart.key}: ${e.cause?.message}" }
            }
        }
        return out
    }

    /** Fetches and caches one chart; null (and no retry for a while) on failure. */
    private fun load(chart: Chart): List<Long>? = try {
        val ids = fetch(chart)
        cache.put(CHART_NS, cacheKey(chart), ids.joinToString(","))
        failedAt.remove(chart.key)
        ids
    } catch (e: Exception) {
        failedAt[chart.key] = System.currentTimeMillis()
        logger.warn { "[Badge:steam_trend] chart ${chart.key} unavailable: ${e.message}" }
        null
    }

    private fun cacheKey(chart: Chart) = if (chart.filter != null) "${chart.filter}:$country" else chart.key

    /** Appids in rank order; 0 marks a position whose appid is unknown. */
    private fun fetch(chart: Chart): List<Long> =
        if (chart.filter != null) storeChart(chart.filter) else mostPlayed()

    private fun storeChart(filter: String): List<Long> {
        val body = http.getJson("$storeBaseUrl/search/results/?filter=$filter&json=1&count=100&cc=$country")?.jsonObject
            ?: return emptyList()
        val items = body["items"] as? JsonArray ?: throw IllegalStateException("no items in the $filter chart")
        return items.map { item ->
            val logo = (item as? JsonObject)?.get("logo")?.jsonPrimitive?.contentOrNull.orEmpty()
            APP_IN_LOGO.find(logo)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }
    }

    private fun mostPlayed(): List<Long> {
        val ranks = http.getJson("$apiBaseUrl/ISteamChartsService/GetMostPlayedGames/v1/")?.jsonObject
            ?.get("response")?.jsonObject?.get("ranks") as? JsonArray ?: return emptyList()
        val byRank = ranks.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val rank = o["rank"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val appId = o["appid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            rank to appId
        }.filter { it.first in 1..1000 }.toMap()
        val last = byRank.keys.maxOrNull() ?: return emptyList()
        return (1..last).map { byRank[it] ?: 0L }
    }

    private fun decode(s: String): List<Long> = if (s.isEmpty()) emptyList() else s.split(',').map { it.toLongOrNull() ?: 0L }
}
