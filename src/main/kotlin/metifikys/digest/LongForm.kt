package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.ai.PromptBuilder
import metifikys.ai.dedup.ShortlistRanker
import metifikys.config.DigestConfig
import metifikys.config.LongFormConfig
import metifikys.model.Article
import metifikys.model.ShortlistItem

private val logger = KotlinLogging.logger {}

/**
 * Long-form posts (`post.longForm`): picks the digest's top events for a longer body and gives the
 * renderer the article text to write it from. See [LongFormConfig].
 */
object LongForm {

    /** Refetches one article page as text, cut to the given length; null on failure. */
    fun interface TextFetcher {
        fun fetch(url: String, maxChars: Int): String?
    }

    /**
     * Marks the long-form items of [shortlist]. The shortlist is already in ranker order, so the
     * first [LongFormConfig.maxPerDigest] items with a composite >= [LongFormConfig.minComposite]
     * win. Every other item's `depth` is cleared — it is code's field, never Step 1's.
     */
    fun mark(shortlist: List<ShortlistItem>, config: LongFormConfig, digestConfig: DigestConfig?): List<ShortlistItem> {
        var left = config.maxPerDigest
        return shortlist.map { item ->
            val long = config.enabled && left > 0 &&
                ShortlistRanker.compositeScore(item, digestConfig) >= config.minComposite
            if (long) left--
            val depth = if (long) ShortlistItem.DEPTH_LONG else null
            if (item.depth == depth) item else item.copy(depth = depth)
        }
    }

    /**
     * Fills [Article.fullText] for the articles behind the long items of [shortlist]: the item's
     * primary `url` first, then its other source articles, until one page yields text. Articles
     * that could not be fetched keep their RSS text — a long item then just has less to work with.
     */
    fun attachFullText(
        category: String,
        shortlist: List<ShortlistItem>,
        articles: List<Article>,
        config: LongFormConfig,
        fetcher: TextFetcher
    ): List<Article> {
        val fullTextByLink = mutableMapOf<String, String>()
        for (item in shortlist.filter { it.isLongForm }) {
            val sourceLinks = PromptBuilder.sourceLinksOf(item, articles)
            val candidates = (listOf(item.url) + sourceLinks).distinct()
            val found = candidates.firstNotNullOfOrNull { link ->
                fetcher.fetch(link, config.sourceChars)?.takeIf { it.isNotBlank() }?.let { link to it }
            }
            if (found == null) {
                logger.info { "[Category:$category][LongForm] ${item.eventKey}: no page text — renderer gets the RSS text" }
            } else {
                logger.info { "[Category:$category][LongForm] ${item.eventKey}: ${found.second.length} chars from ${found.first}" }
                fullTextByLink[found.first] = found.second
            }
        }
        if (fullTextByLink.isEmpty()) return articles
        return articles.map { a -> fullTextByLink[a.link]?.let { a.copy(fullText = it) } ?: a }
    }
}
