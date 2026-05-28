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
    val summarize: String? = null
) {
    /** Body text used in digest LLM prompts: prefer LLM summary when present, otherwise raw description. */
    fun promptText(maxChars: Int = 1000): String =
        (summary?.takeIf { it.isNotBlank() } ?: description).take(maxChars)
}
