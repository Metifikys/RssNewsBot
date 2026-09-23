package metifikys.format

import metifikys.config.AlsoConfig
import metifikys.model.ShortlistItem

/**
 * `📰 Також: [IGN](…) · [PC Gamer](…)` — the other outlets whose batch articles Step 1 folded into
 * the event ([ShortlistItem.sourceLinks]), see [AlsoConfig]. Pure: no DB, no network.
 */
object AlsoLine {

    /** One other outlet: its host (no `www.`), display name and the article link. */
    data class Source(val host: String, val name: String, val url: String)

    /**
     * The event's other outlets, in [ShortlistItem.sourceLinks] order: not the post's own host, one
     * link per host and per name, at most [AlsoConfig.max]. [feedLabelOf] gives the `label` of the
     * feed an article link came from, used as the name when the feed lives on that host
     * ([hostOf] of the feed URL equals the article's).
     */
    fun sources(
        item: ShortlistItem,
        cfg: AlsoConfig,
        feedLabelOf: (String) -> Pair<String, String>? = { null }
    ): List<Source> {
        val mainHost = hostOf(item.url)
        val seenHosts = mutableSetOf(mainHost)
        val seenNames = mutableSetOf<String>()
        val out = mutableListOf<Source>()
        for (link in item.sourceLinks) {
            if (out.size >= cfg.max) break
            if (link == item.url) continue
            val host = hostOf(link)
            if (host.isEmpty() || !seenHosts.add(host)) continue
            if (TopicFormatter.MARKDOWN_LINK.matchEntire("[x]($link)") == null) continue
            val feedLabel = feedLabelOf(link)?.takeIf { (feedUrl, _) -> hostOf(feedUrl) == host }?.second
            val name = nameOf(host, cfg.names, feedLabel)
            if (!seenNames.add(name.lowercase())) continue
            out += Source(host, name, link)
        }
        return out
    }

    /** The footer line for [sources], or null when there are none. */
    fun line(sources: List<Source>): String? {
        if (sources.isEmpty()) return null
        return "📰 Також: " + sources.joinToString(" · ") { "[${it.name}](${it.url})" }
    }

    /** Lower-case host without a leading `www.`; "" for a URL without one. */
    fun hostOf(url: String): String = try {
        java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.")?.removeSuffix(".").orEmpty()
    } catch (e: Exception) {
        ""
    }

    /**
     * [names] for [host] or its closest parent domain (`ign.com` covers `uk.ign.com`), else
     * [feedLabel], else [derivedName]. Brackets and Markdown markers are taken out of the label.
     */
    fun nameOf(host: String, names: Map<String, String>, feedLabel: String? = null): String {
        val byHost = names.entries.associate { (k, v) -> k.lowercase().removePrefix("www.") to v }
        var h = host
        var configured: String? = null
        while (h.contains('.')) {
            configured = byHost[h]
            if (configured != null) break
            h = h.substringAfter('.')
        }
        val raw = configured ?: feedLabel?.takeIf { it.isNotBlank() } ?: derivedName(host)
        return raw.replace('[', '(').replace(']', ')').replace("*", "").replace("`", "").trim()
            .ifEmpty { host }
    }

    /**
     * The domain without its TLD, capitalised: `pcgamer.com` → `Pcgamer`, `blog.playstation.com` →
     * `Playstation`, `bbc.co.uk` → `Bbc` (a two-letter country TLD under `co` / `com` / `org` /
     * `net` / `ac` / `gov` counts as one suffix).
     */
    fun derivedName(host: String): String {
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return host
        val secondLevelSuffix = labels.size >= 3 && labels.last().length == 2 &&
            labels[labels.size - 2] in SECOND_LEVEL
        val name = if (secondLevelSuffix) labels[labels.size - 3] else labels[labels.size - 2]
        return name.replaceFirstChar { it.uppercase() }
    }

    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "ac", "gov")
}
