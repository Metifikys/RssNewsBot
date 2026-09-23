package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Story clusters (`storyIntel.enabled`): which articles the dedup folded into which event, from
 * which domain and when. One row per (category, article) — an article belongs to one event, and a
 * later decision about it (re-extraction after a hold-back, a Layer 3.5 merge) overwrites the row.
 *
 * Writers: Step 1 (`role = step1`, every extraction status, the shortlist's `articleIndices` for
 * articles without an extraction), the Layer 2 hard filter (`layer2`: a late reprint rejected
 * against an article whose event is known, with its cosine) and Layer 3.5 (moves the `step1` rows
 * of a dropped event under the covered event's key). Readers: the story footer lines (📢 / 🥇), the
 * weekly outlets count and the "who writes first" leaderboard.
 */
object EventArticlesTable : Table("event_articles") {
    val category = varchar("category", 100)
    val articleId = integer("article_id")
    val eventKey = varchar("event_key", 3000)
    val link = varchar("link", 2000)
    /** Link host, lowercased, `www.` stripped ([metifikys.feedback.AffinityMath.sourceDomain]). */
    val domain = varchar("domain", 255)
    /** The feed's own date (`articles.pub_date`). */
    val pubDate = datetime("pub_date")
    /** Our first-ingest clock (`articles.fetched_at`); null for articles older than that column. */
    val seenAt = datetime("seen_at").nullable()
    /** [EventArticleRole] value. */
    val role = varchar("role", 10)
    /** Step 1 status of the article's extraction (`new` / `duplicate` / …); null for Layer 2 rows. */
    val step1Status = varchar("step1_status", 30).nullable()
    /** Layer 2 cosine to the anchor article; null for Step 1 rows. */
    val sim = double("sim").nullable()
    val recordedAt = datetime("recorded_at").index()

    override val primaryKey = PrimaryKey(category, articleId)

    init {
        index("idx_event_articles_cat_event", false, category, eventKey)
    }
}

/** [EventArticlesTable.role] values. */
object EventArticleRole {
    const val STEP1 = "step1"
    const val LAYER2 = "layer2"
    const val LAYER35 = "layer35"
}

data class EventArticleRow(
    val category: String,
    val articleId: Int,
    val eventKey: String,
    val link: String,
    val domain: String,
    val pubDate: LocalDateTime,
    val seenAt: LocalDateTime?,
    val role: String,
    val step1Status: String? = null,
    val sim: Double? = null,
    val recordedAt: LocalDateTime
)

interface EventArticleStore {
    /** Inserts or replaces rows by (category, article_id): the latest decision about an article wins. */
    fun upsertEventArticles(rows: List<EventArticleRow>)

    /**
     * Members of the events [eventKeys] in [category], grouped by key; keys without rows are
     * absent. Callers pass an update's `relatedPreviousEventKey` too and merge, so an update sees
     * the whole chain.
     */
    fun fetchEventClusters(category: String, eventKeys: Collection<String>): Map<String, List<EventArticleRow>>

    /** Rows recorded since [since] — of [category], or of every category when null. */
    fun fetchEventArticlesSince(category: String?, since: LocalDateTime): List<EventArticleRow>

    /** Event key of each of [articleIds] in [category] that has a row; the Layer 2 anchor lookup. */
    fun fetchEventKeysByArticleIds(category: String, articleIds: Collection<Int>): Map<Int, String>

    /**
     * Moves the members of [fromKey] under [toKey] with [role] (Layer 3.5 found the event is a copy
     * of an already covered one). Returns the number of rows moved.
     */
    fun reassignEventArticles(category: String, fromKey: String, toKey: String, role: String): Int

    /** Deletes rows recorded more than [days] days ago (`storyIntel.retentionDays`). */
    fun deleteOldEventArticles(days: Long)
}

internal class EventArticleRepository : EventArticleStore {

    override fun upsertEventArticles(rows: List<EventArticleRow>) {
        if (rows.isEmpty()) return
        transaction {
            for (row in rows) {
                val updated = EventArticlesTable.update(
                    {
                        (EventArticlesTable.category eq row.category) and
                            (EventArticlesTable.articleId eq row.articleId)
                    }
                ) {
                    it[eventKey] = row.eventKey
                    it[link] = row.link
                    it[domain] = row.domain
                    it[pubDate] = row.pubDate
                    it[seenAt] = row.seenAt
                    it[role] = row.role
                    it[step1Status] = row.step1Status
                    it[sim] = row.sim
                    it[recordedAt] = row.recordedAt
                }
                if (updated == 0) {
                    EventArticlesTable.insertIgnore {
                        it[category] = row.category
                        it[articleId] = row.articleId
                        it[eventKey] = row.eventKey
                        it[link] = row.link
                        it[domain] = row.domain
                        it[pubDate] = row.pubDate
                        it[seenAt] = row.seenAt
                        it[role] = row.role
                        it[step1Status] = row.step1Status
                        it[sim] = row.sim
                        it[recordedAt] = row.recordedAt
                    }
                }
            }
        }
    }

    override fun fetchEventClusters(category: String, eventKeys: Collection<String>): Map<String, List<EventArticleRow>> {
        val keys = eventKeys.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return emptyMap()
        return transaction {
            EventArticlesTable.selectAll()
                .where { (EventArticlesTable.category eq category) and (EventArticlesTable.eventKey inList keys) }
                .map { it.toRow() }
                .groupBy { it.eventKey }
        }
    }

    override fun fetchEventArticlesSince(category: String?, since: LocalDateTime): List<EventArticleRow> = transaction {
        EventArticlesTable.selectAll()
            .where {
                val recent = EventArticlesTable.recordedAt greaterEq since
                if (category == null) recent else recent and (EventArticlesTable.category eq category)
            }
            .map { it.toRow() }
    }

    override fun fetchEventKeysByArticleIds(category: String, articleIds: Collection<Int>): Map<Int, String> {
        if (articleIds.isEmpty()) return emptyMap()
        return transaction {
            EventArticlesTable
                .select(EventArticlesTable.articleId, EventArticlesTable.eventKey)
                .where { (EventArticlesTable.category eq category) and (EventArticlesTable.articleId inList articleIds.distinct()) }
                .associate { it[EventArticlesTable.articleId] to it[EventArticlesTable.eventKey] }
        }
    }

    override fun reassignEventArticles(category: String, fromKey: String, toKey: String, role: String): Int {
        if (fromKey == toKey) return 0
        return transaction {
            EventArticlesTable.update({
                (EventArticlesTable.category eq category) and (EventArticlesTable.eventKey eq fromKey)
            }) {
                it[eventKey] = toKey
                it[EventArticlesTable.role] = role
                it[recordedAt] = LocalDateTime.now()
            }
        }
    }

    override fun deleteOldEventArticles(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            EventArticlesTable.deleteWhere { recordedAt less cutoff }
        }
    }

    private fun ResultRow.toRow() = EventArticleRow(
        category = this[EventArticlesTable.category],
        articleId = this[EventArticlesTable.articleId],
        eventKey = this[EventArticlesTable.eventKey],
        link = this[EventArticlesTable.link],
        domain = this[EventArticlesTable.domain],
        pubDate = this[EventArticlesTable.pubDate],
        seenAt = this[EventArticlesTable.seenAt],
        role = this[EventArticlesTable.role],
        step1Status = this[EventArticlesTable.step1Status],
        sim = this[EventArticlesTable.sim],
        recordedAt = this[EventArticlesTable.recordedAt]
    )
}
