package metifikys.feedback

import metifikys.db.AudienceAffinityRow
import metifikys.model.ShortlistItem
import java.util.Locale

/**
 * Read side of the reaction-feedback loop (phase 2): one CATEGORY's materialized
 * `audience_affinity` rows turned into per-item affinity scores for the ranker and a
 * compact `{{AUDIENCE_SIGNALS}}` block for the Step-1 prompt. Pure — built from rows
 * already fetched, no I/O.
 *
 * An item's raw affinity mixes its dimensions with fixed weights (franchise carries the
 * most identity, subject the least — subjects are near-unique free text and rarely
 * repeat). A dimension key absent from the table contributes 0 — "never seen" is
 * neutral, not negative. Raw scores live on the centered `audience_affinity.score`
 * scale (0 = category average, roughly ±0.5 in practice).
 */
class AffinityScores private constructor(
    private val byDimension: Map<String, Map<String, Double>>
) {

    companion object {
        const val WEIGHT_FRANCHISE = 0.35
        const val WEIGHT_EVENT_TYPE = 0.30
        const val WEIGHT_SOURCE = 0.20
        const val WEIGHT_SUBJECT = 0.15

        /** Builds a lookup from one category's affinity rows (pass rows for THAT category only). */
        fun of(rows: List<AudienceAffinityRow>): AffinityScores = AffinityScores(
            rows.groupBy { it.dimension }.mapValues { (_, r) -> r.associate { it.key to it.score } }
        )

        val EMPTY = AffinityScores(emptyMap())
    }

    fun isEmpty(): Boolean = byDimension.isEmpty()

    private fun score(dimension: String, key: String): Double =
        byDimension[dimension]?.get(key.trim().lowercase()) ?: 0.0

    /** Raw affinity of a shortlist item on the centered score scale. */
    fun itemScore(item: ShortlistItem): Double =
        WEIGHT_FRANCHISE * score(AffinityMath.DIM_FRANCHISE, item.franchise) +
            WEIGHT_EVENT_TYPE * score(AffinityMath.DIM_EVENT_TYPE, item.eventType) +
            WEIGHT_SOURCE * score(AffinityMath.DIM_SOURCE, AffinityMath.sourceDomain(item.url)) +
            WEIGHT_SUBJECT * score(AffinityMath.DIM_SUBJECT, item.subject)

    /**
     * Compact prompt block for `{{AUDIENCE_SIGNALS}}`: the strongest liked/disliked
     * franchise + event-type keys, or "" when nothing clears [minAbsScore] (an empty
     * placeholder is better than teaching the LLM from noise). Sources and subjects are
     * deliberately excluded — the LLM shouldn't up-rank an outlet, only content traits.
     */
    fun buildAudienceSignals(minAbsScore: Double = 0.05, topN: Int = 5): String {
        val relevant = listOf(AffinityMath.DIM_FRANCHISE, AffinityMath.DIM_EVENT_TYPE)
            .flatMap { dim ->
                byDimension[dim].orEmpty().map { (key, s) -> Triple(dim, key, s) }
            }
        val liked = relevant.filter { it.third >= minAbsScore }.sortedByDescending { it.third }.take(topN)
        val disliked = relevant.filter { it.third <= -minAbsScore }.sortedBy { it.third }.take(topN)
        if (liked.isEmpty() && disliked.isEmpty()) return ""

        fun line(items: List<Triple<String, String, Double>>) = items.joinToString(", ") { (dim, key, s) ->
            val label = if (dim == AffinityMath.DIM_EVENT_TYPE) "event type" else dim
            "$label '$key' (${"%+.2f".format(Locale.ROOT, s)})"
        }

        return buildString {
            append("AUDIENCE FEEDBACK (from channel reactions; scores are relative to the category average):\n")
            if (liked.isNotEmpty()) append("- The audience responds WELL to: ${line(liked)}\n")
            if (disliked.isNotEmpty()) append("- The audience responds POORLY to: ${line(disliked)}\n")
            append(
                "Treat this as a soft tiebreaker when scoring digestFit: nudge matching items up or down " +
                    "by at most 1 point. NEVER let it override newsworthiness — an important story stays in " +
                    "even if the audience is lukewarm about its franchise or event type."
            )
        }
    }
}

/**
 * Human-readable description of how the affinity term changed a ranked shortlist, for the
 * `[AffinityRank]` INFO log. Null when the kept set AND order are identical — the caller
 * logs a cheap "no effect" line instead. Pure and unit-tested; keys are `eventKey`s.
 */
object AffinityImpact {

    fun describe(before: List<ShortlistItem>, after: List<ShortlistItem>): String? {
        val beforeKeys = before.map { it.eventKey }
        val afterKeys = after.map { it.eventKey }
        if (beforeKeys == afterKeys) return null

        val added = afterKeys.filter { it !in beforeKeys.toSet() }
        val removed = beforeKeys.filter { it !in afterKeys.toSet() }
        val moved = afterKeys.withIndex().mapNotNull { (newIdx, key) ->
            val oldIdx = beforeKeys.indexOf(key)
            if (oldIdx >= 0 && oldIdx != newIdx) "'$key' #${oldIdx + 1}→#${newIdx + 1}" else null
        }

        val parts = mutableListOf<String>()
        if (removed.isNotEmpty()) parts += "drops ${removed.joinToString(", ") { "'$it'" }}"
        if (added.isNotEmpty()) parts += "adds ${added.joinToString(", ") { "'$it'" }}"
        if (moved.isNotEmpty()) parts += "reorders ${moved.joinToString(", ")}"
        return parts.joinToString("; ")
    }
}
