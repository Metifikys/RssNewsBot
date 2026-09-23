package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import metifikys.db.BotStateStore
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

private val logger = KotlinLogging.logger {}

/**
 * Per-category digest pause set by `/pause`, kept in `bot_state` (`pause:<category>` = ISO
 * local time or `inf`) so it survives a restart. Only the digest half checks it — ingestion
 * keeps filling the queue, and an explicit `/run` ignores the pause. An expired pause is removed
 * the first time it is read.
 */
class PauseStore(
    private val state: BotStateStore,
    private val clock: () -> LocalDateTime = LocalDateTime::now
) {

    /** An active pause; [until] null = until `/resume`. */
    data class Pause(val until: LocalDateTime?)

    fun pause(category: String, until: LocalDateTime?) {
        state.setState(key(category), until?.toString() ?: INDEFINITE)
    }

    /** Lifts the pause; true when one was active. */
    fun resume(category: String): Boolean {
        val active = pausedUntil(category) != null
        state.deleteState(key(category))
        return active
    }

    /** The active pause of [category], or null. Never throws: a broken value reads as "not paused". */
    fun pausedUntil(category: String): Pause? {
        val raw = try {
            state.getState(key(category))
        } catch (e: Exception) {
            logger.warn(e) { "[Pause] reading the pause of $category failed — treating it as not paused" }
            return null
        } ?: return null
        if (raw == INDEFINITE) return Pause(null)
        val until = try {
            LocalDateTime.parse(raw)
        } catch (e: DateTimeParseException) {
            logger.warn { "[Pause] unparseable pause '$raw' for $category — removed" }
            state.deleteState(key(category))
            return null
        }
        if (!until.isAfter(clock())) {
            state.deleteState(key(category))
            logger.info { "[Pause] $category: pause until $until expired — digest resumes" }
            return null
        }
        return Pause(until)
    }

    private fun key(category: String) = "pause:$category"

    companion object {
        const val INDEFINITE = "inf"

        private val DURATION = Regex("""(\d{1,4})\s*([mhdw])""", RegexOption.IGNORE_CASE)

        /** `30m` / `6h` / `2d` / `1w` → a [Duration]; null for anything else or zero. */
        fun parseDuration(s: String): Duration? {
            val m = DURATION.matchEntire(s.trim()) ?: return null
            val n = m.groupValues[1].toLong()
            if (n <= 0) return null
            return when (m.groupValues[2].lowercase()) {
                "m" -> Duration.ofMinutes(n)
                "h" -> Duration.ofHours(n)
                "d" -> Duration.ofDays(n)
                else -> Duration.ofDays(7 * n)
            }
        }
    }
}
