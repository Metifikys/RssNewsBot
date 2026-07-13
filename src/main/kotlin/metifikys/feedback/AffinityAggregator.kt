package metifikys.feedback

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.config.FeedbackConfig
import metifikys.db.NewsDatabase
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

/**
 * Rebuilds the materialized `audience_affinity` table from the reactions the updates
 * poller has collected. Phase 1 of the reaction-feedback loop
 * (`.claude/plans/reaction-feedback-design.md`): pure signal aggregation — nothing reads
 * the scores yet except `/status`, so a bad run can't hurt the digest.
 *
 * Called at the start of each digest cycle; actually recomputes only when the previous
 * run is older than `feedback.recomputeHours` (timestamp in `bot_state`). A full rebuild
 * over the lookback window is idempotent, so a crash mid-run just means the next cycle
 * redoes it.
 */
class AffinityAggregator(
    private val feedback: FeedbackConfig,
    private val db: NewsDatabase,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    private val valence = ReactionValence(feedback.valence)

    companion object {
        const val STATE_KEY = "affinity_last_recompute"
    }

    /** Recomputes if enabled and stale. Never throws — feedback must not break a cycle. */
    fun recomputeIfStale() {
        if (!feedback.enabled) return
        try {
            val now = clock()
            if (!isStale(now)) return
            recompute(now)
        } catch (e: Exception) {
            logger.warn(e) { "[Affinity] recompute failed — keeping the previous scores" }
        }
    }

    private fun isStale(now: LocalDateTime): Boolean {
        val last = db.getState(STATE_KEY) ?: return true
        return try {
            Duration.between(LocalDateTime.parse(last), now) >= Duration.ofHours(feedback.recomputeHours)
        } catch (e: DateTimeParseException) {
            logger.warn { "[Affinity] unparseable $STATE_KEY='$last' — recomputing" }
            true
        }
    }

    private fun recompute(now: LocalDateTime) {
        val inputs = db.fetchAffinityInputs(feedback.attributionHours, feedback.lookbackDays)
        val signals = inputs.map { m ->
            AffinityMath.MessageSignal(
                category = m.category,
                sentAt = m.sentAt,
                subject = m.subject,
                franchise = m.franchise,
                eventType = m.eventType,
                source = AffinityMath.sourceDomain(m.url),
                volume = m.reactions.sumOf { it.count },
                valenceSum = m.reactions.sumOf { valence.of(it.emoji) * it.count }
            )
        }
        val rows = AffinityMath.aggregate(
            signals,
            AffinityMath.Params(
                halflifeDays = feedback.halflifeDays.toDouble(),
                shrinkageK = feedback.shrinkageK,
                minSamples = feedback.minSamples,
                minVolume = feedback.minVolume,
                now = now
            )
        )
        db.replaceAudienceAffinity(rows)
        db.setState(STATE_KEY, now.toString())
        val byCat = rows.groupBy { it.category }.map { (c, r) -> "$c=${r.size}" }
        logger.info {
            "[Affinity] recomputed from ${signals.size} message(s) → ${rows.size} dimension row(s) " +
                "(${byCat.joinToString(", ")})"
        }
    }
}
