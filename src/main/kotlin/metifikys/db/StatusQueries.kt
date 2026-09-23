package metifikys.db

/**
 * Per-category article outcome counts produced by [NewsDatabase.fetchArticleStatusCounts]
 * within a time window (windowed by `pubDate`): [articles] = rows in status PROCESSED,
 * [blocked] = rows the semantic/event dedup filter hard-rejected (status DUPLICATE).
 *
 * NOTE: [articles] counts articles by RSS `pubDate`, not by when they reached the channel.
 * For the actual publish-event count see [NewsDatabase.fetchPublishedTopicCounts].
 */
data class CategoryArticleCounts(
    val category: String,
    val articles: Long,
    val blocked: Long
)
