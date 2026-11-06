package app.zura.theme

import app.zura.ui.theme.SunClock
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SunClockTest {
    private fun near(t: ZonedDateTime, h: Int, m: Int) = kotlin.math.abs(t.hour * 60 + t.minute - (h * 60 + m)) <= 6

    @Test fun riyadhSummerSolstice() {
        // Published times for Riyadh, 21 June: sunrise ≈ 05:05, sunset ≈ 18:49 (UTC+3).
        val (rise, set) = SunClock.times(24.71, 46.68, LocalDate.of(2026, 6, 21))
        val z = ZoneId.of("Asia/Riyadh")
        assertTrue("rise ${rise!!.atZone(z)}", near(rise.atZone(z), 5, 5))
        assertTrue("set ${set!!.atZone(z)}", near(set.atZone(z), 18, 49))
    }

    @Test fun algiersWinter() {
        // Algiers, 21 December: sunrise ≈ 08:00, sunset ≈ 17:32 (UTC+1).
        val (rise, set) = SunClock.times(36.75, 3.06, LocalDate.of(2026, 12, 21))
        val z = ZoneId.of("Africa/Algiers")
        assertTrue("rise ${rise!!.atZone(z)}", near(rise.atZone(z), 8, 0))
        assertTrue("set ${set!!.atZone(z)}", near(set.atZone(z), 17, 32))
    }

    @Test fun polarNightHasNoSunrise() {
        val (rise, set) = SunClock.times(78.2, 15.6, LocalDate.of(2026, 12, 21)) // Svalbard
        assertNull(rise); assertNull(set)
    }
}
