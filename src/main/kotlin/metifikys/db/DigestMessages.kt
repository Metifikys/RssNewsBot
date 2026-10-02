package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Links each delivered digest message to the article(s)/event it rendered, so reactions
 * Telegram later reports per `(chat_id, message_id)` can be joined back to a category and event.
 * One row per Telegram message actually sent (a topic is normally one message). `event_key` is
 * null for legacy single-step categories that produce no shortlist.
 */
object DigestMessagesTable : Table("digest_messages") {
    val chatId = long("chat_id")
    val messageId = long("message_id")
    val category = varchar("category", 100).index()
    val eventKey = varchar("event_key", 3000).nullable()
    val articleLinks = text("article_links").default("")   // newline-separated article links in this message
    val sentAt = datetime("sent_at").index()
    /** Experiment arm of the post for reaction analysis (see [DigestMessageRow.variant]); null = none. */
    val variant = varchar("variant", 20).nullable()
    /** The clean topic as posted (see [DigestMessageRow.text]); null on rows written before the column existed. */
    val text = text("text").nullable()
    /** The Step 1 item's `relatedPreviousEventKey` (see [DigestMessageRow.relatedEventKey]). */
    val relatedEventKey = varchar("related_event_key", 3000).nullable()

    override val primaryKey = PrimaryKey(chatId, messageId)

    init {
        // Reply threading looks up "the last post about this event" by (category, event_key).
        index("idx_digest_messages_cat_event", false, category, eventKey)
    }
}

/**
 * Current aggregated reaction counts per `(chat_id, message_id, emoji)`, written by
 * [metifikys.telegram.TelegramUpdatesPoller] from `message_reaction_count` updates. Each
 * update carries the message's complete reaction set, so writes are replace-all for the
 * message, not increments. Custom emoji are stored as `custom:<id>`, paid reactions as `paid`.
 */
object ReactionCountsTable : Table("reaction_counts") {
    val chatId = long("chat_id")
    val messageId = long("message_id")
    val emoji = varchar("emoji", 64)
    val count = integer("count")
    val updatedAt = datetime("updated_at").index()

    override val primaryKey = PrimaryKey(chatId, messageId, emoji)
}

/** One delivered digest message, linking a Telegram `(chatId, messageId)` to what it rendered. */
data class DigestMessageRow(
    val chatId: Long,
    val messageId: Long,
    val category: String,
    val eventKey: String?,
    val articleLinks: String,
    val sentAt: LocalDateTime,
    /**
     * Which experiment arm the post belongs to, so reactions can be compared per arm: `explore`
     * (the anti-bubble pick), `persona` / `control` (the editor-remark A/B). Null for an ordinary
     * post and for every row written before the column existed.
     */
    val variant: String? = null,
    /**
     * The topic exactly as the bullet was rendered — after the URL whitelist and layout, WITHOUT
     * the footer (badges, story lines, hashtags…): the same clean text `saveSummary` keeps, the
     * full text for a long-form post. Every chunk of a chunked topic carries it. Lets
     * `dedup.digest.updateDiff` compare an update with what the channel actually said. Null on
     * rows written before the column existed.
     */
    val text: String? = null,
    /**
     * The `relatedPreviousEventKey` of the item this post rendered (null for a `new` item and on
     * older rows): links the posts of one story whose updates minted new event keys, so the story
     * reads back as a chain ([DigestMessageStore.fetchStoryPosts]).
     */
    val relatedEventKey: String? = null
) {
    companion object {
        const val VARIANT_EXPLORE = "explore"
        const val VARIANT_PERSONA = "persona"
        const val VARIANT_CONTROL = "control"
    }
}

/**
 * One post of a story as the channel carried it ([DigestMessageStore.fetchStoryPosts]): the event
 * key it rendered, when it went out, and its clean text (null for posts written before
 * `digest_messages.text` existed).
 */
data class StoryPost(val eventKey: String, val sentAt: LocalDateTime, val text: String?)

/** How many rounds [DigestMessageStore.fetchStoryPosts] follows `related_event_key` links. */
const val STORY_HOPS = 2

/** A single emoji's current aggregated count on a message. */
data class ReactionCount(val emoji: String, val count: Int)

