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
        /** How many top and bottom keys per category × dimension the INFO score dump shows. */
        const val LOG_TOP_N = 5
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

    /**
     * Stale when the last run is older than `recomputeHours` OR was produced with different
     * feedback parameters — an operator tuning `minVolume`/`valence` on a restart must see the
     * effect immediately, not after the next 12h window. State format: `<ISO timestamp>|<hash>`;
     * legacy values without a hash (or unparseable) count as stale.
     */
    private fun isStale(now: LocalDateTime): Boolean {
        val last = db.getState(STATE_KEY) ?: return true
        val parts = last.split('|', limit = 2)
        if (parts.size != 2) return true
        if (parts[1] != configHash()) {
            logger.info { "[Affinity] feedback config changed since the last run — recomputing now" }
            return true
        }
        return try {
            Duration.between(LocalDateTime.parse(parts[0]), now) >= Duration.ofHours(feedback.recomputeHours)
        } catch (e: DateTimeParseException) {
            logger.warn { "[Affinity] unparseable $STATE_KEY='$last' — recomputing" }
            true
        }
    }

    /** Fingerprint of every parameter that changes the scores (recomputeHours deliberately not). */
    private fun configHash(): String = feedback.copy(recomputeHours = 0).hashCode().toString()

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
        db.setState(STATE_KEY, "$now|${configHash()}")
        val byCat = rows.groupBy { it.category }.map { (c, r) -> "$c=${r.size}" }
        logger.info {
            "[Affinity] recomputed from ${signals.size} message(s) → ${rows.size} dimension row(s) " +
                "(${byCat.joinToString(", ")})"
        }
        logScores(rows)
    }

    /**
     * Ranked score dump so the operator can see WHAT the audience likes, not just that
     * something was computed. INFO: per category × dimension, the top/bottom [LOG_TOP_N]
     * keys by score. DEBUG: every row. Grep for `[Affinity][scores]`.
     */
    private fun logScores(rows: List<metifikys.db.AudienceAffinityRow>) {
        for ((category, catRows) in rows.groupBy { it.category }.toSortedMap()) {
            for ((dimension, dimRows) in catRows.groupBy { it.dimension }.toSortedMap()) {
                val ranked = dimRows.sortedByDescending { it.score }
                logger.info {
                    val top = ranked.take(LOG_TOP_N)
                    val bottom = ranked.takeLast(LOG_TOP_N).filter { it !in top }
                    val tail = if (bottom.isEmpty()) "" else " … ↓ ${bottom.joinToString(", ") { cell(it) }}"
                    "[Affinity][scores] $category/$dimension (${ranked.size}): " +
                        "↑ ${top.joinToString(", ") { cell(it) }}$tail"
                }
                logger.debug {
                    "[Affinity][scores][full] $category/$dimension: " +
                        ranked.joinToString("; ") { cell(it) }
                }
            }
        }
    }

    /**
     * `key=+0.42 (sent=+0.80 z=+1.2 n=1.7/3.5)` — score first, then the raw parts behind it.
     * `n` reads `nTone/n`: only the reacted messages (`nTone`) move the score, while `n`
     * counts every delivered message carrying the key. A wide gap means the score rests on
     * far less evidence than the sample size suggests.
     */
    private fun cell(r: metifikys.db.AudienceAffinityRow): String =
        "'${r.key}'=${fmt(r.score)} (sent=${fmt(r.sentiment)} z=${fmt(r.engagementZ)} " +
            "n=${"%.1f".format(java.util.Locale.ROOT, r.nTone)}/${"%.1f".format(java.util.Locale.ROOT, r.n)})"

    private fun fmt(v: Double): String = "%+.2f".format(java.util.Locale.ROOT, v)
}
