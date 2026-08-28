package metifikys.fetch

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Recovers the real article behind a Reddit **link post**.
 *
 * Reddit's RSS gives a link post no content of its own — the whole `<description>` is the
 * boilerplate card reddit renders for every submission:
 *
 * ```html
 * <table><tr><td> <a href="…/comments/1vovlwd/…"><img src="…external-preview.redd.it…"/></a> </td>
 * <td> &#32; submitted by &#32; <a href="…/user/malcolm58"> /u/malcolm58 </a> <br/>
 * <span><a href="https://www.scmp.com/news/china/science/article/3363975/…">[link]</a></span>
 * &#32; <span><a href="…/comments/1vovlwd/…">[comments]</a></span> </td></tr></table>
 * ```
 *
 * So the story itself — the SCMP article — is reachable only through the `[link]` anchor, and
 * everything downstream (Step-1 extraction, the digest render, the published URL) sees a title
 * and ~500 chars of markup. In `logs/news.db` that is 14.4k of 26.6k reddit rows.
 *
 * [rewrite] turns such an entry into the article it points at: the outbound URL becomes the
 * primary [Article][metifikys.model.Article] link — which also makes link-dedup collapse a
 * reddit crosspost against the publisher's own feed item — the boilerplate is dropped, and
 * full-content fetching is forced on so [ArticleFetcher] fills the now-empty description from
 * the real page.
 *
 * A **self post** is left untouched: its `[link]` anchor points back at the permalink, so the
 * reddit page really is the content.
 */
object RedditLinkExtractor {

    /**
     * Hosts whose pages are reddit itself. A `[link]` target here means the submission has no
     * external source — a self post (`[link]` = the permalink), a crosspost, or media hosted by
     * reddit (`i.redd.it`, `v.redd.it`). Rewriting to those trades a readable permalink for a
     * raw image or an identical page, so they stay on the permalink.
     */
    private val REDDIT_HOST = Regex("^(.+\\.)?(reddit\\.com|redd\\.it)$", RegexOption.IGNORE_CASE)

    /** The anchor text reddit uses for the outbound target of a submission. */
    private const val LINK_ANCHOR_TEXT = "[link]"

    /** Reddit wraps a submission's own body text in `<!-- SC_OFF --><div class="md">…`. */
    private const val BODY_SELECTOR = "div.md"

    /** True when [url] points at reddit — i.e. this entry came from a subreddit feed. */
    fun isRedditUrl(url: String): Boolean = hostOf(url)?.matches(REDDIT_HOST) == true

    /** Lowercase host of [url], or null when it is unparseable / has no authority. */
    private fun hostOf(url: String): String? = try {
        URI(url.trim()).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    /**
     * The external URL a reddit submission points at, or null when there is none (self post,
     * crosspost, reddit-hosted media) or when [permalink] is not a reddit URL at all.
     */
    fun outboundTarget(permalink: String, descriptionHtml: String): String? {
        if (descriptionHtml.isBlank() || !isRedditUrl(permalink)) return null
        val href = try {
            Jsoup.parseBodyFragment(descriptionHtml, permalink)
                .select("a[href]")
                .firstOrNull { it.text().trim() == LINK_ANCHOR_TEXT }
                ?.absUrl("href")
                ?.trim()
        } catch (e: Exception) {
            logger.debug(e) { "[Reddit] Failed to parse description of $permalink" }
            null
        }
        if (href.isNullOrBlank()) return null
        // Scheme guard mirrors RssFetcher.validateFeedUrl: the target becomes an article link
        // that ArticleFetcher will fetch, so never let a feed hand us a non-http(s) URL.
        val scheme = try {
            URI(href).scheme?.lowercase()
        } catch (e: Exception) {
            null
        }
        if (scheme != "http" && scheme != "https") return null
        return if (isRedditUrl(href)) null else href
    }

    /**
     * The submission's own body text, stripped of the "submitted by … [link] [comments]" footer.
     * Empty for a plain link post — reddit gives those no body at all — which is deliberately
     * preferred over keeping the boilerplate: the markup carries no information, costs Step-1
     * prompt budget, and smuggles reddit URLs into a render prompt that is told to use only the
     * URLs it was given.
     */
    fun bodyHtml(descriptionHtml: String, baseUrl: String): String =
        try {
            Jsoup.parseBodyFragment(descriptionHtml, baseUrl)
                .selectFirst(BODY_SELECTOR)
                ?.html()
                ?.trim()
                .orEmpty()
        } catch (e: Exception) {
            logger.debug(e) { "[Reddit] Failed to extract body from $baseUrl" }
            ""
        }

    /**
     * Result of [rewrite]: the outbound article a reddit link post stands for.
     *
     * @param link the external URL, already canonicalized through [LinkNormalizer]
     * @param description the submission's own body text, or empty for a plain link post
     */
    data class Rewrite(val link: String, val description: String)

    /**
     * Rewrites a reddit link post onto the article it points at, or returns null to leave the
     * entry exactly as the feed gave it (not reddit, a self post, or reddit-hosted media).
     */
    fun rewrite(permalink: String, descriptionHtml: String): Rewrite? {
        val target = outboundTarget(permalink, descriptionHtml) ?: return null
        return Rewrite(
            link = LinkNormalizer.normalize(target),
            description = bodyHtml(descriptionHtml, permalink)
        )
    }
}
