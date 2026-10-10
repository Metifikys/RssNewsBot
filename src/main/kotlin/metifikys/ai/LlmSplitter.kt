package metifikys.ai

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import metifikys.config.LlmOverride
import metifikys.db.BotStateStore
import java.time.LocalDate
import java.time.ZoneId

private val logger = KotlinLogging.logger {}

/**
 * Picks the entry of an `llm.<slot>.providers` split for the next call, so that the day's calls
 * follow the entries' `percent` shares as closely as integers allow: the entry furthest below its
 * share of `(calls so far + 1)` wins, ties go to the earlier entry. 20/10/70 over 10 calls is
 * exactly 2/1/7, in the order 70,20,70,70,10,70,70,20,70,70 — no randomness, no bursts.
 *
 * Tallies are kept per scope (category + slot) and written to `bot_state` on every pick, so a
 * restart carries on where the day stood; the first pick after local midnight starts the scope's
 * tally from zero. An entry that left the config simply stops being counted; a new one starts at 0.
 * A failing store never blocks a call — the pick is made from the in-memory tally and logged.
 */
class LlmSplitter(
    private val store: BotStateStore?,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) }
) {
    @Serializable
    private data class DayTally(val day: String, val counts: Map<String, Long> = emptyMap())

    private val tallies = HashMap<String, DayTally>()
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun pick(scope: String, legs: List<LlmOverride>): LlmOverride {
        require(legs.isNotEmpty()) { "empty providers split for $scope" }
        if (legs.size == 1) return legs.single()
        val day = today().toString()
        val tally = (tallies[scope] ?: load(scope)).takeIf { it.day == day } ?: DayTally(day)
        val keys = legs.map(::legKey)
        val total = keys.sumOf { tally.counts[it] ?: 0L }
        // Deficit against the share of (total + 1), scaled by 100 to stay in integers (exact ties).
        val idx = legs.indices.maxBy { i ->
            (legs[i].percent ?: 0) * (total + 1) - 100 * (tally.counts[keys[i]] ?: 0L)
        }
        val updated = tally.copy(counts = tally.counts + (keys[idx] to (tally.counts[keys[idx]] ?: 0L) + 1))
        tallies[scope] = updated
        save(scope, updated)
        logger.debug { "[LlmSplit] $scope → ${keys[idx]} (today ${updated.counts.filterKeys { it in keys }})" }
        return legs[idx]
    }

    /** Today's tally of every scope picked so far in this process (for `/status` and tests). */
    @Synchronized
    fun snapshot(): Map<String, Map<String, Long>> {
        val day = today().toString()
        return tallies.filterValues { it.day == day }.mapValues { it.value.counts }
    }

    private fun load(scope: String): DayTally {
        val raw = try {
            store?.getState(STATE_PREFIX + scope)
        } catch (e: Exception) {
            logger.warn(e) { "[LlmSplit] reading the tally of $scope failed — starting from zero" }
            null
        }
        if (raw.isNullOrBlank()) return DayTally("")
        return try {
            json.decodeFromString(DayTally.serializer(), raw)
        } catch (e: Exception) {
            logger.warn { "[LlmSplit] unreadable tally of $scope ('$raw') — starting from zero" }
            DayTally("")
        }
    }

    private fun save(scope: String, tally: DayTally) {
        try {
            store?.setState(STATE_PREFIX + scope, json.encodeToString(DayTally.serializer(), tally))
        } catch (e: Exception) {
            logger.warn(e) { "[LlmSplit] persisting the tally of $scope failed — kept in memory only" }
        }
    }

    companion object {
        const val STATE_PREFIX = "llm_split:"

        /** A split entry's identity in the tally: provider/model (blank = the provider default), plus batch. */
        fun legKey(leg: LlmOverride): String =
            "${leg.provider}/${leg.model.ifBlank { "<default>" }}" + if (leg.batch) "+batch" else ""
    }
}
