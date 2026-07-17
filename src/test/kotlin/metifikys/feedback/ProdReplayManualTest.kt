package metifikys.feedback

import metifikys.ai.dedup.ShortlistRanker
import metifikys.config.DigestConfig
import metifikys.config.FeedbackConfig
import metifikys.config.RankerConfig
import metifikys.db.NewsDatabase
import metifikys.model.ShortlistItem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale

/**
 * Manual replay harness, not part of the suite: runs the feedback pipeline against a
 * COPY of a production DB (set REPLAY_DB=<path>) and prints the results. Used to
 * validate parameter changes against real reaction data before touching production.
 */
class ProdReplayManualTest {

    private fun db() = NewsDatabase(System.getenv("REPLAY_DB"))

    @Test
    @EnabledIfEnvironmentVariable(named = "REPLAY_DB", matches = ".+")
    fun `replay affinity aggregation against a prod snapshot`() {
        val db = db()
        AffinityAggregator(FeedbackConfig(enabled = true), db).recomputeIfStale()
        val rows = db.fetchAudienceAffinity()
        for ((cat, catRows) in rows.groupBy { it.category }.toSortedMap()) {
            println("== $cat ==")
            for ((dim, dimRows) in catRows.groupBy { it.dimension }.toSortedMap()) {
                println("  -- $dim --")
                for (r in dimRows.sortedByDescending { it.score }) {
                    println(String.format(Locale.ROOT,
                        "    %+.3f  sent=%+.2f z=%+.2f n=%5.1f  %s", r.score, r.sentiment, r.engagementZ, r.n, r.key))
                }
            }
        }
    }

    /**
     * End-to-end what-if for lever A: takes each category's REAL recent covered events as a
     * pseudo-shortlist, ranks it with and without the affinity term (weight 0.15, the
     * suggested rollout value), and prints the exact selection diff plus the
     * AUDIENCE_SIGNALS block the Step-1 prompt would receive. This is the same code path
     * production runs — AffinityScores → ShortlistRanker → AffinityImpact.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "REPLAY_DB", matches = ".+")
    fun `replay ranker affinity term against a prod snapshot`() {
        val db = db()
        AffinityAggregator(FeedbackConfig(enabled = true), db).recomputeIfStale()
        val digestConfig = DigestConfig(
            ranker = RankerConfig(enabled = true, reactionWeight = 0.15),
            maxDigestItems = 6
        )
        val categories = db.fetchAudienceAffinity().map { it.category }.distinct().sorted()
        for (category in categories) {
            val scores = AffinityScores.of(db.fetchAudienceAffinity().filter { it.category == category })
            val scorer: (ShortlistItem) -> Double = { scores.itemScore(it) }

            // Real recent events as a pseudo-shortlist (what Step 1 plausibly emits).
            val candidates = db.fetchRecentEvents(category, sinceDays = 3, limit = 18).map {
                ShortlistItem(
                    eventKey = it.eventKey, subject = it.subject, franchise = it.franchise,
                    eventType = it.eventType, coreFact = it.coreFact, importance = it.importance,
                    newsworthiness = it.newsworthiness, digestFit = it.digestFit,
                    url = it.url, status = "new"
                )
            }
            println("== $category: ${candidates.size} candidate(s), 6 slots ==")
            if (candidates.isEmpty()) continue

            val baseline = ShortlistRanker.rank(candidates, digestConfig)
            val withAffinity = ShortlistRanker.rank(candidates, digestConfig, scorer)
            val impact = AffinityImpact.describe(baseline.kept, withAffinity.kept)

            println("  baseline : ${baseline.kept.joinToString { it.eventKey }}")
            println("  affinity : ${withAffinity.kept.joinToString { it.eventKey }}")
            println("  impact   : ${impact ?: "no effect"}")
            for (item in candidates) {
                val c = ShortlistRanker.affinityContribution(item, digestConfig, scorer)
                if (kotlin.math.abs(c) >= 0.05) {
                    println(String.format(Locale.ROOT,
                        "    %+.2fpts  %s (franchise='%s' type=%s)", c, item.eventKey, item.franchise, item.eventType))
                }
            }
            val signals = scores.buildAudienceSignals()
            println(if (signals.isEmpty()) "  AUDIENCE_SIGNALS: <empty>" else signals.prependIndent("  | "))
        }
    }
}