/**
 * Per-category reaction rollup produced by [NewsDatabase.fetchReactionSummary] over a time
 * window: [totalReactions] summed across every emoji, [postCount] distinct messages carrying
 * any reaction, and [byEmoji] the per-emoji totals sorted highest-first for a "top" line.
 */
data class CategoryReactionSummary(
    val category: String,
    val totalReactions: Long,
    val postCount: Long,
    val byEmoji: List<ReactionCount>
)

/** Delivered digest messages (`digest_messages`) and their Telegram reaction counts (`reaction_counts`). */
interface DigestMessageStore {
    /**
     * Records one row per delivered digest message. Idempotent on `(chat_id, message_id)`:
     * a redelivery with the same ids (should not happen) is ignored rather than duplicated.
     */
    fun insertDigestMessages(rows: List<DigestMessageRow>)

    /**
     * How many posts the channel already carries per event key: rows of [category] whose
     * `event_key` is in [eventKeys] and `sent_at >= since`, counted as distinct deliveries
     * (a chunked topic writes several rows with one `sent_at` and counts once). Keys with no
     * posts are absent. Backs `digest.maxPostsPerEvent`.
     */
    fun countDigestPostsByEventKeys(
        category: String,
        eventKeys: Collection<String>,
        since: LocalDateTime
    ): Map<String, Int>

    /**
     * The latest delivered message per event key: rows of [category] whose `event_key` is in
     * [eventKeys] and `sent_at >= since`. The newest digest wins; within one digest the lowest
     * message id (the first chunk of a chunked topic). Backs the reply threading in
     * [metifikys.digest.DigestDeliverer] — "the previous post about this story".
     */
    fun fetchLatestDigestMessagesByEventKeys(
        category: String,
        eventKeys: Collection<String>,
        since: LocalDateTime
    ): Map<String, DigestMessageRow>

    /**
     * The last [limit] posts of the story [eventKeys] belong to in [category] since [since],
     * oldest first; one entry per post (the chunks of a topic count once). The story is followed
     * through `related_event_key` both ways for up to [STORY_HOPS] rounds: an update that minted
     * a new key (pointing `related` at the original) is found from the original's key, and a
     * sibling update pointing at the same original is found from the newest one. Backs
     * `dedup.digest.updateDiff` — "what the channel already said about this story".
     */
    fun fetchStoryPosts(
        category: String,
        eventKeys: Collection<String>,
        since: LocalDateTime,
        limit: Int
    ): List<StoryPost>

    /**
     * Replaces the stored reaction set for one message with [counts]. A `message_reaction_count`
     * update always carries the message's *complete* current reactions, so this deletes the old
     * rows and re-inserts the new set in one transaction — an empty [counts] (last reaction
     * removed) therefore clears the message. Zero/negative counts are skipped.
     */
    fun replaceReactionCounts(chatId: Long, messageId: Long, counts: List<ReactionCount>)

    /**
     * Per-category reaction rollup over the last [sinceHours] hours, windowed by the digest
     * message's `sent_at`. Joins reactions to their digest message so counts are attributed to a
     * category; messages we didn't send (no `digest_messages` row) are excluded. Categories with
     * no in-window reactions are absent from the map. Aggregated in-process — the reaction volume
     * is tiny relative to articles.
     */
    fun fetchReactionSummary(sinceHours: Long): Map<String, CategoryReactionSummary>

    /**
     * Every article link that reached a channel since [since] (the `article_links` of the
     * `digest_messages` rows), for [category] or for every category when null. Backs the "how
     * many of these were published" half of a mute preview.
     */
    fun fetchDigestArticleLinksSince(category: String?, since: LocalDateTime): Set<String>

    /**
     * Posts [category] published since [since]: distinct `(sent_at, article_links)` pairs, so a
     * topic chunked over several messages counts once. Backs the `/run` completion report.
     */
    fun countDigestPostsSince(category: String, since: LocalDateTime): Int

    /**
     * The article links of every post sent since [since], one set per post (the chunks of one
     * topic share `(sent_at, article_links)` and count once): the published clusters of the
     * feed-quality "who was first" metric.
     */
    fun fetchPublishedLinkGroupsSince(since: LocalDateTime): List<Set<String>>

