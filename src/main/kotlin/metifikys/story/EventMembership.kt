package metifikys.story

import metifikys.ai.PromptBuilder
import metifikys.model.Article
import metifikys.model.ExtractionItem
import metifikys.model.ShortlistItem

/**
 * The one place that maps Step 1's output back onto the batch articles: which article went into
 * which event. Pure. Both consumers go through it so the join rules cannot drift apart:
 * `event_articles` (the persisted cluster, `storyIntel.enabled`) and [ShortlistItem.sourceLinks]
 * (the same mapping in memory, carried with the shortlist to delivery).
 *
 * Join rules:
 * - an extraction joins its article by `url`; a `url` that is not in the batch is a model slip and
 *   the extraction is dropped (a cluster may come out incomplete, never with a wrong member);
 * - only an extraction without a `url` falls back to its `articleIndex`, read 0-based unless the
 *   batch's own url/index pairs show Step 1 counted from 1 ([indexOffset]);
 * - a shortlist item's `articleIndices` resolve exactly as the render prompt resolves them
 *   ([PromptBuilder.sourceLinksOf]); an article they name without an extraction of its own joins
 *   the item's event. An article's own extraction wins over a shortlist's claim.
 */
object EventMembership {

    /** One batch article and the event Step 1 put it in; [step1Status] is the extraction's status (or the item's). */
    data class Member(val link: String, val eventKey: String, val step1Status: String)

    const val STATUS_REJECTED = "rejected"

    fun resolve(extractions: List<ExtractionItem>, shortlist: List<ShortlistItem>, articles: List<Article>): List<Member> {
        if (articles.isEmpty()) return emptyList()
        val byLink = LinkedHashMap<String, Member>()
        val offset = indexOffset(extractions, articles)
        for (e in extractions) {
            if (e.eventKey.isBlank()) continue
            val article = articleOf(e, articles, offset) ?: continue
            byLink.putIfAbsent(article.link, Member(article.link, e.eventKey, e.status))
        }
        for (item in shortlist) {
            for (link in PromptBuilder.sourceLinksOf(item, articles)) {
                byLink.putIfAbsent(link, Member(link, item.eventKey, item.status))
            }
        }
        return byLink.values.toList()
    }

    /**
     * [ShortlistItem.sourceLinks] for [item]: the articles its `articleIndices` name (the render
     * prompt's own resolution, so it matches what the renderer saw), then every other batch article
     * [members] put in the same event — the in-batch copies Step 1 marked `duplicate` — except rejects.
     */
    fun sourceLinks(item: ShortlistItem, members: List<Member>, articles: List<Article>): List<String> =
        (PromptBuilder.sourceLinksOf(item, articles) +
            members.filter { it.eventKey == item.eventKey && it.step1Status != STATUS_REJECTED }.map { it.link })
            .distinct()

    private fun articleOf(e: ExtractionItem, articles: List<Article>, offset: Int): Article? {
        val url = e.url.trim()
        if (url.isNotEmpty()) return articles.firstOrNull { it.link == url }
        return articles.getOrNull(e.articleIndex - offset)
    }

    /**
     * 1 when Step 1 numbered this batch from 1, else 0. Decided per batch, like the render prompt
     * does per item: the extractions whose `url` is in the batch vote for the reading their
     * `articleIndex` agrees with; without any such anchor, indices that only fit 1-based
     * (no 0, one equal to the batch size) mean 1-based.
     */
    internal fun indexOffset(extractions: List<ExtractionItem>, articles: List<Article>): Int {
        val indexOfLink = HashMap<String, Int>()
        articles.forEachIndexed { i, a -> indexOfLink.putIfAbsent(a.link, i) }
        var zeroBased = 0
        var oneBased = 0
        for (e in extractions) {
            val i = indexOfLink[e.url.trim()] ?: continue
            when (e.articleIndex) {
                i -> zeroBased++
                i + 1 -> oneBased++
            }
        }
        if (zeroBased != oneBased) return if (oneBased > zeroBased) 1 else 0
        val indices = extractions.map { it.articleIndex }.filter { it >= 0 }
        return if (indices.isNotEmpty() && 0 !in indices && articles.size in indices) 1 else 0
    }
}
