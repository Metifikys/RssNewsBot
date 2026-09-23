package metifikys.story

import metifikys.config.BarometerSeriesConfig
import metifikys.db.EventHistoryRow
import metifikys.digest.TrackerDates
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * The weekly industry barometer (plan item 12): per ISO week (Monday–Sunday), how many distinct
 * entities fell into each series, over the last `weeks` weeks ending with the week of `today`.
 * Pure counting and the text renderings (caption, sparkline block); the picture is [BarometerChart].
 */
object Barometer {

    class Series(val id: String, val label: String, val counts: List<Int>) {
        /** This week's count (the last bar). */
        val current: Int get() = counts.last()
        val mean: Double get() = if (counts.isEmpty()) 0.0 else counts.sum().toDouble() / counts.size
    }

    class Result(
        /** Monday of each week, oldest first; the last one is the current week. */
        val weekStarts: List<LocalDate>,
        val series: List<Series>
    )

    fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Counts [rows] (a category's `event_history`) into [weeks] weekly buckets per series. An entity
     * (the normalized subject; the event key when there is none) counts once per week and series.
     * A [BarometerSeriesConfig.byEventDate] series places each entity at the week of its latest
     * known day-precise `eventDate` (by `covered_at`), so a delay moves the release.
     */
    fun weekly(rows: List<EventHistoryRow>, series: List<BarometerSeriesConfig>, weeks: Int, today: LocalDate): Result {
        val last = weekStart(today)
        val starts = (weeks - 1 downTo 0).map { last.minusWeeks(it.toLong()) }
        val index = starts.withIndex().associate { (i, d) -> d to i }
        val out = series.map { s ->
            val buckets = List(weeks) { mutableSetOf<String>() }
            val matching = rows.filter { matches(it, s) }
            if (s.byEventDate) {
                matching
                    .filter { TrackerDates.normalize(it.eventDate)?.length == DAY_LENGTH }
                    .groupBy { entityOf(it) }
                    .forEach { (entity, rs) ->
                        val latest = rs.maxBy { it.coveredAt }
                        val date = LocalDate.parse(latest.eventDate!!.trim())
                        index[weekStart(date)]?.let { buckets[it] += entity }
                    }
            } else {
                for (r in matching) index[weekStart(r.coveredAt.toLocalDate())]?.let { buckets[it] += entityOf(r) }
            }
            Series(s.id, s.label, buckets.map { it.size })
        }
        return Result(starts, out)
    }

    private fun matches(r: EventHistoryRow, s: BarometerSeriesConfig): Boolean {
        val type = r.eventType.trim().lowercase()
        if (s.eventTypes.any { it.equals(type, ignoreCase = true) }) return true
        if (s.keywords.isEmpty() || s.keywordEventTypes.none { it.equals(type, ignoreCase = true) }) return false
        // The subject only: core facts mention layoffs in passing (a festival "despite the layoffs",
        // "cost cuts"), subjects name what happened ("Bethesda Game Studios (звільнення)").
        val subject = r.subject.lowercase()
        return s.keywords.any { it.isNotBlank() && it.lowercase() in subject }
    }

    private fun entityOf(r: EventHistoryRow): String = r.entityKey.ifBlank { "event:" + r.eventKey }

    // ── text renderings ─────────────────────────────────────────────────────

    private const val DAY_LENGTH = 10
    private const val BARS = "▂▃▄▅▆▇█"
    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

    /** `▁▂▅█`: zero is the flat `▁`, other counts scale over `▂`…`█` by the series' maximum. */
    fun sparkline(counts: List<Int>): String {
        val max = counts.maxOrNull() ?: return ""
        return counts.joinToString("") { v ->
            if (v <= 0 || max <= 0) "▁" else BARS[((v.toDouble() / max) * (BARS.length - 1)).roundToInt()].toString()
        }
    }

    /** `1,8` — one decimal, Ukrainian comma. */
    fun decimal(x: Double): String = String.format(java.util.Locale.ROOT, "%.1f", x).replace('.', ',')

    /**
     * The photo caption: `📊 Барометр індустрії за нашими джерелами (12 тиж. до 27.09). За тиждень:
     * Звільнення і закриття — 3 (у середньому 1,8) · Релізи — 14 (у середньому 9,5)`.
     */
    fun caption(r: Result): String {
        val weekly = r.series.joinToString(" · ") { "${it.label} — ${it.current} (у середньому ${decimal(it.mean)})" }
        return "📊 Барометр індустрії за нашими джерелами (${r.weekStarts.size} тиж. до ${r.weekStarts.last().plusDays(6).format(DAY_MONTH)}). " +
            "За тиждень: $weekly"
    }

    /**
     * The text form (`format: text`, and the fallback when the chart cannot be drawn):
     * ```
     * 📊 Барометр індустрії (12 тиж., за нашими джерелами)
     * Звільнення і закриття ▁▂▁▃▅▂▁▁▂▃▁▇ 3 (у середньому 1,8)
     * ```
     */
    fun textBlock(r: Result): String = buildString {
        append("📊 Барометр індустрії (${r.weekStarts.size} тиж., за нашими джерелами)")
        for (s in r.series) {
            append('\n').append(s.label).append(' ').append(sparkline(s.counts))
                .append(' ').append(s.current).append(" (у середньому ").append(decimal(s.mean)).append(')')
        }
    }
}
