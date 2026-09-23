package metifikys.fetch

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The YouTube video an article embeds (the ▶️ trailer badge). Pure: parses HTML, fetches nothing.
 *
 * Recognised, embeds before plain links: `iframe` `src` / `data-src` on `youtube.com/embed/` or
 * `youtube-nocookie.com/embed/`, `lite-youtube[videoid]`, `[data-youtube-id]`; then links to
 * `youtu.be/<id>`, `youtube.com/watch?v=<id>`, `youtube.com/shorts/<id>`. On a full page only the
 * article body is searched — the first [ArticleFetcher.ARTICLE_SELECTORS] match, with `aside`,
 * navigation and "related" / "recommended" / "promo" / "sidebar" blocks removed — so the site's own
 * promo video in a sidebar is not taken for the article's; a page without such a body falls back
 * to its `og:video` meta only. Nothing here proves the video is about the story: the caller checks
 * the title (YouTube oEmbed).
 */
object VideoLinks {

    private const val ID = """([A-Za-z0-9_-]{11})"""
    private val EMBED = Regex("""youtube(?:-nocookie)?\.com/embed/$ID""", RegexOption.IGNORE_CASE)
    private val LINK = Regex(
        """(?:youtu\.be/$ID|youtube\.com/watch\?(?:[^#"'\s]*&(?:amp;)?)?v=$ID|youtube\.com/shorts/$ID)""",
        RegexOption.IGNORE_CASE
    )
    private val BARE_ID = Regex("""^$ID$""")

    private const val NOISE =
        "script, style, nav, header, footer, aside, [class*=related], [class*=recommend], [class*=promo], " +
            "[class*=sidebar], [class*=newsletter], [id*=related], [id*=sidebar]"

    /** The video of a full article page, or null. */
    fun fromPage(html: String, baseUrl: String = ""): String? {
        val doc = Jsoup.parse(html, baseUrl)
        val ogVideo = listOf("meta[property=og:video]", "meta[property=og:video:url]", "meta[property=og:video:secure_url]")
            .firstNotNullOfOrNull { sel -> doc.selectFirst(sel)?.attr("content")?.let(::idInUrl) }
        doc.select(NOISE).remove()
        val body = ArticleFetcher.ARTICLE_SELECTORS.firstNotNullOfOrNull { doc.selectFirst(it) }
        return body?.let(::inElement) ?: ogVideo
    }

    /** The video of an HTML fragment (a feed item's description), searched whole. */
    fun fromFragment(html: String): String? {
        if (!html.contains("youtu", ignoreCase = true)) return null
        return inElement(Jsoup.parseBodyFragment(html).body())
    }

    private fun inElement(root: Element): String? {
        for (e in root.select("iframe, lite-youtube, [data-youtube-id]")) {
            val id = when {
                e.tagName() == "iframe" -> listOf(e.attr("src"), e.attr("data-src")).firstNotNullOfOrNull { src ->
                    EMBED.find(src)?.groupValues?.get(1)
                }
                e.tagName() == "lite-youtube" -> e.attr("videoid").takeIf { BARE_ID.matches(it) }
                else -> e.attr("data-youtube-id").takeIf { BARE_ID.matches(it) }
            }
            if (id != null) return id
        }
        return root.select("a[href]").firstNotNullOfOrNull { idInUrl(it.attr("href")) }
    }

    /** The video id in a YouTube URL (embed, watch, youtu.be, shorts), or null. */
    fun idInUrl(url: String): String? {
        EMBED.find(url)?.let { return it.groupValues[1] }
        val m = LINK.find(url) ?: return null
        return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }
}
