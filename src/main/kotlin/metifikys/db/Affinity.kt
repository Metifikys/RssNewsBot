package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
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
