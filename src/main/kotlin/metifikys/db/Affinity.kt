package metifikys.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

/**
 * Materialized audience-affinity scores, one row per (category, dimension, key) — e.g.
 * ("games", "franchise", "silksong"). Rebuilt wholesale by
 * [metifikys.feedback.AffinityAggregator] from `digest_messages` × `reaction_counts` ×
 * `covered_events`; read by `/status` (phase 1) and later by the ranker / prompt builder.
 * `n` is the decay-weighted sample count (fractional by design), `engagement_z` the mean
 * cohort-normalized reaction volume, `sentiment` the mean emoji valence in [-1, 1], and
 * `score` the final shrunk value the consumers read.
 */
object AudienceAffinityTable : Table("audience_affinity") {
    val category = varchar("category", 100)
    val dimension = varchar("dimension", 30)   // "franchise" | "event_type" | "subject" | "source"
    val key = varchar("key", 500)
    val n = double("n")
    /**
     * Decay-weighted count of the messages that actually carried a reaction — the subset
     * [n] that produced the score. Defaulted for rows written before the column existed.
     */
    val nTone = double("n_tone").default(0.0)
    val engagementZ = double("engagement_z")
    val sentiment = double("sentiment")
    val score = double("score")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(category, dimension, key)
}

/**
 * One delivered digest message joined to its covered-event dimensions plus its current
 * reaction set, as consumed by [metifikys.feedback.AffinityAggregator]. Messages that
 * never got a reaction still appear (empty [reactions]) — they anchor the cohort baseline;
 * without them every reacted post would look merely average.
 */
data class AffinityInputMessage(
    val chatId: Long,
    val messageId: Long,
    val category: String,
    val sentAt: LocalDateTime,
    val subject: String,
    val franchise: String,
    val eventType: String,
    /** Canonical source URL from `covered_events` — the aggregator derives the source domain. */
    val url: String,
    val reactions: List<ReactionCount>
)

/** One materialized affinity score; see [AudienceAffinityTable] for field semantics. */
data class AudienceAffinityRow(
    val category: String,
    val dimension: String,
    val key: String,
    val n: Double,
    val nTone: Double = 0.0,
    val engagementZ: Double,
    val sentiment: Double,
    val score: Double,
    val updatedAt: LocalDateTime
)

/** Audience-affinity input (a join over `digest_messages` × `covered_events` × `reaction_counts`) and its materialized scores. */
interface AffinityStore {
    /**
     * Loads the affinity-aggregation input: every digest message sent between
     * `now - lookbackDays` and `now - olderThanHours` that has an `event_key` (legacy
     * single-step messages carry none and stay out of the learning set), joined to its
     * covered-event dimensions and LEFT-joined to its current reactions. Zero-reaction
     * messages are included on purpose — they anchor the cohort baseline. Grouped
     * in-process; the volume is a few messages per category per day.
     *
     * NOTE: the INNER join to `covered_events` means the *effective* lookback is
     * `min(lookbackDays, summaryHistory.retentionDays)` — `pruneOldCoveredEvents` (14d by
     * default) deletes the dimensions of older messages, silently dropping them from the
     * learning set. Raise `summaryHistory.retentionDays` to widen the real window.
     */
    fun fetchAffinityInputs(olderThanHours: Long, lookbackDays: Long): List<AffinityInputMessage>

    /**
     * Replaces the whole materialized affinity set with [rows] in one transaction.
     * A full rebuild is idempotent and the volume is tiny (tens of rows), so
     * delete-all + insert is simpler and safer than a per-key upsert diff.
     */
    fun replaceAudienceAffinity(rows: List<AudienceAffinityRow>)

    /** Reads all materialized affinity rows (every category/dimension), unordered. */
    fun fetchAudienceAffinity(): List<AudienceAffinityRow>
}

internal class AffinityRepository : AffinityStore {

