package metifikys.enrich

import metifikys.fetch.UrlGuard
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CveBadgeProviderTest {

    private fun item(subject: String = "Log4Shell", coreFact: String = "f") = ShortlistItem(
        eventKey = "evt", subject = subject, coreFact = coreFact, url = "https://example.com/1", status = "new", eventType = "vulnerability"
    )

    private fun article(title: String, description: String = "", fullText: String? = null) =
        Article(category = "tech", title = title, link = "https://example.com/a", description = description, fullText = fullText)

    /** Trimmed from the real NVD 2.0 answer for CVE-2021-44228 (2026-09-23). */
    private val log4shell = """{"resultsPerPage":1,"startIndex":0,"totalResults":1,"format":"NVD_CVE","version":"2.0",
        "vulnerabilities":[{"cve":{"id":"CVE-2021-44228","sourceIdentifier":"security@apache.org","vulnStatus":"Analyzed",
        "metrics":{
          "cvssMetricV31":[
            {"source":"nvd@nist.gov","type":"Primary","cvssData":{"version":"3.1","baseScore":10.0,"baseSeverity":"CRITICAL"},"exploitabilityScore":3.9,"impactScore":6.0},
            {"source":"134c704f-9b21-4f2e-91b3-4a467353bcc0","type":"Secondary","cvssData":{"version":"3.1","baseScore":10.0,"baseSeverity":"CRITICAL"}}],
          "cvssMetricV2":[{"source":"nvd@nist.gov","type":"Primary","cvssData":{"version":"2.0","baseScore":9.3},"baseSeverity":"HIGH"}],
          "ssvcV203":[{"source":"134c704f-9b21-4f2e-91b3-4a467353bcc0","ssvcData":{"role":"CISA Coordinator"}}]},
        "cisaExploitAdd":"2021-12-10","cisaActionDue":"2021-12-24",
        "cisaVulnerabilityName":"Apache Log4j2 Remote Code Execution Vulnerability"}}]}"""
    /** The real answer for an ID NVD does not have. */
    private val unknown = """{"resultsPerPage":0,"startIndex":0,"totalResults":0,"format":"NVD_CVE","version":"2.0","vulnerabilities":[]}"""

    private fun cve(id: String, status: String, metrics: String = "{}") =
        """{"totalResults":1,"vulnerabilities":[{"cve":{"id":"$id","vulnStatus":"$status","metrics":$metrics}}]}"""

    private fun provider(api: FakeApi, cache: BadgeCache = MemoryBadgeCache(), key: String? = null, now: () -> Long = System::currentTimeMillis) =
        CveBadgeProvider(BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF), cache, key, api.baseUrl, now)

    @Test
    fun `IDs are found case-insensitively, deduplicated, at most two, titles before descriptions`() {
        val ids = CveBadgeProvider.cveIds(
            item(subject = "Patch Tuesday", coreFact = "fixes cve-2024-3094"),
            listOf(
                article("CVE-2024-3094 and CVE-2024-21412 fixed", description = "also CVE-2024-99999"),
                article("CVE-2024-1 is not an ID")
            )
        )
        assertEquals(listOf("CVE-2024-3094", "CVE-2024-21412"), ids)
        assertEquals(listOf("CVE-2025-1234567"), CveBadgeProvider.cveIds(null, listOf(article("x", fullText = "see CVE-2025-1234567."))))
        assertTrue(CveBadgeProvider.cveIds(null, listOf(article("CVE-2024-1 and CVE-2024-123"))).isEmpty())
        assertEquals(listOf("CVE-2024-0001", "CVE-2024-0002"), CveBadgeProvider.cveIds(null, listOf(article("x", description = "CVE-2024-0003"), article("CVE-2024-0001 CVE-2024-0002"))))
    }

    @Test
    fun `one analyzed and exploited CVE`() {
        FakeApi().route("/rest/json/cves/2.0", log4shell).start().use { api ->
            val badge = provider(api).badge(item(coreFact = "CVE-2021-44228 exploited"), emptyList())
            assertEquals(
                "🛡 [CVE-2021-44228](https://nvd.nist.gov/vuln/detail/CVE-2021-44228): CVSS 10.0, критична · експлуатується (CISA KEV)",
                badge?.text
            )
            assertEquals("/rest/json/cves/2.0?cveId=CVE-2021-44228", api.requests.single())
            assertTrue("apikey" !in api.headers.single())
        }
    }

    @Test
    fun `v4 before v3_1, Primary before Secondary, v2 severity next to cvssData`() {
        FakeApi().start().use { api ->
            val p = provider(api)
            val v4 = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"vulnStatus":"Analyzed","metrics":{
                  "cvssMetricV31":[{"type":"Primary","cvssData":{"baseScore":7.5,"baseSeverity":"HIGH"}}],
                  "cvssMetricV40":[{"type":"Secondary","cvssData":{"baseScore":9.3,"baseSeverity":"CRITICAL"}},
                                   {"type":"Primary","cvssData":{"baseScore":8.7,"baseSeverity":"HIGH"}}]}}"""
            ) as kotlinx.serialization.json.JsonObject
            assertEquals(CveBadgeProvider.Record(8.7, "HIGH", kev = false, analyzed = true), p.parse(v4))
            val v2 = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"vulnStatus":"Modified","metrics":{"cvssMetricV2":[{"type":"Primary","cvssData":{"baseScore":5.0},"baseSeverity":"MEDIUM"}]}}"""
            ) as kotlinx.serialization.json.JsonObject
            assertEquals(CveBadgeProvider.Record(5.0, "MEDIUM", kev = false, analyzed = true), p.parse(v2))
        }
    }

    @Test
    fun `two IDs share one line, a CNA-only fresh CVE and an unscored one`() {
        val fresh = cve("CVE-2026-1111", "Awaiting Analysis",
            """{"cvssMetricV31":[{"type":"Secondary","cvssData":{"baseScore":7.5,"baseSeverity":"HIGH"}}]}""")
        FakeApi().route("/rest/json/cves/2.0?cveId=CVE-2021-44228", log4shell)
            .route("/rest/json/cves/2.0?cveId=CVE-2026-1111", fresh).start().use { api ->
                val badge = provider(api).badge(null, listOf(article("CVE-2021-44228 and CVE-2026-1111")))
                assertEquals(
                    "🛡 [CVE-2021-44228](https://nvd.nist.gov/vuln/detail/CVE-2021-44228): 10.0, критична, експлуатується · " +
                        "[CVE-2026-1111](https://nvd.nist.gov/vuln/detail/CVE-2026-1111): 7.5, висока",
                    badge?.text
                )
            }
        FakeApi().route("/rest/json/cves/2.0", cve("CVE-2026-2222", "Received")).start().use { api ->
            val cache = MemoryBadgeCache()
            assertEquals(
                "🛡 [CVE-2026-2222](https://nvd.nist.gov/vuln/detail/CVE-2026-2222): ще аналізується",
                provider(api, cache).badge(item(subject = "CVE-2026-2222"), emptyList())?.text
            )
            assertNull(cache.get("cve", "CVE-2026-2222", Duration.ofDays(1)))
            assertEquals(";;0;0", cache.get("cve_pending", "CVE-2026-2222", Duration.ofHours(1)))
        }
    }

    @Test
    fun `unknown to NVD is no line and a cached miss`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/rest/json/cves/2.0", unknown).start().use { api ->
            val p = provider(api, cache)
            assertNull(p.badge(item(subject = "CVE-2099-0001"), emptyList()))
            assertEquals("1", cache.get("cve_miss", "CVE-2099-0001", Duration.ofHours(1)))
            assertNull(p.badge(item(subject = "CVE-2099-0001"), emptyList()))
            assertEquals(1, api.hits.get())
        }
    }

    @Test
    fun `an analyzed record is cached, a failure is not and does not sink the other ID`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/rest/json/cves/2.0?cveId=CVE-2021-44228", log4shell)
            .route("/rest/json/cves/2.0?cveId=CVE-2026-3333", "busy", status = 503).start().use { api ->
                val p = provider(api, cache)
                val text = p.badge(item(subject = "CVE-2026-3333 / CVE-2021-44228"), emptyList())?.text
                assertEquals("🛡 [CVE-2021-44228](https://nvd.nist.gov/vuln/detail/CVE-2021-44228): CVSS 10.0, критична · експлуатується (CISA KEV)", text)
                assertEquals("10.0;CRITICAL;1;1", cache.get("cve", "CVE-2021-44228", Duration.ofHours(1)))
                assertNull(cache.get("cve_miss", "CVE-2026-3333", Duration.ofHours(1)))
                p.badge(item(subject = "CVE-2021-44228"), emptyList())
                assertEquals(2, api.hits.get())
            }
    }

    @Test
    fun `the keyless window allows 5 lookups per 30 s, then skips without waiting`() {
        val clock = AtomicLong(1_000_000)
        FakeApi().route("/rest/json/cves/2.0", unknown).start().use { api ->
            val p = provider(api, now = clock::get)
            for (n in 1..5) p.badge(item(subject = "CVE-2026-000$n"), emptyList())
            assertEquals(5, api.hits.get())
            assertNull(p.badge(item(subject = "CVE-2026-0006"), emptyList()))
            assertEquals(5, api.hits.get())
            clock.addAndGet(30_000)
            p.badge(item(subject = "CVE-2026-0006"), emptyList())
            assertEquals(6, api.hits.get())
        }
    }

    @Test
    fun `the apiKey header only with a key, which also widens the window`() {
        FakeApi().route("/rest/json/cves/2.0", unknown).start().use { api ->
            val p = provider(api, key = "nvd-key", now = { 0L })
            for (n in 1..6) p.badge(item(subject = "CVE-2026-100$n"), emptyList())
            assertEquals(6, api.hits.get())
            assertTrue(api.headers.all { it["apikey"] == "nvd-key" })
        }
        FakeApi().start().use { api ->
            assertFalse(provider(api).badge(item(subject = "no ids here"), emptyList()) != null)
            assertEquals(0, api.hits.get())
        }
    }
}
