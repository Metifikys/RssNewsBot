package metifikys.format

import metifikys.config.DateLineConfig
import metifikys.model.ShortlistItem
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DateLineTest {

    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val today = LocalDate.of(2026, 10, 11)

    private fun item(eventDate: String?, eventType: String = "release_date") = ShortlistItem(
        eventKey = "k", subject = "MMX", coreFact = "f", url = "https://example.com/k", status = "new",
        eventType = eventType, eventDate = eventDate
    )

    @Test
    fun `a day from today on gets the weekday-date and the countdown, both as time tokens`() {
        // 19.10.2026 12:00 Kyiv = 09:00 UTC.
        assertEquals(
            "📅 ⟦t:1792400400:wD|19.10.2026⟧ · ⟦t:1792400400:r|19.10.2026⟧",
            DateLine.line(item("2026-10-19"), DateLineConfig(), today, kyiv)
        )
        assertEquals("📅 ⟦t:1791709200:wD|11.10.2026⟧ · ⟦t:1791709200:r|11.10.2026⟧", DateLine.line(item("2026-10-11"), DateLineConfig(), today, kyiv))
    }

    @Test
    fun `no line for a past day, a month, a quarter, no date, or a type outside the list`() {
        assertNull(DateLine.line(item("2026-10-10"), DateLineConfig(), today, kyiv))
        assertNull(DateLine.line(item("2026-11"), DateLineConfig(), today, kyiv))
        assertNull(DateLine.line(item("2027-Q1"), DateLineConfig(), today, kyiv))
        assertNull(DateLine.line(item(null), DateLineConfig(), today, kyiv))
        assertNull(DateLine.line(item("2026-02-30"), DateLineConfig(), today, kyiv))
        assertNull(DateLine.line(item("2026-10-19", "rumor"), DateLineConfig(eventTypes = listOf("release_date", "delay")), today, kyiv))
        assertNull(DateLine.line(null, DateLineConfig(), today, kyiv))
    }

    @Test
    fun `toHtml turns a valid time token into a tg-time entity and leaves anything else as text`() {
        assertEquals(
            "📅 <tg-time unix=\"1792400400\" format=\"wD\">19.10.2026</tg-time> · <b>x</b>",
            TopicFormatter.toHtml("📅 ⟦t:1792400400:wD|19.10.2026⟧ · **x**")
        )
        assertEquals("⟦t:1:zz|a⟧ &lt;b&gt;", TopicFormatter.toHtml("⟦t:1:zz|a⟧ <b>"), "an invalid format is not an entity")
        assertEquals("<tg-time unix=\"5\" format=\"r\">&lt;i&gt;</tg-time>", TopicFormatter.toHtml(TopicFormatter.timeToken(5, "r", "<i>")))
    }
}