    override fun fetchAffinityInputs(olderThanHours: Long, lookbackDays: Long): List<AffinityInputMessage> {
        val now = LocalDateTime.now()
        val newest = now.minusHours(olderThanHours)
        val oldest = now.minusDays(lookbackDays)
        data class Flat(
            val chatId: Long, val messageId: Long, val category: String, val sentAt: LocalDateTime,
            val subject: String, val franchise: String, val eventType: String, val url: String,
            val emoji: String?, val count: Int?
        )
        val flat = transaction {
            DigestMessagesTable
                .join(
                    CoveredEventsTable, JoinType.INNER,
                    additionalConstraint = {
                        (DigestMessagesTable.category eq CoveredEventsTable.category) and
                            (DigestMessagesTable.eventKey eq CoveredEventsTable.eventKey)
                    }
                )
                .join(
                    ReactionCountsTable, JoinType.LEFT,
                    additionalConstraint = {
                        (DigestMessagesTable.chatId eq ReactionCountsTable.chatId) and
                            (DigestMessagesTable.messageId eq ReactionCountsTable.messageId)
                    }
                )
                .select(
                    DigestMessagesTable.chatId, DigestMessagesTable.messageId,
                    DigestMessagesTable.category, DigestMessagesTable.sentAt,
                    CoveredEventsTable.subject, CoveredEventsTable.franchise,
                    CoveredEventsTable.eventType, CoveredEventsTable.url,
                    ReactionCountsTable.emoji, ReactionCountsTable.count
                )
                .where { (DigestMessagesTable.sentAt greaterEq oldest) and (DigestMessagesTable.sentAt less newest) }
                .map {
                    Flat(
                        chatId = it[DigestMessagesTable.chatId],
                        messageId = it[DigestMessagesTable.messageId],
                        category = it[DigestMessagesTable.category],
                        sentAt = it[DigestMessagesTable.sentAt],
                        subject = it[CoveredEventsTable.subject],
                        franchise = it[CoveredEventsTable.franchise],
                        eventType = it[CoveredEventsTable.eventType],
                        url = it[CoveredEventsTable.url],
                        emoji = it.getOrNull(ReactionCountsTable.emoji),
                        count = it.getOrNull(ReactionCountsTable.count)
                    )
                }
        }
        return flat.groupBy { it.chatId to it.messageId }.map { (_, rows) ->
            val first = rows.first()
            AffinityInputMessage(
                chatId = first.chatId,
                messageId = first.messageId,
                category = first.category,
                sentAt = first.sentAt,
                subject = first.subject,
                franchise = first.franchise,
                eventType = first.eventType,
                url = first.url,
                reactions = rows.mapNotNull { r ->
                    val emoji = r.emoji ?: return@mapNotNull null
                    ReactionCount(emoji, r.count ?: 0)
                }
            )
        }
    }

    override fun replaceAudienceAffinity(rows: List<AudienceAffinityRow>) {
        transaction {
            AudienceAffinityTable.deleteAll()
            for (r in rows) {
                AudienceAffinityTable.insert {
                    it[category] = r.category
                    it[dimension] = r.dimension
                    it[key] = r.key
                    it[n] = r.n
                    it[nTone] = r.nTone
                    it[engagementZ] = r.engagementZ
                    it[sentiment] = r.sentiment
                    it[score] = r.score
                    it[updatedAt] = r.updatedAt
                }
            }
        }
    }

    override fun fetchAudienceAffinity(): List<AudienceAffinityRow> = transaction {
        AudienceAffinityTable.selectAll().map {
            AudienceAffinityRow(
                category = it[AudienceAffinityTable.category],
                dimension = it[AudienceAffinityTable.dimension],
                key = it[AudienceAffinityTable.key],
                n = it[AudienceAffinityTable.n],
                nTone = it[AudienceAffinityTable.nTone],
                engagementZ = it[AudienceAffinityTable.engagementZ],
                sentiment = it[AudienceAffinityTable.sentiment],
                score = it[AudienceAffinityTable.score],
                updatedAt = it[AudienceAffinityTable.updatedAt]
            )
        }
    }
}
