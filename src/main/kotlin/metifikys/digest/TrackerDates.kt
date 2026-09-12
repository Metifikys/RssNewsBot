package metifikys.digest

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * The partial-date vocabulary Step 1 may emit for an event (`eventDate`): a day, a month, a
 * quarter or a year. Pure helpers shared by the extractor's guard and the trackers.
 */
object TrackerDates {

    private val DAY = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val MONTH = Regex("""^(\d{4})-(\d{2})$""")
    private val QUARTER = Regex("""^(\d{4})-[Qq]([1-4])$""")
    private val YEAR = Regex("""^(\d{4})$""")
    private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM.yyyy")

    /** Canonical form (`2027-Q1` upper-cased, whitespace trimmed) or null when [raw] is not a date the vocabulary knows or is not a real day. */
    fun normalize(raw: String?): String? {
        val s = raw?.trim() ?: return null
        return when {
            DAY.matches(s) -> runCatching { LocalDate.parse(s); s }.getOrNull()
            MONTH.matches(s) -> runCatching { YearMonth.parse(s); s }.getOrNull()
            QUARTER.matches(s) -> s.uppercase()
            YEAR.matches(s) -> s
            else -> null
        }
    }

    fun yearOf(normalized: String): Int? = normalized.take(4).toIntOrNull()

    /** First day of the period — the sort key; null for an unparseable value. */
    fun start(normalized: String?): LocalDate? {
        val s = normalized ?: return null
        DAY.matchEntire(s)?.let { return LocalDate.parse(s) }
        MONTH.matchEntire(s)?.let { return YearMonth.parse(s).atDay(1) }
        QUARTER.matchEntire(s)?.let { m -> return LocalDate.of(m.groupValues[1].toInt(), (m.groupValues[2].toInt() - 1) * 3 + 1, 1) }
        YEAR.matchEntire(s)?.let { return LocalDate.of(s.toInt(), 1, 1) }
        return null
    }

    /** Last day of the period; a story is "out" once today is past it. */
    fun end(normalized: String?): LocalDate? {
        val s = normalized ?: return null
        DAY.matchEntire(s)?.let { return LocalDate.parse(s) }
        MONTH.matchEntire(s)?.let { return YearMonth.parse(s).atEndOfMonth() }
        QUARTER.matchEntire(s)?.let { m -> return YearMonth.of(m.groupValues[1].toInt(), m.groupValues[2].toInt() * 3).atEndOfMonth() }
        YEAR.matchEntire(s)?.let { return LocalDate.of(s.toInt(), 12, 31) }
        return null
    }

    /** Reader-facing form: `14.10.2026`, `10.2026`, `Q1 2027`, `2027`, `TBA`. */
    fun display(normalized: String?): String {
        val s = normalized ?: return "TBA"
        DAY.matchEntire(s)?.let { return LocalDate.parse(s).format(DAY_FMT) }
        MONTH.matchEntire(s)?.let { return YearMonth.parse(s).format(MONTH_FMT) }
        QUARTER.matchEntire(s)?.let { m -> return "Q${m.groupValues[2]} ${m.groupValues[1]}" }
        YEAR.matchEntire(s)?.let { return s }
        return "TBA"
    }
}
