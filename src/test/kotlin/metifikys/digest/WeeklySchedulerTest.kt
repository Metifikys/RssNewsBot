package metifikys.digest

import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class WeeklySchedulerTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(date: LocalDate, time: LocalTime): ZonedDateTime =
        ZonedDateTime.of(date, time, zone)

    // 2024-01-03 is a Wednesday; 2024-01-07 is the following Sunday.
    private val wed = LocalDate.of(2024, 1, 3)
    private val sun = LocalDate.of(2024, 1, 7)

    @Test
    fun `picks the upcoming target day later this week`() {
        val now = at(wed, LocalTime.of(10, 0))
        val next = WeeklyScheduler.nextRun(now, DayOfWeek.SUNDAY, LocalTime.of(18, 0))
        assertEquals(at(sun, LocalTime.of(18, 0)), next)
    }

    @Test
    fun `same day before the time fires today`() {
        val now = at(sun, LocalTime.of(17, 0))
        val next = WeeklyScheduler.nextRun(now, DayOfWeek.SUNDAY, LocalTime.of(18, 0))
        assertEquals(at(sun, LocalTime.of(18, 0)), next)
    }

    @Test
    fun `same day exactly at the time rolls to next week`() {
        val now = at(sun, LocalTime.of(18, 0))
        val next = WeeklyScheduler.nextRun(now, DayOfWeek.SUNDAY, LocalTime.of(18, 0))
        assertEquals(at(sun.plusWeeks(1), LocalTime.of(18, 0)), next)
    }

    @Test
    fun `same day after the time rolls to next week`() {
        val now = at(sun, LocalTime.of(19, 30))
        val next = WeeklyScheduler.nextRun(now, DayOfWeek.SUNDAY, LocalTime.of(18, 0))
        assertEquals(at(sun.plusWeeks(1), LocalTime.of(18, 0)), next)
    }

    @Test
    fun `the result is always strictly in the future`() {
        val now = at(wed, LocalTime.of(10, 15))
        val next = WeeklyScheduler.nextRun(now, DayOfWeek.WEDNESDAY, LocalTime.of(10, 15))
        // now is exactly Wednesday 10:15 → must roll a full week forward.
        assertEquals(at(wed.plusWeeks(1), LocalTime.of(10, 15)), next)
    }
}