    /**
     * Every post tagged with an experiment arm (`variant` not null) sent since [since], with the
     * reactions it collected: one entry per post (the chunks of a topic share chat, category,
     * event key and `sent_at` and are summed), posts without reactions included. Backs the
     * `/status` arm comparison (persona / control / explore).
     */
    fun fetchVariantPosts(since: LocalDateTime): List<VariantPost>

    /** The row of one delivered message, or null. */
    fun fetchDigestMessage(chatId: Long, messageId: Long): DigestMessageRow?

    /**
     * Every message of the post [row] belongs to — the chunks of one topic share the chat,
     * category, `sent_at` and event key (legacy rows without a key: the article links) — ordered
     * by message id.
     */
    fun fetchDigestMessagesOfPost(row: DigestMessageRow): List<DigestMessageRow>

    /**
     * Rows with message id [messageId] in any of [categories], newest first: a `t.me/<username>/<id>`
     * link names the channel, not the numeric chat id the rows are keyed by.
     */
    fun fetchDigestMessagesByMessageId(messageId: Long, categories: Collection<String>): List<DigestMessageRow>

    /**
     * The posts of the story [eventKeys] belong to in [category] since [since] — followed through
     * `related_event_key` like [fetchStoryPosts] — as the FIRST message of each post, oldest first.
     * Backs the inspector's "update to ↗" / "next: N updates ↗" links.
     */
    fun fetchStoryMessages(category: String, eventKeys: Collection<String>, since: LocalDateTime): List<DigestMessageRow>

    /** Current reactions of these messages summed per emoji, highest first. */
    fun fetchReactionCounts(refs: Collection<Pair<Long, Long>>): List<ReactionCount>

    /** Deletes the rows of these `(chat_id, message_id)` messages (a post deleted from the channel). */
    fun deleteDigestMessages(refs: Collection<Pair<Long, Long>>)

    /** Deletes digest-message links older than [days] days (by `sent_at`). */
    fun deleteOldDigestMessages(days: Long = 90)

    /** Deletes reaction-count rows not touched in [days] days (by `updated_at`). */
    fun deleteOldReactionCounts(days: Long = 90)
}

internal class DigestMessageRepository : DigestMessageStore {

    override fun insertDigestMessages(rows: List<DigestMessageRow>) {
        if (rows.isEmpty()) return
        transaction {
            for (r in rows) {
                DigestMessagesTable.insertIgnore {
                    it[chatId] = r.chatId
                    it[messageId] = r.messageId
                    it[category] = r.category
                    it[eventKey] = r.eventKey
                    it[articleLinks] = r.articleLinks
                    it[sentAt] = r.sentAt
                    it[variant] = r.variant
                    it[text] = r.text
                    it[relatedEventKey] = r.relatedEventKey
                }
            }
        }
    }

    override fun countDigestPostsByEventKeys(
        category: String,
        eventKeys: Collection<String>,
        since: LocalDateTime
    ): Map<String, Int> {
        if (eventKeys.isEmpty()) return emptyMap()
        return transaction {
            DigestMessagesTable
                .select(DigestMessagesTable.eventKey, DigestMessagesTable.sentAt)
                .where {
                    (DigestMessagesTable.category eq category) and
                        (DigestMessagesTable.eventKey inList eventKeys.toSet()) and
                        (DigestMessagesTable.sentAt greaterEq since)
                }
                .mapNotNull { row -> row[DigestMessagesTable.eventKey]?.let { it to row[DigestMessagesTable.sentAt] } }
                .distinct()
                .groupingBy { it.first }
                .eachCount()
        }
    }

