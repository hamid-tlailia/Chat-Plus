package app.zura.theme

import app.zura.ui.theme.SunClock
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SunClockTest {
    private var day: LocalDate = LocalDate.MIN
    /** Same DAY (the bug was tomorrow's times) and within 6 minutes. */
    private fun near(t: ZonedDateTime, h: Int, m: Int) = t.toLocalDate() == day && kotlin.math.abs(t.hour * 60 + t.minute - (h * 60 + m)) <= 6

    @Test fun riyadhSummerSolstice() {
        // Published times for Riyadh, 21 June: sunrise ≈ 05:05, sunset ≈ 18:49 (UTC+3).
        day = LocalDate.of(2026, 6, 21)
        val (rise, set) = SunClock.times(24.71, 46.68, day)
        val z = ZoneId.of("Asia/Riyadh")
        assertTrue("rise ${rise!!.atZone(z)}", near(rise.atZone(z), 5, 5))
        assertTrue("set ${set!!.atZone(z)}", near(set.atZone(z), 18, 49))
    }

    @Test fun algiersWinter() {
        // Algiers, 21 December: sunrise ≈ 08:00, sunset ≈ 17:32 (UTC+1).
        day = LocalDate.of(2026, 12, 21)
        val (rise, set) = SunClock.times(36.75, 3.06, day)
        val z = ZoneId.of("Africa/Algiers")
        assertTrue("rise ${rise!!.atZone(z)}", near(rise.atZone(z), 8, 0))
        assertTrue("set ${set!!.atZone(z)}", near(set.atZone(z), 17, 32))
    }

    @Test fun polarNightHasNoSunrise() {
        val (rise, set) = SunClock.times(78.2, 15.6, LocalDate.of(2026, 12, 21)) // Svalbard
        assertNull(rise); assertNull(set)
    }

    @Test fun middayIsDay() {
        // The reported case: early afternoon must be between TODAY's sunrise and sunset.
        day = LocalDate.of(2026, 11, 6)
        val z = ZoneId.of("Asia/Riyadh")
        val (rise, set) = SunClock.times(30.0, 45.0, day)
        val noonish = day.atTime(13, 52).atZone(z).toInstant()
        assertTrue("$rise .. $set", noonish in rise!!..set!!)
        val night = day.atTime(21, 0).atZone(z).toInstant()
        assertTrue(night !in rise..set)
    }
}
