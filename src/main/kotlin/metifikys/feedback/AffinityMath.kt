package metifikys.feedback

import metifikys.db.AudienceAffinityRow
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure aggregation math for the reaction-feedback loop: per-message signals in,
 * materialized [AudienceAffinityRow]s out. No I/O — mirrors the ShortlistRanker
 * pattern so every step is unit-testable.
 *
 * Pipeline per category:
 *  1. Cohort z-score of each message's reaction volume (zero-reaction messages included —
 *     they ARE the baseline). Clamped to ±[Z_CLAMP]: one viral or brigaded post may not
 *     dominate a dimension (winsorization).
 *  2. Tone = valenceSum/volume, only for messages with volume >= minVolume — a single 👍
 *     says nothing about direction.
 *  3. Exponential time-decay weight `0.5^(ageDays/halflife)` on every contribution.
 *  4. Per dimension key: decay-weighted means, then the per-message combined signal
 *     `tone · (0.5 + 0.5·satEngagement)` — sentiment sets the DIRECTION, engagement scales
 *     CONFIDENCE (a lukewarm-volume positive counts half; high volume counts full; low
 *     volume never flips the sign) — shrunk toward the category prior by `k/(n+k)`
 *     (empirical Bayes), so a franchise seen once cannot swing selection.
 *  5. The stored score is CENTERED on the category prior: `nTone·(combined − prior)/(nTone+k)`.
 *     0 means "no signal / reacts like the category average", positive = liked MORE than
 *     the average, negative = less. Without centering, a category whose operator likes
 *     almost everything (prior ≈ +0.5) paints every no-data key strongly positive —
 *     real production data made 26 tech keys with zero reactions read as +0.55.
 */
object AffinityMath {

    /** Dimension names as stored in `audience_affinity.dimension`. */
    const val DIM_FRANCHISE = "franchise"
    const val DIM_EVENT_TYPE = "event_type"
    const val DIM_SUBJECT = "subject"
    const val DIM_SOURCE = "source"

    /** Winsorization bound on the cohort z-score. */
    const val Z_CLAMP = 3.0

    /**
     * One delivered message reduced to its feedback signal. [volume] is the total reaction
     * count, [valenceSum] the valence-weighted sum over the same reactions. Dimension values
     * arrive raw — normalization (trim/lowercase, blank-drop) happens here.
     */
    data class MessageSignal(
        val category: String,
        val sentAt: LocalDateTime,
        val subject: String,
        val franchise: String,
        val eventType: String,
        val source: String,
        val volume: Int,
        val valenceSum: Double
    )

    data class Params(
        val halflifeDays: Double,
        val shrinkageK: Double,
        val minSamples: Double,
        val minVolume: Int,
        val now: LocalDateTime
    )

    fun aggregate(messages: List<MessageSignal>, p: Params): List<AudienceAffinityRow> {
        val out = mutableListOf<AudienceAffinityRow>()
        for ((category, catMessages) in messages.groupBy { it.category }) {
            out += aggregateCategory(category, catMessages, p)
        }
        return out
    }

    private fun aggregateCategory(
        category: String,
        messages: List<MessageSignal>,
        p: Params
    ): List<AudienceAffinityRow> {
        if (messages.isEmpty()) return emptyList()

        // 1. Cohort normalization over ALL of the category's messages (volume 0 included).
        val volumes = messages.map { it.volume.toDouble() }
        val mean = volumes.average()
        val variance = volumes.sumOf { (it - mean) * (it - mean) } / volumes.size
        val std = sqrt(variance)

        data class Scored(
            val m: MessageSignal,
            val weight: Double,     // time-decay
            val z: Double,          // clamped cohort z-score of volume
            val tone: Double?,      // valence mean, null below minVolume
            val combined: Double?   // tone × engagement confidence; null when tone is null
        )

        val scored = messages.map { m ->
            val ageDays = Duration.between(m.sentAt, p.now).toHours().coerceAtLeast(0) / 24.0
            val weight = 0.5.pow(ageDays / p.halflifeDays)
            val z = if (std > 0.0) ((m.volume - mean) / std).coerceIn(-Z_CLAMP, Z_CLAMP) else 0.0
            val tone = if (m.volume >= p.minVolume) m.valenceSum / m.volume else null
            val combined = tone?.let { it * (0.5 + 0.5 * satEngagement(z)) }
            Scored(m, weight, z, tone, combined)
        }

        // Category prior: decay-weighted mean of the SAME combined quantity the per-key
        // means use — mixing scales here would let a thin key drift toward a raw-tone
        // prior and outscore a well-sampled key with identical observations. 0 when the
        // category has no toned messages yet — shrinkage then pulls everything to neutral.
        val toned = scored.filter { it.combined != null }
        val priorWeight = toned.sumOf { it.weight }
        val prior = if (priorWeight > 0.0) toned.sumOf { it.weight * it.combined!! } / priorWeight else 0.0

        val dimensions = listOf(
            DIM_FRANCHISE to { m: MessageSignal -> m.franchise },
            DIM_EVENT_TYPE to { m: MessageSignal -> m.eventType },
            DIM_SUBJECT to { m: MessageSignal -> m.subject },
            DIM_SOURCE to { m: MessageSignal -> m.source }
        )

        val out = mutableListOf<AudienceAffinityRow>()
        for ((dimension, keyOf) in dimensions) {
            val byKey = scored.groupBy { keyOf(it.m).trim().lowercase() }
            for ((key, group) in byKey) {
                if (key.isBlank()) continue
                val n = group.sumOf { it.weight }
                if (n < p.minSamples) continue
                val engagementZ = group.sumOf { it.weight * it.z } / n

                // Direction × confidence, shrunk toward the prior, then centered on it:
                // (nTone·combined + k·prior)/(nTone+k) − prior. Zero = no evidence this key
                // differs from the category average.
                val tonedGroup = group.filter { it.combined != null }
                val nTone = tonedGroup.sumOf { it.weight }
                val combined = if (nTone > 0.0) {
                    tonedGroup.sumOf { it.weight * it.combined!! } / nTone
                } else 0.0
                val score = nTone * (combined - prior) / (nTone + p.shrinkageK)

                out += AudienceAffinityRow(
                    category = category,
                    dimension = dimension,
                    key = key,
                    n = n,
                    engagementZ = engagementZ,
                    sentiment = if (nTone > 0.0) tonedGroup.sumOf { it.weight * it.tone!! } / nTone else 0.0,
                    score = score,
                    updatedAt = p.now
                )
            }
        }
        return out
    }

    /**
     * Engagement saturation in [0, 1]: z=+[Z_CLAMP] → 1 (full confidence), z=0 → ~0.5,
     * z=-[Z_CLAMP] → 0. Linear on purpose — smooth, monotone, no surprises to debug.
     */
    private fun satEngagement(z: Double): Double = ((z + Z_CLAMP) / (2 * Z_CLAMP)).coerceIn(0.0, 1.0)

    /** Extracts a lowercase source domain ("www."-stripped) from a URL, or "" when unparseable. */
    fun sourceDomain(url: String): String {
        if (url.isBlank()) return ""
        return try {
            val host = java.net.URI(url.trim()).host ?: return ""
            host.lowercase().removePrefix("www.")
        } catch (e: Exception) {
            ""
        }
    }
}
