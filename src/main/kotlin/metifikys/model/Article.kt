package metifikys.model

import java.time.LocalDateTime

data class Article(
    val category: String,
    val title: String,
    val link: String,
    val description: String,
    val pubDate: LocalDateTime = LocalDateTime.now(),
    /** Optional image URL extracted from the RSS entry (enclosure, media:content/thumbnail, or first <img> in description). */
    val imageUrl: String? = null,
    /** Persisted. LLM-generated summary, populated by ArticleSummarizer when feed has `summarize:` set. */
    val summary: String? = null,
    /** Ephemeral flag — not persisted to DB. Set from [FeedConfig][metifikys.config.FeedConfig.fetchFullContent]. */
    val fetchFullContent: Boolean = false,
    /** Ephemeral — not persisted. Provider name from [FeedConfig.summarize][metifikys.config.FeedConfig.summarize]. */
    val summarize: String? = null,
    /** Persisted. Source-specific signals captured at ingestion, see [ArticleMeta]. Null for most feeds. */
    val metaJson: String? = null,
    /** Persisted as `articles.feed_url`: the configured feed `url` this entry came from (null on older rows). */
    val feedUrl: String? = null,
    /**
     * Ephemeral — not persisted. Article page text refetched right before render for a
     * long-form event (`post.longForm`); the renderer uses it instead of [promptText].
     */
    val fullText: String? = null
) {
    /** Parsed [metaJson]; null when absent or malformed. */
    fun meta(): ArticleMeta? = ArticleMeta.parse(metaJson)

    /**
     * Body text used in digest LLM prompts: prefer LLM summary when present, otherwise the
     * description. The description is HTML-stripped defensively — new rows are already stored
     * clean by RssFetcher, but rows written before that change (and any path that bypasses it)
     * still carry raw feed markup, which would eat the [maxChars] budget.
     */
    fun promptText(maxChars: Int = 1000): String =
        (summary?.takeIf { it.isNotBlank() } ?: HtmlText.strip(description)).take(maxChars)
}
