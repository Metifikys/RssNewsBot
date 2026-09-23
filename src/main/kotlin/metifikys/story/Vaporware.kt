package metifikys.story

import metifikys.config.VaporwareLineConfig
import metifikys.db.EventHistoryRow
import metifikys.digest.TrackerDates
import java.time.Duration
import java.time.LocalDateTime

/**
 * The vaporware counter (plan item 10): how many times this entity has been "announced" — a new
 * wave of announcement / rumour / leak / dev-quote posts — while nobody ever gave it a date. Pure;
 * reads the entity's `event_history`.
 *
 * - Only for an item of an `announceTypes` type that carries no date of its own.
 * - Any history row with a hard date (a day or a month) means it is not vaporware: no line. A soft
 *   date (a quarter or a year) stops the count only with `softDatesStop`; otherwise such a row
 *   counts as an undated announcement.
 * - The undated announcements plus the current item form waves: a new wave starts after a gap of
 *   at least `minGapDays`. The current item's number is the count of waves it closes; the line
 *   shows from `minAnnouncements`.
 */
object Vaporware {

    data class Result(val number: Int, val firstAt: LocalDateTime)

    fun count(
        eventType: String,
        eventDate: String?,
        history: List<EventHistoryRow>,
        cfg: VaporwareLineConfig,
        now: LocalDateTime
    ): Result? {
        val types = cfg.announceTypes.map { it.lowercase() }.toSet()
        if (eventType.lowercase() !in types) return null
        if (stops(TrackerDates.normalize(eventDate), cfg)) return null
        if (history.any { stops(TrackerDates.normalize(it.eventDate), cfg) }) return null

        val times = history
            .filter { it.eventType.lowercase() in types }
            .map { it.coveredAt }
            .filter { it <= now }
            .sorted() + now
        var waves = 1
        for (i in 1 until times.size) {
            if (Duration.between(times[i - 1], times[i]).toDays() >= cfg.minGapDays) waves++
        }
        if (waves < cfg.minAnnouncements) return null
        return Result(waves, times.first())
    }

    /** A date that ends the count: always a day or a month; a quarter or a year only with `softDatesStop`. */
    private fun stops(date: String?, cfg: VaporwareLineConfig): Boolean {
        val d = date ?: return false
        val hard = d.length >= 7 && !d.contains('Q') // YYYY-MM or YYYY-MM-DD
        return hard || cfg.softDatesStop
    }
}
