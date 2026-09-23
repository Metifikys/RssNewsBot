package metifikys.story

import metifikys.config.BarometerConfig
import metifikys.db.EventHistoryRow
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalDateTime
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BarometerTest {

    /** A Wednesday; its ISO week starts on Monday 2026-09-21. */
    private val today = LocalDate.of(2026, 9, 23)

    private fun row(
        key: String,
        entity: String,
        type: String,
        at: LocalDateTime,
        date: String? = null,
        subject: String = entity
    ) = EventHistoryRow("gaming", key, entity, subject, "", type, "f", date, 5, at)

    private val series = BarometerConfig.DEFAULT_SERIES

    @Test
    fun `weeks run Monday to Sunday and the last one is the current week`() {
        val r = Barometer.weekly(
            listOf(
                row("a", "studio_a", "studio_closure", LocalDateTime.of(2026, 9, 21, 0, 5)), // Monday: this week
                row("b", "studio_b", "layoffs", LocalDateTime.of(2026, 9, 20, 23, 55)),      // Sunday: last week
                row("c", "studio_c", "layoffs", LocalDateTime.of(2026, 8, 1, 12, 0))          // outside a 4-week window
            ),
            series, weeks = 4, today = today
        )
        assertEquals(listOf(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21)), r.weekStarts)
        assertEquals(listOf(0, 0, 1, 1), r.series[0].counts)
    }

    @Test
    fun `an entity counts once a week, industry news counts only with a keyword`() {
        val t = LocalDateTime.of(2026, 9, 22, 10, 0)
        val r = Barometer.weekly(
            listOf(
                row("a1", "xbox", "layoffs", t),
                row("a2", "xbox", "industry_news", t, subject = "Xbox (звільнення 300 людей)"),
                row("b", "ubisoft", "industry_news", t, subject = "Ubisoft Layoffs hit Red Storm"),
                row("c", "valve", "industry_news", t, subject = "Valve hires"),
                row("c2", "ps3_store", "industry_news", t, subject = "Закриття цифрового магазину PS3"),
                row("d", "", "studio_closure", t),
                row("e", "", "studio_closure", t),
                row("f", "patch_notes", "patch", t, subject = "Звільнення патчу")
            ),
            series, weeks = 2, today = today
        )
        // xbox once, ubisoft by keyword, two entity-less closures by their event keys; valve, a store
        // shutdown and a patch (not a keyword type) are out.
        assertEquals(listOf(0, 4), r.series[0].counts)
    }

    @Test
    fun `releases count by their own day date, the latest one known per entity`() {
        val r = Barometer.weekly(
            listOf(
                row("r1", "silksong", "release_date", LocalDateTime.of(2026, 6, 1, 10, 0), date = "2026-09-08"),
                // A delay announced later moves the release into this week.
                row("r2", "silksong", "delay", LocalDateTime.of(2026, 9, 1, 10, 0), date = "2026-09-24"),
                row("r3", "hades_2", "release_date", LocalDateTime.of(2026, 7, 1, 10, 0), date = "2026-09-15"),
                row("r4", "gta_6", "release_date", LocalDateTime.of(2026, 7, 1, 10, 0), date = "2026-11"),
                row("r5", "future", "release_date", LocalDateTime.of(2026, 7, 1, 10, 0), date = "2026-12-01")
            ),
            series, weeks = 3, today = today
        )
        assertEquals(listOf(0, 1, 1), r.series[1].counts)
        assertEquals("Релізи", r.series[1].label)
    }

    @Test
    fun `sparkline, caption and text block`() {
        assertEquals("▁▃▅█", Barometer.sparkline(listOf(0, 1, 3, 6)))
        assertEquals("▁▁▁", Barometer.sparkline(listOf(0, 0, 0)))
        val r = Barometer.Result(
            listOf(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21)),
            listOf(Barometer.Series("layoffs", "Звільнення і закриття", listOf(1, 3)), Barometer.Series("releases", "Релізи", listOf(10, 14)))
        )
        assertEquals(
            "📊 Барометр індустрії за нашими джерелами (2 тиж. до 27.09). За тиждень: " +
                "Звільнення і закриття — 3 (у середньому 2,0) · Релізи — 14 (у середньому 12,0)",
            Barometer.caption(r)
        )
        assertEquals(
            "📊 Барометр індустрії (2 тиж., за нашими джерелами)\nЗвільнення і закриття ▄█ 3 (у середньому 2,0)\nРелізи ▆█ 14 (у середньому 12,0)",
            Barometer.textBlock(r)
        )
    }

    @Test
    fun `the chart is a 1200x675 PNG, also with an empty series`() {
        val starts = (11 downTo 0).map { LocalDate.of(2026, 9, 21).minusWeeks(it.toLong()) }
        for (counts in listOf(listOf(1, 0, 2, 3, 1, 0, 0, 4, 2, 1, 5, 3), List(12) { 0 })) {
            val r = Barometer.Result(starts, listOf(Barometer.Series("layoffs", "Звільнення і закриття", counts), Barometer.Series("releases", "Релізи", List(12) { 0 })))
            val png = BarometerChart.render(r, "Барометр індустрії · gaming", "12 тижнів до 27.09.2026 · за нашими джерелами")
            val img = ImageIO.read(ByteArrayInputStream(png))
            assertEquals(1200 to 675, img.width to img.height)
        }
        assertTrue(java.awt.GraphicsEnvironment.isHeadless())
    }

    @Test
    fun `nice y steps give at most five gridlines`() {
        assertEquals(listOf(1, 1, 2, 5, 10, 20), listOf(1, 5, 7, 22, 45, 81).map { BarometerChart.niceStep(it) })
    }
}
