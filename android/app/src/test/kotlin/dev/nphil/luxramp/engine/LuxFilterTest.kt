package dev.nphil.luxramp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter decides how the screen feels: too fast and a passing hand dims the panel, too slow and
 * walking outside leaves you squinting. Two properties are load-bearing and both are checked here
 * without recomputing the formula in the test.
 *
 * Averaging happens in the log domain, which shows up as a multiplicative identity: for equal time
 * constants a rise and a fall over the same interval land symmetrically about the geometric middle,
 * so `(1 + rise) * (1 + fall)` comes back to `(1 + from) * (1 + to)`. A linear average would land on
 * the arithmetic middle instead and overshoot that product by a factor of two and a half.
 */
class LuxFilterTest {

    @Test
    fun `the first reading is adopted outright`() {
        val filter = LuxFilter(tauUpMillis = 300L, tauDownMillis = 1_500L)
        assertTrue("no reading yet", filter.value.isNaN())

        assertEquals(123.5f, filter.submit(123.5f, 5_000L), 0f)
        assertEquals(123.5f, filter.value, 0f)

        // Coming back from a screen-off period must snap again, not fade up from the old room.
        filter.reset()
        assertTrue(filter.value.isNaN())
        assertEquals(4_000f, filter.submit(4_000f, 60_000L), 0f)
    }

    @Test
    fun `equal time constants make a rise and a fall mirror each other`() {
        val filter = LuxFilter(tauUpMillis = 500L, tauDownMillis = 500L)

        filter.submit(100f, 0L)
        val rise = filter.submit(1_000f, 700L)
        filter.reset()
        filter.submit(1_000f, 0L)
        val fall = filter.submit(100f, 700L)

        // dt of 1.4 tau closes 75 % of the log gap in both directions.
        assertEquals(567.6f, rise, 0.5f)
        assertEquals(176.8f, fall, 0.5f)
        assertEquals(101f * 1_001f, (1f + rise) * (1f + fall), 5f)
    }

    @Test
    fun `a shorter rise constant follows brightening sooner than dimming`() {
        val filter = LuxFilter(tauUpMillis = 300L, tauDownMillis = 1_500L)

        filter.submit(100f, 0L)
        val rise = filter.submit(1_000f, 300L)
        filter.reset()
        filter.submit(1_000f, 0L)
        val fall = filter.submit(100f, 300L)

        assertEquals(429.5f, rise, 0.5f)
        assertEquals(659.5f, fall, 0.5f)
        // What is left of the log gap reads as a ratio: 1001/(1+rise) against (1+fall)/101. The
        // riser has closed 63 % of it (dt == tauUp), the faller 18 % (dt == tauDown / 5), so the
        // riser's remainder is the smaller one — even though 429 lux looks like less travel.
        assertTrue("rise=$rise fall=$fall", 1_001f / (1f + rise) < (1f + fall) / 101f)
    }

    @Test
    fun `a clock that does not advance leaves the value alone`() {
        val filter = LuxFilter(tauUpMillis = 300L, tauDownMillis = 1_500L)
        filter.submit(100f, 1_000L)

        assertEquals(100f, filter.submit(4_000f, 1_000L), 0f)
        assertEquals(100f, filter.submit(4_000f, 900L), 0f)
        // Once time moves again the pending reading is followed as usual.
        assertTrue(filter.submit(4_000f, 1_300L) > 100f)
    }

    @Test
    fun `the deadband is a ratio, so it widens with the reading`() {
        val filter = LuxFilter(tauUpMillis = 300L, tauDownMillis = 1_500L, deadbandRatio = 0.10f)
        assertFalse("nothing to compare against yet", filter.settled(100f))

        filter.submit(100f, 0L)
        assertTrue(filter.settled(105f))
        assertFalse(filter.settled(120f))

        filter.reset()
        filter.submit(1_000f, 0L)
        // 20 lux of drift is a different room at 100 lux and noise at 1000.
        assertTrue(filter.settled(1_020f))
    }
}
