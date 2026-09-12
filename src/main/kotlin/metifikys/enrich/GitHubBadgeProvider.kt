package metifikys.enrich

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import metifikys.model.Article
import metifikys.model.ShortlistItem
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * `⭐ [GitHub](repo): 12.3k · Rust · оновлено 03.09.2026`
 *
 * For a bullet whose article link (or the item's URL) is a GitHub repository — common on HN
 * and lobste.rs. `GET /repos/{owner}/{repo}` is public: 60 requests/hour per IP without a
 * token, 5000 with one (`badges.githubToken` / `GITHUB_TOKEN`). A 404 is cached as a negative.
 */
class GitHubBadgeProvider(
    private val http: BadgeHttp,
    private val cache: BadgeCache,
    private val token: String? = null,
    private val apiBaseUrl: String = "https://api.github.com"
) : BadgeProvider {

    override val id: String = ID

    companion object {
        const val ID = "github"
        private val TTL: Duration = Duration.ofHours(24)
        private const val NEGATIVE = ""
        private val REPO = Regex("""^https?://(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?(?:[#?].*)?$""")

        /** First path segments that are GitHub pages, not repository owners. */
        private val NOT_OWNERS = setOf(
            "orgs", "topics", "features", "marketplace", "sponsors", "settings", "login", "about", "blog",
            "trending", "explore", "search", "collections", "events", "site", "pulls", "issues",
            "notifications", "new", "apps", "enterprise", "team", "pricing", "security", "readme",
            "copilot", "codespaces", "customer-stories", "contact", "join", "signup", "account"
        )
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        /** `owner/repo` for a repository URL, null for anything else on github.com. */
        internal fun repoOf(url: String): Pair<String, String>? {
            val m = REPO.find(url.trim()) ?: return null
            val owner = m.groupValues[1]
            val repo = m.groupValues[2]
            if (owner.lowercase() in NOT_OWNERS || repo.isEmpty()) return null
            return owner to repo
        }

        /** `12345` → `12.3k`; locale-independent (the JVM default may use a decimal comma). */
        internal fun formatStars(n: Long): String = when {
            n >= 1_000_000 -> "${String.format(Locale.ROOT, "%.1f", n / 1_000_000.0).removeSuffix(".0")}M"
            n >= 1_000 -> "${String.format(Locale.ROOT, "%.1f", n / 1_000.0).removeSuffix(".0")}k"
            else -> n.toString()
        }
    }

    override fun badge(item: ShortlistItem?, articles: List<Article>): Badge? {
        val candidates = articles.map { it.link } + listOfNotNull(item?.url)
        val (owner, repo) = candidates.firstNotNullOfOrNull { repoOf(it) } ?: return null
        val key = "$owner/$repo".lowercase()
        cache.get(id, key, TTL)?.let { cached -> return cached.takeIf { it.isNotEmpty() }?.let(::Badge) }

        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            token?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        val json = try {
            http.getJson("$apiBaseUrl/repos/$owner/$repo", headers)?.jsonObject
        } catch (e: Exception) {
            logger.warn { "[Badge:github] lookup failed for $owner/$repo: ${e.message}" }
            return null   // transient (rate limit included): do not cache
        }
        if (json == null) {
            cache.put(id, key, NEGATIVE)
            return null
        }
        val stars = json["stargazers_count"]?.jsonPrimitive?.longOrNull ?: return null
        val parts = mutableListOf(formatStars(stars))
        json["language"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { parts += it }
        json["pushed_at"]?.jsonPrimitive?.contentOrNull?.let { raw ->
            runCatching { OffsetDateTime.parse(raw).format(DATE) }.getOrNull()?.let { parts += "оновлено $it" }
        }
        val text = "⭐ [GitHub](https://github.com/$owner/$repo): ${parts.joinToString(" · ")}"
        cache.put(id, key, text)
        return Badge(text)
    }
}
