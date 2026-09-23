package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.PromptBuilder
import metifikys.config.OfficialConfig
import metifikys.format.AlsoLine
import metifikys.format.Hashtags
import metifikys.model.Article
import metifikys.model.ShortlistItem

private val logger = KotlinLogging.logger {}

/**
 * `post.official`: when an event's batch cluster holds the official source — a press release, the
 * studio or platform blog — the post links there instead of to the outlet retelling it. See
 * [OfficialConfig]. Deterministic, after the ranker (the affinity `source` dimension scores the
 * outlet Step 1 picked).
 */
object OfficialSource {

    /**
     * The official link among [item]'s cluster ([ShortlistItem.sourceLinks], only links in
     * [batchLinks]), or null when there is none or [ShortlistItem.url] already is one. The first
     * of [OfficialConfig.domains] (in list order; a host matches its domain or any subdomain) that
     * some candidate matches wins; with [OfficialConfig.matchEntityDomain], a host with a label
     * equal to the franchise / subject key (`hollowknight.com` for "Hollow Knight") comes after.
     */
    fun pick(item: ShortlistItem, cfg: OfficialConfig, batchLinks: Set<String>): String? {
        if (isOfficial(AlsoLine.hostOf(item.url), item, cfg)) return null
        val candidates = item.sourceLinks.filter { it != item.url && it in batchLinks }
            .map { it to AlsoLine.hostOf(it) }
            .filter { (_, host) -> host.isNotEmpty() }
        if (candidates.isEmpty()) return null
        for (domain in cfg.domains) {
            candidates.firstOrNull { (_, host) -> hostMatches(host, domain) }?.let { return it.first }
        }
        if (cfg.matchEntityDomain) {
            val keys = entityKeys(item)
            candidates.firstOrNull { (_, host) -> entityMatches(host, keys) }?.let { return it.first }
        }
        return null
    }

    /**
     * [pick] over the shortlist: a winning link becomes the item's `url`, and its `articleIndices`
     * are rewritten as 0-based indices into [articles] with the new link included — so the render
     * prompt shows the official article among the item's sources even when it was an in-batch
     * duplicate rather than one of Step 1's indices.
     */
    fun apply(category: String, shortlist: List<ShortlistItem>, articles: List<Article>, cfg: OfficialConfig): List<ShortlistItem> {
        if (!cfg.enabled) return shortlist
        val batchLinks = articles.map { it.link }.toSet()
        return shortlist.map { item ->
            val official = pick(item, cfg, batchLinks) ?: return@map item
            val linkIndex = articles.withIndex().associate { (i, a) -> a.link to i }
            val indices = (PromptBuilder.sourceLinksOf(item, articles) + official).mapNotNull { linkIndex[it] }.distinct()
            logger.info { "[Category:$category][Official] ${item.eventKey}: ${AlsoLine.hostOf(item.url)} → ${AlsoLine.hostOf(official)}" }
            item.copy(url = official, articleIndices = indices)
        }
    }

    private fun isOfficial(host: String, item: ShortlistItem, cfg: OfficialConfig): Boolean =
        host.isNotEmpty() && (cfg.domains.any { hostMatches(host, it) } || (cfg.matchEntityDomain && entityMatches(host, entityKeys(item))))

    /** `eu.blog.playstation.com` matches `blog.playstation.com`; `notplaystation.com` does not match `playstation.com`. */
    fun hostMatches(host: String, domain: String): Boolean {
        val d = domain.trim().lowercase().removePrefix("www.")
        return d.isNotEmpty() && (host == d || host.endsWith(".$d"))
    }

    /** Franchise / subject as a host label: the entity key without `_`, at least [MIN_ENTITY_CHARS] long. */
    fun entityKeys(item: ShortlistItem): Set<String> =
        listOf(item.franchise, item.subject)
            .mapNotNull { Hashtags.entityKey(it)?.replace("_", "") }
            .filter { it.length >= MIN_ENTITY_CHARS }
            .toSet()

    private fun entityMatches(host: String, keys: Set<String>): Boolean =
        keys.isNotEmpty() && host.split('.').dropLast(1).any { it in keys }

    const val MIN_ENTITY_CHARS = 5
}
