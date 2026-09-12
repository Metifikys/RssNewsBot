package metifikys.digest

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackerDatesTest {

    @Test
    fun `normalize accepts the four forms and rejects the rest`() {
        assertEquals("2026-10-14", TrackerDates.normalize(" 2026-10-14 "))
        assertEquals("2026-10", TrackerDates.normalize("2026-10"))
        assertEquals("2027-Q1", TrackerDates.normalize("2027-q1"))
        assertEquals("2027", TrackerDates.normalize("2027"))
        assertNull(TrackerDates.normalize(null))
        assertNull(TrackerDates.normalize(""))
        assertNull(TrackerDates.normalize("14.10.2026"))
        assertNull(TrackerDates.normalize("2026-13"))
        assertNull(TrackerDates.normalize("2026-02-30"))
        assertNull(TrackerDates.normalize("2027-Q5"))
        assertNull(TrackerDates.normalize("soon"))
    }

    @Test
    fun `start and end bound the period`() {
        assertEquals(LocalDate.of(2026, 10, 14), TrackerDates.start("2026-10-14"))
        assertEquals(LocalDate.of(2026, 10, 14), TrackerDates.end("2026-10-14"))
        assertEquals(LocalDate.of(2026, 10, 1), TrackerDates.start("2026-10"))
        assertEquals(LocalDate.of(2026, 10, 31), TrackerDates.end("2026-10"))
        assertEquals(LocalDate.of(2027, 4, 1), TrackerDates.start("2027-Q2"))
        assertEquals(LocalDate.of(2027, 6, 30), TrackerDates.end("2027-Q2"))
        assertEquals(LocalDate.of(2027, 1, 1), TrackerDates.start("2027"))
        assertEquals(LocalDate.of(2027, 12, 31), TrackerDates.end("2027"))
        assertNull(TrackerDates.start(null))
        assertNull(TrackerDates.end("garbage"))
        assertEquals(2027, TrackerDates.yearOf("2027-Q2"))
    }

    @Test
    fun `display is reader-facing`() {
        assertEquals("14.10.2026", TrackerDates.display("2026-10-14"))
        assertEquals("10.2026", TrackerDates.display("2026-10"))
        assertEquals("Q1 2027", TrackerDates.display("2027-Q1"))
        assertEquals("2027", TrackerDates.display("2027"))
        assertEquals("TBA", TrackerDates.display(null))
    }
}
