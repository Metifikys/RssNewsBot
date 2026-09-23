package metifikys.fetch

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.model.HtmlText
import org.jsoup.Jsoup
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * The social-post counterpart of [RedditLinkExtractor] (`repointLinks: true` on a feed): a short
 * post whose only content is "look at this" plus ONE outbound link stands for the article it links
 * to. That article becomes the primary link — so link-dedup collapses the post against the
 * publisher's own feed item and enrichment fetches the real text — while the post URL is kept in
 * the article meta.
 *
 * A post is left alone (null) when it is long (a studio's own announcement IS the source), links
 * nowhere or to several places, or its one link points at another social network, a store page,
 * the post's own host, the converter's host or a private address.
 */
object SocialLinkExtractor {

    /** Longer posts carry their own content; they stay the article. */
    const val MAX_POST_CHARS = 280

    /** Hosts that are never "the article": social networks, video, chat, link-in-bio. */
    private val SOCIAL_HOSTS = setOf(
        "t.me", "telegram.me", "telegram.org", "x.com", "twitter.com", "bsky.app", "youtube.com", "youtu.be",
        "instagram.com", "facebook.com", "fb.com", "fb.watch", "tiktok.com", "threads.net", "discord.gg",
        "discord.com", "reddit.com", "redd.it", "vk.com", "linktr.ee", "twitch.tv"
    )

    /** Store pages: an announcement repointed at its store page would lose the announcement. */
    private val STORE_HOSTS = setOf(
        "store.steampowered.com", "steamcommunity.com", "play.google.com", "apps.apple.com", "store.epicgames.com",
        "store.playstation.com", "xbox.com", "nintendo.com", "gog.com", "amazon.com", "patreon.com", "boosty.to"
    )

    /** Same ranges as the fetcher's SSRF guard: a post must never point the article fetcher at the LAN. */
    private val PRIVATE_HOST = Regex(
        "^(localhost|127\\..*|10\\..*|192\\.168\\..*|172\\.(1[6-9]|2[0-9]|3[01])\\..*|169\\.254\\..*|\\[?::1]?|0\\.0\\.0\\.0)$",
        RegexOption.IGNORE_CASE
    )

    private val BARE_URL = Regex("""https?://[^\s<>"'«»]+""")

    /**
     * @param link the outbound article, canonicalized through [LinkNormalizer]
     * @param postUrl the post it came from
     * @param account the posting account parsed from [postUrl] (t.me / x.com / bsky / Mastodon), or null
     */
    data class Rewrite(val link: String, val postUrl: String, val account: String?)

    /**
     * Repoints a short single-link post at its link, or returns null to keep the post as it is.
     * [feedUrl] is the converted feed's own URL (its host — the converter — is never a target).
     */
    fun rewrite(permalink: String, descriptionHtml: String, feedUrl: String): Rewrite? {
        if (descriptionHtml.isBlank()) return null
        val text = HtmlText.strip(descriptionHtml)
        if (text.length >= MAX_POST_CHARS) return null
        val excluded = setOfNotNull(hostOf(permalink), hostOf(feedUrl))
        val targets = outboundLinks(descriptionHtml, permalink)
            .filter { isArticleTarget(it, excluded) }
            .map { LinkNormalizer.normalize(it) }
            .distinct()
        val target = targets.singleOrNull() ?: return null
        return Rewrite(link = target, postUrl = permalink, account = accountOf(permalink))
    }

    /** Every http(s) link of the post: anchors (resolved against [baseUrl]) and bare URLs in the text. */
    internal fun outboundLinks(descriptionHtml: String, baseUrl: String): List<String> {
        val links = try {
            val doc = Jsoup.parseBodyFragment(descriptionHtml, baseUrl)
            val anchors = doc.select("a[href]").map { it.absUrl("href").trim() }
            // Bare URLs only OUTSIDE anchors: an anchor's text is often its own href, shortened with "…".
            doc.select("a").remove()
            anchors + BARE_URL.findAll(doc.text()).map { it.value.trimEnd('.', ',', ')', '!', '?', ';', ':', '…') }
        } catch (e: Exception) {
            logger.debug(e) { "[Social] failed to parse the post $baseUrl" }
            emptyList()
        }
        return links.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun isArticleTarget(url: String, excluded: Set<String>): Boolean {
        val host = hostOf(url) ?: return false
        if (host in excluded || PRIVATE_HOST.matches(host)) return false
        fun under(set: Set<String>) = set.any { host == it || host.endsWith(".$it") }
        return !under(SOCIAL_HOSTS) && !under(STORE_HOSTS)
    }

    /** `t.me/<account>/<id>`, `t.me/s/<account>/<id>`, `x.com/<account>/status/<id>`, `bsky.app/profile/<h>/…`, `/@user/…`. */
    fun accountOf(postUrl: String): String? {
        val uri = try { URI(postUrl.trim()) } catch (e: Exception) { return null }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val seg = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val account = when {
            host == "t.me" || host == "telegram.me" -> if (seg.firstOrNull() == "s") seg.getOrNull(1) else seg.firstOrNull()
            host == "x.com" || host == "twitter.com" -> seg.firstOrNull()
            host == "bsky.app" -> if (seg.firstOrNull() == "profile") seg.getOrNull(1) else null
            seg.firstOrNull()?.startsWith("@") == true -> seg.first().removePrefix("@")
            else -> null
        }
        return account?.takeIf { it.isNotBlank() }?.let { "$host/$it" }
    }

    private fun hostOf(url: String): String? = try {
        URI(url.trim()).host?.lowercase()?.removePrefix("www.")
    } catch (e: Exception) {
        null
    }
}