    override fun fetchLatestDigestMessagesByEventKeys(
        category: String,
        eventKeys: Collection<String>,
        since: LocalDateTime
    ): Map<String, DigestMessageRow> {
        if (eventKeys.isEmpty()) return emptyMap()
        return transaction {
            val out = LinkedHashMap<String, DigestMessageRow>()
            DigestMessagesTable
                .selectAll()
                .where {
                    (DigestMessagesTable.category eq category) and
                        (DigestMessagesTable.eventKey inList eventKeys.toSet()) and
                        (DigestMessagesTable.sentAt greaterEq since)
                }
                .orderBy(DigestMessagesTable.sentAt to SortOrder.DESC, DigestMessagesTable.messageId to SortOrder.ASC)
                .forEach { row ->
                    val key = row[DigestMessagesTable.eventKey] ?: return@forEach
                    out.putIfAbsent(key, row.toDigestMessageRow())
                }
            out
        }
    }

    override fun fetchStoryPosts(
        category: String,
        eventKeys: Collection<String>,
        since: LocalDateTime,
        limit: Int
    ): List<StoryPost> {
        if (eventKeys.isEmpty() || limit <= 0) return emptyList()
        return storyRows(category, eventKeys, since)
            .filter { it.eventKey != null }
            .sortedWith(compareBy({ it.sentAt }, { it.messageId }))
            .distinctBy { it.eventKey to it.sentAt }
            .takeLast(limit)
            .map { StoryPost(it.eventKey!!, it.sentAt, it.text) }
    }

    override fun fetchStoryMessages(category: String, eventKeys: Collection<String>, since: LocalDateTime): List<DigestMessageRow> {
        if (eventKeys.isEmpty()) return emptyList()
        return storyRows(category, eventKeys, since)
            .filter { it.eventKey != null }
            .sortedWith(compareBy({ it.sentAt }, { it.messageId }))
            .distinctBy { Triple(it.chatId, it.eventKey, it.sentAt) }
    }

    /** Every row of the story chain: the given keys, then [STORY_HOPS] rounds of linked keys. */
    private fun storyRows(category: String, eventKeys: Collection<String>, since: LocalDateTime): Collection<DigestMessageRow> =
        transaction {
            val keys = eventKeys.toMutableSet()
            val rows = LinkedHashMap<Pair<Long, Long>, DigestMessageRow>()
            var frontier: Set<String> = keys.toSet()
            // Round 0 reads the given keys; each further round follows the keys the rows found
            // so far link to (their own key and their related key), up to STORY_HOPS.
            repeat(STORY_HOPS + 1) {
                if (frontier.isEmpty()) return@repeat
                DigestMessagesTable
                    .selectAll()
                    .where {
                        (DigestMessagesTable.category eq category) and
                            (DigestMessagesTable.sentAt greaterEq since) and
                            ((DigestMessagesTable.eventKey inList frontier) or (DigestMessagesTable.relatedEventKey inList frontier))
                    }
                    .forEach { r -> rows.putIfAbsent(r[DigestMessagesTable.chatId] to r[DigestMessagesTable.messageId], r.toDigestMessageRow()) }
                val linked = rows.values.flatMap { listOfNotNull(it.eventKey, it.relatedEventKey) }.toSet()
                frontier = linked - keys
                keys += linked
            }
            rows.values
        }

    override fun replaceReactionCounts(chatId: Long, messageId: Long, counts: List<ReactionCount>) {
        transaction {
            ReactionCountsTable.deleteWhere {
                (ReactionCountsTable.chatId eq chatId) and (ReactionCountsTable.messageId eq messageId)
            }
            val now = LocalDateTime.now()
            for (c in counts) {
                if (c.count <= 0) continue
                ReactionCountsTable.insert {
                    it[ReactionCountsTable.chatId] = chatId
                    it[ReactionCountsTable.messageId] = messageId
                    it[emoji] = c.emoji
                    it[count] = c.count
                    it[updatedAt] = now
                }
            }
        }
    }

