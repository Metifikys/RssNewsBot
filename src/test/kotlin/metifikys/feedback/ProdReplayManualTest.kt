package metifikys.feedback

import metifikys.config.FeedbackConfig
import metifikys.db.NewsDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Locale

/**
 * Manual replay harness, not part of the suite: reruns the affinity aggregation against a
 * COPY of a production DB (set REPLAY_DB=<path>) and prints the resulting scores.
 * Used to validate parameter changes (e.g. minVolume) against real reaction data.
 */
class ProdReplayManualTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "REPLAY_DB", matches = ".+")
    fun `replay affinity aggregation against a prod snapshot`() {
        val db = NewsDatabase(System.getenv("REPLAY_DB"))
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
}
