package metifikys.ai.dedup

import metifikys.config.DigestConfig
import metifikys.model.ShortlistItem

/**
 * Post-Step-1 editorial gate. Applies reject thresholds, composite scoring, diversity caps,
 * and a hard size clamp to the raw LLM shortlist. Pure function — no I/O, easy to unit-test.
 *
 * See `telegram_posting_improvement_plan.md` §3 for the decision log behind each knob.
 */
object ShortlistRanker {

    /** eventType values that earn tiebreaker priority over everything else. */
    private val PRIORITY_EVENT_TYPES: List<String> = listOf(
        "major_announcement",
        "release_date",
        "platform_policy",
        "industry_news",
        "major_dlc_expansion",
        "delay",
        "cancellation",
        "lawsuit",
        "acquisition",
        "studio_closure"
    )

    /** Human-readable reason for dropping an item — surfaces in QA logs. */
    data class Dropped(val item: ShortlistItem, val reason: String)

    data class Result(
        val kept: List<ShortlistItem>,
        val dropped: List<Dropped>
    ) {
        /** max items any single franchise holds in [kept], 0 if [kept] is empty. */
        fun franchiseConcentration(): Double {
            if (kept.isEmpty()) return 0.0
            val maxPerFranchise = kept.groupingBy { it.franchise.lowercase() }.eachCount()
                .values.maxOrNull() ?: 0
            return maxPerFranchise.toDouble() / kept.size
        }
    }

    /**
     * @param affinityScorer optional audience-affinity source (reaction feedback, phase 2):
     *        returns an item's raw centered affinity (see `audience_affinity.score`). When
     *        non-null and `ranker.reactionWeight > 0`, the composite gains a clamped term —
     *        see [affinityContribution]. Null (default) keeps the pre-phase-2 behavior bit-for-bit.
     */
    fun rank(
        shortlist: List<ShortlistItem>,
        config: DigestConfig,
        affinityScorer: ((ShortlistItem) -> Double)? = null
    ): Result {
        val dropped = mutableListOf<Dropped>()

        // 1. Floor filter — drop weak digestFit unless newsworthiness saves it.
        val survivedFloor = shortlist.filter { item ->
            val news = effectiveNewsworthiness(item)
            val fit = effectiveDigestFit(item)
            val keep = fit >= config.minDigestFit || news >= config.newsworthinessOverride
            if (!keep) dropped += Dropped(item, "digestFit=$fit<${config.minDigestFit} and newsworthiness=$news<${config.newsworthinessOverride}")
            keep
        }

        // 2. Composite score, descending. Stable: ties broken by priority eventType, then newsworthiness.
        val weightNews = config.ranker.newsworthinessWeight.coerceIn(0.0, 1.0)
        val weightFit = 1.0 - weightNews
        val scored = survivedFloor
            .map { it to composite(it, weightNews, weightFit) + affinityContribution(it, config, affinityScorer) }
            .sortedWith(
                compareByDescending<Pair<ShortlistItem, Double>> { it.second }
                    .thenBy { eventTypeRank(it.first.eventType) }
                    .thenByDescending { effectiveNewsworthiness(it.first) }
            )

        // 3. Diversity caps: maxPerSubject wins over maxPerFranchise because subject is narrower.
        val perFranchise = mutableMapOf<String, Int>()
        val perSubject = mutableMapOf<String, Int>()
        val kept = mutableListOf<ShortlistItem>()

        for ((item, _) in scored) {
            if (kept.size >= config.maxDigestItems) {
                dropped += Dropped(item, "maxDigestItems=${config.maxDigestItems} reached")
                continue
            }
            val franchiseKey = item.franchise.lowercase().ifBlank { "<unknown>" }
            val subjectKey = item.subject.lowercase().ifBlank { "<unknown>" }

            val subjectCount = perSubject.getOrDefault(subjectKey, 0)
            if (subjectKey != "<unknown>" && subjectCount >= config.maxPerSubject) {
                dropped += Dropped(item, "maxPerSubject=${config.maxPerSubject} for subject='${item.subject}'")
                continue
            }
            val franchiseCount = perFranchise.getOrDefault(franchiseKey, 0)
            if (franchiseKey != "<unknown>" && franchiseCount >= config.maxPerFranchise) {
                dropped += Dropped(item, "maxPerFranchise=${config.maxPerFranchise} for franchise='${item.franchise}'")
                continue
            }

            kept += item
            perSubject[subjectKey] = subjectCount + 1
            perFranchise[franchiseKey] = franchiseCount + 1
        }

        return Result(kept = kept, dropped = dropped)
    }

    private fun composite(item: ShortlistItem, weightNews: Double, weightFit: Double): Double =
        weightNews * effectiveNewsworthiness(item) + weightFit * effectiveDigestFit(item)

    /**
     * Audience-affinity term in composite points: raw centered affinity (±~0.5) scaled ×10
     * onto the 0–10 composite scale, weighted by `ranker.reactionWeight`, clamped to
     * ±`ranker.maxAffinityBoost`. 0 whenever the scorer is absent or the weight is 0.
     * Public so the caller can log per-item contributions next to the ranking diff.
     */
    fun affinityContribution(
        item: ShortlistItem,
        config: DigestConfig,
        affinityScorer: ((ShortlistItem) -> Double)?
    ): Double {
        if (affinityScorer == null) return 0.0
        val weight = config.ranker.reactionWeight
        if (weight <= 0.0) return 0.0
        val boost = config.ranker.maxAffinityBoost
        return (weight * 10.0 * affinityScorer(item)).coerceIn(-boost, boost)
    }

    /** Falls back to legacy `importance` when the LLM didn't emit `newsworthiness`. */
    private fun effectiveNewsworthiness(item: ShortlistItem): Int =
        if (item.newsworthiness > 0) item.newsworthiness else item.importance

    /** Without `digestFit`, assume the item is average-fit (5) rather than zero. */
    private fun effectiveDigestFit(item: ShortlistItem): Int =
        if (item.digestFit > 0) item.digestFit else 5

    /** Lower rank = higher priority (used in ascending tiebreaker). Unknown types sink to the bottom. */
    private fun eventTypeRank(eventType: String): Int {
        val idx = PRIORITY_EVENT_TYPES.indexOf(eventType)
        return if (idx >= 0) idx else PRIORITY_EVENT_TYPES.size
    }
}
