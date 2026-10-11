package metifikys.format

import metifikys.config.DateLineConfig
import metifikys.model.ShortlistItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `post.dateLine`: `📅 понеділок, 19 жовтня 2026 р. · через 8 днів` under a post whose event has a
 * day-precise `eventDate` from today on — both parts date-time entities ([TopicFormatter.timeToken]),
 * so every reader sees the day in their own zone and language and the countdown keeps itself
 * current. The day is anchored at noon in the bot's zone, which keeps it the same calendar day for
 * every reader. Pure.
 */
object DateLine {

    private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val FALLBACK: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun line(item: ShortlistItem?, cfg: DateLineConfig, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        if (item == null) return null
        if (cfg.eventTypes.isNotEmpty() && cfg.eventTypes.none { it.equals(item.eventType.trim(), ignoreCase = true) }) return null
        val raw = item.eventDate?.trim()?.takeIf { DAY.matches(it) } ?: return null
        val day = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return null
        if (day.isBefore(today)) return null
        val unix = day.atTime(12, 0).atZone(zone).toEpochSecond()
        val fallback = day.format(FALLBACK)
        return "📅 ${TopicFormatter.timeToken(unix, "wD", fallback)} · ${TopicFormatter.timeToken(unix, "r", fallback)}"
    }
}