    override fun fetchReactionSummary(sinceHours: Long): Map<String, CategoryReactionSummary> {
        val cutoff = LocalDateTime.now().minusHours(sinceHours)
        val recs = transaction {
            ReactionCountsTable
                .join(
                    DigestMessagesTable, JoinType.INNER,
                    additionalConstraint = {
                        (ReactionCountsTable.chatId eq DigestMessagesTable.chatId) and
                            (ReactionCountsTable.messageId eq DigestMessagesTable.messageId)
                    }
                )
                .select(
                    DigestMessagesTable.category, ReactionCountsTable.chatId,
                    ReactionCountsTable.messageId, ReactionCountsTable.emoji, ReactionCountsTable.count
                )
                .where { DigestMessagesTable.sentAt greaterEq cutoff }
                .map {
                    ReactionRec(
                        category = it[DigestMessagesTable.category],
                        chatId = it[ReactionCountsTable.chatId],
                        messageId = it[ReactionCountsTable.messageId],
                        emoji = it[ReactionCountsTable.emoji],
                        count = it[ReactionCountsTable.count]
                    )
                }
        }
        return recs.groupBy { it.category }.mapValues { (cat, list) ->
            val byEmoji = list.groupBy { it.emoji }
                .map { (emoji, rows) -> ReactionCount(emoji, rows.sumOf { it.count }) }
                .sortedByDescending { it.count }
            CategoryReactionSummary(
                category = cat,
                totalReactions = list.sumOf { it.count.toLong() },
                postCount = list.map { it.chatId to it.messageId }.distinct().size.toLong(),
                byEmoji = byEmoji
            )
        }
    }

    override fun fetchDigestArticleLinksSince(category: String?, since: LocalDateTime): Set<String> = transaction {
        DigestMessagesTable
            .select(DigestMessagesTable.articleLinks)
            .where {
                val recent = DigestMessagesTable.sentAt greaterEq since
                if (category == null) recent else recent and (DigestMessagesTable.category eq category)
            }
            .flatMap { row -> row[DigestMessagesTable.articleLinks].lines().map { it.trim() }.filter { it.isNotEmpty() } }
            .toSet()
    }

