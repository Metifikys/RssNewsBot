package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.time.Duration
import java.util.ArrayDeque
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * `🛡 [CVE-2021-44228](https://nvd.nist.gov/vuln/detail/CVE-2021-44228): CVSS 10.0, критична · експлуатується (CISA KEV)`
 *
 * "A critical vulnerability" in a headline gives no scale; the CVSS score and the "exploited in
 * the wild" mark do. For any bullet (legacy path too) whose subject, core fact or articles name a
 * CVE ID, at most [MAX_IDS] IDs in order of first appearance (titles before descriptions), looked
 * up in the NVD API 2.0 (keyless; optional [apiKey] → header `apiKey`).
 *
 * Metric: CVSS v4.0 → v3.1 → v3.0 → v2, the NVD's own (Primary) before the CNA's (Secondary) —
 * fresh CVEs often have only the CNA score. KEV = `cisaExploitAdd` present. A record without a
 * score yet says `ще аналізується`.
 *
 * Cache per ID in `badge_cache`: analyzed 7 days (`cve`), still being analyzed 6 h (`cve_pending`,
 * the score will come), unknown to NVD 3 h (`cve_miss`). NVD allows 5 requests per rolling 30 s
 * without a key (50 with one); the provider keeps that window itself and skips an ID when it is
 * full — waiting would outlast the badge timeout.
 */
class CveBadgeProvider(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    private val apiKey: String? = null,
    private val baseUrl: String = PUBLIC_NVD,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : BadgeProvider {

    override val id: String = ID

    companion object {
        const val ID = "cve"
        const val PUBLIC_NVD = "https://services.nvd.nist.gov"
        const val MAX_IDS = 2
        private const val PENDING_NS = "cve_pending"
        private const val MISS_NS = "cve_miss"
        private val TTL: Duration = Duration.ofDays(7)
        private val PENDING_TTL: Duration = Duration.ofHours(6)
        private val MISS_TTL: Duration = Duration.ofHours(3)
        private const val WINDOW_MS = 30_000L
        private val CVE_ID = Regex("""\bCVE-\d{4}-\d{4,7}\b""", RegexOption.IGNORE_CASE)
        private val METRICS = listOf("cvssMetricV40", "cvssMetricV31", "cvssMetricV30", "cvssMetricV2")
        private val ANALYZED = setOf("analyzed", "modified")
        private val SEVERITY = mapOf("CRITICAL" to "критична", "HIGH" to "висока", "MEDIUM" to "середня", "LOW" to "низька")

        /** Up to [MAX_IDS] distinct upper-case IDs, in the order the texts name them. */
        fun cveIds(item: ShortlistItem?, articles: List<Article>): List<String> {
            val texts = listOfNotNull(item?.subject, item?.coreFact) +
                articles.map { it.title } + articles.map { it.description } + articles.mapNotNull { it.fullText }
            val out = LinkedHashSet<String>()
            for (text in texts) {
                for (m in CVE_ID.findAll(text)) {
                    out += m.value.uppercase(Locale.ROOT)
                    if (out.size == MAX_IDS) return out.toList()
                }
            }
            return out.toList()
        }
    }

    /** What NVD says about one ID: score and severity (null while unscored), KEV, analysis state. */
    internal data class Record(val score: Double?, val severity: String?, val kev: Boolean, val analyzed: Boolean) {
        fun encode() = "${score ?: ""};${severity ?: ""};${if (kev) 1 else 0};${if (analyzed) 1 else 0}"

        companion object {
            fun decode(s: String): Record? {
                val p = s.split(';')
                if (p.size != 4) return null
                return Record(p[0].toDoubleOrNull(), p[1].ifEmpty { null }, p[2] == "1", p[3] == "1")
            }
        }
    }

    private val window = ArrayDeque<Long>()
    private val limit = if (apiKey.isNullOrBlank()) 5 else 50

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        val ids = cveIds(item, articles)
        if (ids.isEmpty()) return null
        val found = ids.mapNotNull { cveId -> record(cveId)?.let { cveId to it } }
        if (found.isEmpty()) return null
        return Badge("🛡 " + found.joinToString(" · ") { (cveId, r) -> render(cveId, r, single = found.size == 1) })
    }

    /** The cached or fetched record; null when NVD does not know the ID, is unreachable, or the window is full. */
    private fun record(cveId: String): Record? {
        cache.get(id, cveId, TTL)?.let { return Record.decode(it) }
        cache.get(PENDING_NS, cveId, PENDING_TTL)?.let { return Record.decode(it) }
        if (cache.get(MISS_NS, cveId, MISS_TTL) != null) return null
        if (!tryAcquire()) {
            logger.info { "[Badge:cve] NVD rate window full ($limit / 30 s) — skipping $cveId" }
            return null
        }
        val cve = try {
            val headers = if (apiKey.isNullOrBlank()) emptyMap() else mapOf("apiKey" to apiKey)
            val body = http.getJson("$baseUrl/rest/json/cves/2.0?cveId=$cveId", headers)?.jsonObject
            (body?.get("vulnerabilities") as? JsonArray)?.firstOrNull()?.jsonObject?.get("cve") as? JsonObject
        } catch (e: Exception) {
            logger.warn { "[Badge:cve] NVD lookup failed for $cveId: ${e.message}" }
            return null   // transient: not cached
        }
        if (cve == null) {
            cache.put(MISS_NS, cveId, "1")
            return null
        }
        val status = cve["vulnStatus"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (status.equals("Rejected", ignoreCase = true)) {
            cache.put(id, cveId, "")   // decodes to null: a withdrawn ID says nothing
            return null
        }
        val record = parse(cve)
        cache.put(if (record.analyzed && record.score != null) id else PENDING_NS, cveId, record.encode())
        return record
    }

    internal fun parse(cve: JsonObject): Record {
        val metrics = cve["metrics"] as? JsonObject
        val metric = metrics?.let { m ->
            METRICS.firstNotNullOfOrNull { key ->
                val list = (m[key] as? JsonArray)?.map { it.jsonObject }.orEmpty()
                list.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "Primary" } ?: list.firstOrNull()
            }
        }
        val data = metric?.get("cvssData") as? JsonObject
        val score = data?.get("baseScore")?.jsonPrimitive?.doubleOrNull
        // v3 / v4 carry the severity inside cvssData, v2 next to it.
        val severity = (data?.get("baseSeverity") ?: metric?.get("baseSeverity"))?.jsonPrimitive?.contentOrNull?.uppercase()
        val kev = cve["cisaExploitAdd"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        val status = cve["vulnStatus"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
        return Record(score, severity, kev, status in ANALYZED)
    }

    internal fun render(cveId: String, r: Record, single: Boolean): String {
        val parts = mutableListOf<String>()
        if (r.score != null) {
            val score = String.format(Locale.ROOT, "%.1f", r.score)
            val sev = r.severity?.let { SEVERITY[it] }
            parts += (if (single) "CVSS $score" else score) + (sev?.let { ", $it" } ?: "")
        } else {
            parts += "ще аналізується"
        }
        val head = "[$cveId](https://nvd.nist.gov/vuln/detail/$cveId): ${parts.joinToString()}"
        return when {
            !r.kev -> head
            single -> "$head · експлуатується (CISA KEV)"
            else -> "$head, експлуатується"
        }
    }

    @Synchronized
    private fun tryAcquire(): Boolean {
        val now = nowMillis()
        while (window.isNotEmpty() && now - window.peekFirst() >= WINDOW_MS) window.pollFirst()
        if (window.size >= limit) return false
        window.addLast(now)
        return true
    }
}
