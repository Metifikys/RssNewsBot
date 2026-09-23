package metifikys.enrich

import metifikys.fetch.UrlGuard
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubBadgeProviderTest {

    private fun article(link: String) = Article(category = "tech", title = "t", link = link, description = "d")

    private fun provider(api: FakeApi, cache: BadgeCache = MemoryBadgeCache(), token: String? = null) =
        GitHubBadgeProvider(BadgeHttp(timeoutSeconds = 3, guard = UrlGuard.OFF), cache, token = token, apiBaseUrl = api.baseUrl)

    private val repoJson = """{"full_name":"asg017/sqlite-vec","stargazers_count":12345,"language":"C","pushed_at":"2026-09-03T10:15:00Z"}"""

    @Test
    fun `renders stars, language and last push for a repository link`() {
        FakeApi().route("/repos/asg017/sqlite-vec", repoJson).start().use { api ->
            val badge = provider(api).badge(null, listOf(article("https://github.com/asg017/sqlite-vec?tab=readme")))
            assertEquals("⭐ [GitHub](https://github.com/asg017/sqlite-vec): 12.3k · C · оновлено 03.09.2026", badge?.text)
        }
    }

    @Test
    fun `the item url counts too, and a token is sent as a bearer`() {
        FakeApi().route("/repos/o/r", """{"stargazers_count":42}""").start().use { api ->
            val item = ShortlistItem(eventKey = "e", coreFact = "f", url = "https://github.com/o/r", status = "new")
            val badge = provider(api, token = "ghp_x").badge(item, listOf(article("https://example.com/1")))
            assertEquals("⭐ [GitHub](https://github.com/o/r): 42", badge?.text)
        }
    }

    @Test
    fun `non-repository github links and other hosts are ignored`() {
        FakeApi().start().use { api ->
            val p = provider(api)
            assertNull(p.badge(null, listOf(article("https://github.com/topics/rust"))))
            assertNull(p.badge(null, listOf(article("https://github.com/features/copilot"))))
            assertNull(p.badge(null, listOf(article("https://example.com/github.com/o/r"))))
            assertNull(p.badge(null, listOf(article("https://gist.github.com/o/abc"))))
            assertEquals(0, api.hits.get())
        }
    }

    @Test
    fun `404 is cached as a negative, a 5xx is not`() {
        val cache = MemoryBadgeCache()
        FakeApi().route("/repos/gone/repo", "", status = 404).route("/repos/down/repo", "x", status = 502).start().use { api ->
            val p = provider(api, cache)
            assertNull(p.badge(null, listOf(article("https://github.com/gone/repo"))))
            assertEquals("", cache.get("github", "gone/repo", Duration.ofHours(1)))
            assertNull(p.badge(null, listOf(article("https://github.com/down/repo"))))
            assertNull(cache.get("github", "down/repo", Duration.ofHours(1)))
        }
    }

    @Test
    fun `repoOf and formatStars`() {
        assertEquals("o" to "r", GitHubBadgeProvider.repoOf("https://github.com/o/r"))
        assertEquals("o" to "r", GitHubBadgeProvider.repoOf("http://www.github.com/o/r.git"))
        assertEquals("o" to "r", GitHubBadgeProvider.repoOf("https://github.com/o/r/#readme"))
        assertNull(GitHubBadgeProvider.repoOf("https://github.com/o"))
        assertNull(GitHubBadgeProvider.repoOf("https://github.com/orgs/o/repositories"))
        assertEquals("999", GitHubBadgeProvider.formatStars(999))
        assertEquals("1k", GitHubBadgeProvider.formatStars(1000))
        assertEquals("12.3k", GitHubBadgeProvider.formatStars(12345))
        assertEquals("1.2M", GitHubBadgeProvider.formatStars(1_234_567))
    }
}
