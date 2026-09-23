package metifikys.db

import metifikys.model.ArticleStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

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

/** Cross-table read-only aggregates for `/status` (articles + summaries); no table of their own. */
interface StatusQueryStore {
    /**
     * Per-category article outcome counts over the last [sinceHours] hours, windowed by
     * `pubDate`. Each entry reports PROCESSED (published) and DUPLICATE (dedup-blocked)
     * counts. Categories with no in-window rows are absent from the map.
     */
    fun fetchArticleStatusCounts(sinceHours: Long): Map<String, CategoryArticleCounts>

    /**
     * Per-category SUM of `articleCount` over digests actually published (windowed by
     * `SummariesTable.createdAt`) in the last [sinceHours]. Each topic in a digest is one
     * channel message, so this approximates the post count visible on the channel.
     * Categories with no in-window digests are absent from the map.
     */
    fun fetchPublishedTopicCounts(sinceHours: Long): Map<String, Long>
}

internal class StatusQueryRepository : StatusQueryStore {

    override fun fetchArticleStatusCounts(sinceHours: Long): Map<String, CategoryArticleCounts> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val countExpr = ArticlesTable.id.count()
        val rows = transaction {
            ArticlesTable
                .select(ArticlesTable.category, ArticlesTable.status, countExpr)
                .where { ArticlesTable.pubDate greaterEq cutoff }
                .groupBy(ArticlesTable.category, ArticlesTable.status)
                .map { Triple(it[ArticlesTable.category], it[ArticlesTable.status], it[countExpr]) }
        }
        val byCategory = mutableMapOf<String, MutableMap<String, Long>>()
        for ((cat, status, cnt) in rows) {
            byCategory.getOrPut(cat) { mutableMapOf() }[status] = cnt
        }
        return byCategory.mapValues { (cat, statusCounts) ->
            CategoryArticleCounts(
                category = cat,
                articles = statusCounts[ArticleStatus.PROCESSED.name] ?: 0L,
                blocked = statusCounts[ArticleStatus.DUPLICATE.name] ?: 0L
            )
        }
    }

    override fun fetchPublishedTopicCounts(sinceHours: Long): Map<String, Long> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val sumExpr = SummariesTable.articleCount.sum()
        return transaction {
            SummariesTable
                .select(SummariesTable.category, sumExpr)
                .where { SummariesTable.createdAt greaterEq cutoff }
                .groupBy(SummariesTable.category)
                .mapNotNull { row ->
                    val s = row[sumExpr] ?: return@mapNotNull null
                    row[SummariesTable.category] to s.toLong()
                }
                .toMap()
        }
    }
}
