package app.zura.call

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MuLawTest {
    @Test fun roundTripStaysClose() {
        for (s in listOf(0, 1, -1, 100, -100, 1000, -1000, 8000, -8000, 20000, -20000, 32767, -32768)) {
            val back = MuLaw.decode(MuLaw.encode(s.toShort())).toInt()
            // μ-law is logarithmic: error grows with amplitude but stays a few % of the value.
            val tolerance = maxOf(16, abs(s) / 16)
            assertTrue("$s → $back", abs(back - s.coerceIn(-32635, 32635)) <= tolerance)
        }
    }

    @Test fun silenceIsSilent() {
        assertTrue(abs(MuLaw.decode(MuLaw.encode(0)).toInt()) <= 8)
    }
}