    override fun fetchPublishedLinkGroupsSince(since: LocalDateTime): List<Set<String>> = transaction {
        DigestMessagesTable
            .select(DigestMessagesTable.sentAt, DigestMessagesTable.articleLinks)
            .where { DigestMessagesTable.sentAt greaterEq since }
            .map { it[DigestMessagesTable.sentAt] to it[DigestMessagesTable.articleLinks] }
            .distinct()
            .map { (_, links) -> links.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            .filter { it.isNotEmpty() }
    }

    override fun countDigestPostsSince(category: String, since: LocalDateTime): Int = transaction {
        DigestMessagesTable
            .select(DigestMessagesTable.sentAt, DigestMessagesTable.articleLinks)
            .where { (DigestMessagesTable.category eq category) and (DigestMessagesTable.sentAt greaterEq since) }
            .map { it[DigestMessagesTable.sentAt] to it[DigestMessagesTable.articleLinks] }
            .distinct()
            .size
    }

    override fun fetchVariantPosts(since: LocalDateTime): List<VariantPost> {
        data class Rec(val key: List<Any?>, val category: String, val variant: String, val emoji: String?, val count: Int)
        val recs = transaction {
            DigestMessagesTable
                .join(
                    ReactionCountsTable, JoinType.LEFT,
                    additionalConstraint = {
                        (ReactionCountsTable.chatId eq DigestMessagesTable.chatId) and
                            (ReactionCountsTable.messageId eq DigestMessagesTable.messageId)
                    }
                )
                .select(
                    DigestMessagesTable.chatId, DigestMessagesTable.category, DigestMessagesTable.eventKey,
                    DigestMessagesTable.sentAt, DigestMessagesTable.variant,
                    ReactionCountsTable.emoji, ReactionCountsTable.count
                )
                .where { DigestMessagesTable.variant.isNotNull() and (DigestMessagesTable.sentAt greaterEq since) }
                .map {
                    val category = it[DigestMessagesTable.category]
                    val variant = it[DigestMessagesTable.variant]!!
                    Rec(
                        key = listOf(it[DigestMessagesTable.chatId], category, it[DigestMessagesTable.eventKey], it[DigestMessagesTable.sentAt], variant),
                        category = category,
                        variant = variant,
                        emoji = it.getOrNull(ReactionCountsTable.emoji),
                        count = it.getOrNull(ReactionCountsTable.count) ?: 0
                    )
                }
        }
        return recs.groupBy { it.key }.map { (_, list) ->
            val first = list.first()
            val reactions = list.filter { it.emoji != null && it.count > 0 }
                .groupBy { it.emoji!! }
                .map { (emoji, rows) -> ReactionCount(emoji, rows.sumOf { it.count }) }
            VariantPost(first.category, first.variant, reactions)
        }
    }

    private fun ResultRow.toDigestMessageRow() = DigestMessageRow(
        chatId = this[DigestMessagesTable.chatId],
        messageId = this[DigestMessagesTable.messageId],
        category = this[DigestMessagesTable.category],
        eventKey = this[DigestMessagesTable.eventKey],
        articleLinks = this[DigestMessagesTable.articleLinks],
        sentAt = this[DigestMessagesTable.sentAt],
        variant = this[DigestMessagesTable.variant],
        text = this[DigestMessagesTable.text],
        relatedEventKey = this[DigestMessagesTable.relatedEventKey]
    )

    override fun fetchDigestMessage(chatId: Long, messageId: Long): DigestMessageRow? = transaction {
        DigestMessagesTable.selectAll()
            .where { (DigestMessagesTable.chatId eq chatId) and (DigestMessagesTable.messageId eq messageId) }
            .map { it.toDigestMessageRow() }
            .firstOrNull()
    }

    override fun fetchDigestMessagesOfPost(row: DigestMessageRow): List<DigestMessageRow> = transaction {
        DigestMessagesTable.selectAll()
            .where {
                (DigestMessagesTable.chatId eq row.chatId) and
                    (DigestMessagesTable.category eq row.category) and
                    (DigestMessagesTable.sentAt eq row.sentAt)
            }
            .map { it.toDigestMessageRow() }
            .filter { if (row.eventKey != null) it.eventKey == row.eventKey else it.eventKey == null && it.articleLinks == row.articleLinks }
            .sortedBy { it.messageId }
    }

    override fun fetchDigestMessagesByMessageId(messageId: Long, categories: Collection<String>): List<DigestMessageRow> {
        if (categories.isEmpty()) return emptyList()
        return transaction {
            DigestMessagesTable.selectAll()
                .where { (DigestMessagesTable.messageId eq messageId) and (DigestMessagesTable.category inList categories.toSet()) }
                .orderBy(DigestMessagesTable.sentAt to SortOrder.DESC)
                .map { it.toDigestMessageRow() }
        }
    }

    override fun fetchReactionCounts(refs: Collection<Pair<Long, Long>>): List<ReactionCount> {
        if (refs.isEmpty()) return emptyList()
        val wanted = refs.toSet()
        val rows = transaction {
            ReactionCountsTable.selectAll()
                .where {
                    (ReactionCountsTable.chatId inList wanted.map { it.first }.toSet()) and
                        (ReactionCountsTable.messageId inList wanted.map { it.second }.toSet())
                }
                .filter { (it[ReactionCountsTable.chatId] to it[ReactionCountsTable.messageId]) in wanted }
                .map { it[ReactionCountsTable.emoji] to it[ReactionCountsTable.count] }
        }
        return rows.groupBy({ it.first }, { it.second })
            .map { (emoji, counts) -> ReactionCount(emoji, counts.sum()) }
            .sortedByDescending { it.count }
    }

    override fun deleteDigestMessages(refs: Collection<Pair<Long, Long>>) {
        if (refs.isEmpty()) return
        transaction {
            for ((chat, message) in refs) {
                DigestMessagesTable.deleteWhere { (chatId eq chat) and (messageId eq message) }
            }
        }
    }

    override fun deleteOldDigestMessages(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            DigestMessagesTable.deleteWhere { sentAt less cutoff }
        }
    }

    override fun deleteOldReactionCounts(days: Long) {
        val cutoff = LocalDateTime.now().minusDays(days)
        transaction {
            ReactionCountsTable.deleteWhere { updatedAt less cutoff }
        }
    }

    /** Internal join-row for [fetchReactionSummary] aggregation. */
    private data class ReactionRec(
        val category: String,
        val chatId: Long,
        val messageId: Long,
        val emoji: String,
        val count: Int
    )
}

/** One post of an experiment arm (`digest_messages.variant`) and the reactions it collected. */
data class VariantPost(val category: String, val variant: String, val reactions: List<ReactionCount>)
