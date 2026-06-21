package metifikys.digest

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Fires [runnable] once per week at [day] + [time] in [zone]. Uses single-shot re-arming (each run
 * schedules the next) rather than a fixed-rate timer, so it stays aligned to the wall clock across
 * DST shifts and never drifts. The first fire is computed fresh on [start], so a restart can post at
 * most one extra weekly digest only if it happens to land inside the post window — acceptable, and
 * the same staleness tradeoff the status poster makes.
 */
class WeeklyScheduler(
    private val day: DayOfWeek,
    private val time: LocalTime,
    private val runnable: () -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "weekly-digest").also { it.isDaemon = true }
        }
) {

    fun start() {
        scheduleNext()
    }

    private fun scheduleNext() {
        val now = ZonedDateTime.now(zone)
        val next = nextRun(now, day, time)
        val delay = Duration.between(now, next)
        logger.info {
            "[Weekly] Next weekly digest at $next " +
                "(in ${delay.toDays()}d ${delay.toHoursPart()}h ${delay.toMinutesPart()}m)."
        }
        executor.schedule({ fire() }, delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    private fun fire() {
        try {
            runnable()
        } catch (e: Exception) {
            logger.error(e) { "[Weekly] Weekly digest run threw — will reschedule for next week." }
        } finally {
            scheduleNext()
        }
    }

    fun shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /**
         * Strictly-future next occurrence of [day] at [time], relative to [now]. When [now] is
         * already on [day] but at/after [time], rolls to the same day next week.
         */
        fun nextRun(now: ZonedDateTime, day: DayOfWeek, time: LocalTime): ZonedDateTime {
            var candidate = now.with(TemporalAdjusters.nextOrSame(day))
                .toLocalDate()
                .atTime(time)
                .atZone(now.zone)
                .truncatedTo(ChronoUnit.SECONDS)
            if (!candidate.isAfter(now)) {
                candidate = candidate.plusWeeks(1)
            }
            return candidate
        }
    }
}
