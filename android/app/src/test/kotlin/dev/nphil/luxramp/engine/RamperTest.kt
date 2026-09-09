package dev.nphil.luxramp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ramping is the difference between a brightness change you notice and one you don't. The clock is
 * the caller's, so every timing rule here is checkable exactly: the fade takes the direction's
 * duration, it lands on the target rather than near it, and a sensor repeating itself cannot keep
 * restarting it.
 *
 * The three brightnesses are the device's own ground-truth points: 0.03750879 is 47 %, 0.10131326
 * is 70 %, 0.18362173 is 81 %.
 */
class RamperTest {

    private val dim = 0.03750879f
    private val mid = 0.10131326f
    private val bright = 0.18362173f

    private val ramper = Ramper(upMillis = 400L, downMillis = 1_500L)

    @Test
    fun `priming adopts a brightness without a fade`() {
        ramper.prime(mid)

        assertFalse(ramper.isRamping)
        assertEquals(mid, ramper.current, 0f)
        assertEquals(mid, ramper.target, 0f)
        assertEquals(mid, ramper.step(9_999L), 0f)
        // Retargeting with nothing to fade from is a prime too, so NaN never reaches the display.
        val fresh = Ramper(upMillis = 400L, downMillis = 1_500L)
        assertTrue(fresh.current.isNaN())
        fresh.retarget(mid, 0L)
        assertEquals(mid, fresh.current, 0f)
        assertFalse(fresh.isRamping)
    }

    @Test
    fun `the fade lands exactly on the target when the up duration is up`() {
        ramper.prime(dim)
        ramper.retarget(mid, 0L)
        assertTrue(ramper.isRamping)

        assertTrue("still fading a millisecond early", ramper.step(399L) != mid)
        assertTrue(ramper.isRamping)
        assertEquals(mid, ramper.step(400L), 0f)
        assertFalse(ramper.isRamping)
        assertEquals(mid, ramper.step(10_000L), 0f)
    }

    @Test
    fun `halfway through the fade is halfway in percent, not in linear brightness`() {
        ramper.prime(dim)
        ramper.retarget(mid, 0L)

        val halfway = ramper.step(200L)
        val expectedPercent = (Brightness.toPercent(dim) + Brightness.toPercent(mid)) / 2f
        assertEquals(expectedPercent, Brightness.toPercent(halfway), 0.01f)
        // Half the perceived distance is dimmer than half the linear distance; that asymmetry is
        // the whole reason the ramp runs in gamma space.
        assertTrue(halfway < (dim + mid) / 2f)
    }

    @Test
    fun `dimming takes the down duration`() {
        ramper.prime(mid)
        ramper.retarget(dim, 0L)

        val atUpDuration = ramper.step(400L)
        assertTrue("a dim must not finish in the up duration", ramper.isRamping)
        assertTrue(atUpDuration in dim..mid)
        assertTrue(ramper.step(1_499L) != dim)
        assertEquals(dim, ramper.step(1_500L), 0f)
        assertFalse(ramper.isRamping)
    }

    @Test
    fun `asking again for the target already in flight changes nothing`() {
        ramper.prime(dim)
        ramper.retarget(mid, 0L)
        val halfway = ramper.step(200L)

        ramper.retarget(mid, 200L)
        assertTrue(ramper.isRamping)
        assertEquals(halfway, ramper.current, 0f)
        // The original clock still runs: a restart here would leave the fade halfway at 400 ms and
        // a sensor confirming the same reading every frame would never let it arrive.
        assertEquals(mid, ramper.step(400L), 0f)
        assertFalse(ramper.isRamping)

        ramper.retarget(mid, 400L)
        assertFalse(ramper.isRamping)
        assertEquals(mid, ramper.current, 0f)
    }

    @Test
    fun `a new target mid-fade restarts from where the fade got to`() {
        ramper.prime(dim)
        ramper.retarget(mid, 0L)
        val halfway = ramper.step(200L)

        ramper.retarget(bright, 200L)
        assertEquals("retargeting must not move the backlight itself", halfway, ramper.step(200L), 0f)
        assertEquals(bright, ramper.target, 0f)

        // 200 ms into a fresh 400 ms fade, so halfway between where it was and the new target.
        val expectedPercent = (Brightness.toPercent(halfway) + Brightness.toPercent(bright)) / 2f
        assertEquals(expectedPercent, Brightness.toPercent(ramper.step(400L)), 0.01f)
        assertEquals(bright, ramper.step(600L), 0f)
        assertFalse(ramper.isRamping)
    }

    @Test
    fun `a clock that jumps backwards holds the fade where it is`() {
        ramper.prime(dim)
        ramper.retarget(mid, 1_000L)
        val halfway = ramper.step(1_200L)

        assertEquals("a backwards clock must not rewind the fade", halfway, ramper.step(900L), 0f)
        assertTrue(ramper.isRamping)
        // The schedule is unchanged, so the fade resumes and finishes on its original clock.
        assertEquals(halfway, ramper.step(1_200L), 0f)
        assertEquals(mid, ramper.step(1_400L), 0f)
    }
}
