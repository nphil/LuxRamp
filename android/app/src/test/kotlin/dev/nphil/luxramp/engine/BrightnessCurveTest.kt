package dev.nphil.luxramp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curve is what makes LuxRamp behave like the display it replaces: it has to reproduce the
 * stock table at its own points, fill the gaps the way the platform does (in log lux, not in lux),
 * and take the user's preference as a gamma rather than as a shove up or down.
 */
class BrightnessCurveTest {

    private val curve = BrightnessCurve.STOCK

    @Test
    fun `a table point comes back untouched`() {
        assertEquals(0.1856375f, curve.brightnessFor(200f, 0f), 0f)
        assertEquals(0.06765999f, curve.brightnessFor(10f, 0f), 0f)
        assertEquals(1f, curve.brightnessFor(6_000f, 0f), 0f)
    }

    @Test
    fun `readings off the ends of the table hold the end values`() {
        assertEquals(0.008549097f, curve.brightnessFor(0f, 0f), 0f)
        assertEquals(0.008549097f, curve.brightnessFor(-5f, 0f), 0f)
        assertEquals(1f, curve.brightnessFor(9_000f, 0f), 0f)
    }

    @Test
    fun `a reading between two points interpolates in log lux`() {
        val at10 = curve.brightnessFor(10f, 0f)
        val at15 = curve.brightnessFor(15f, 0f)
        val at13 = curve.brightnessFor(13f, 0f)

        assertTrue("13 lux must sit above the 10 lux point", at13 > at10)
        assertTrue("13 lux must sit below the 15 lux point", at13 < at15)
        // log10(14) is 64.4 % of the way from log10(11) to log10(16), where 13 lux is only 60 % of
        // the way from 10 to 15, so interpolating in lux would land lower.
        assertTrue("must interpolate in log lux", at13 > at10 + 0.6f * (at15 - at10))
        assertEquals(0.08306675f, at13, 1e-6f)
    }

    @Test
    fun `a positive offset brightens and a negative one dims`() {
        val neutral = curve.brightnessFor(100f, 0f)

        assertTrue(curve.brightnessFor(100f, 0.145f) > neutral)
        assertTrue(curve.brightnessFor(100f, 0.5f) > curve.brightnessFor(100f, 0.145f))
        assertTrue(curve.brightnessFor(100f, -0.145f) < neutral)
        assertTrue(curve.brightnessFor(100f, -0.5f) < curve.brightnessFor(100f, -0.145f))
    }

    @Test
    fun `the offset is a gamma, so it lifts the dim end hardest`() {
        val dimLift = curve.brightnessFor(10f, 0.5f) / curve.brightnessFor(10f, 0f)
        val brightLift = curve.brightnessFor(1_300f, 0.5f) / curve.brightnessFor(1_300f, 0f)

        assertTrue("dim lift $dimLift must exceed bright lift $brightLift", dimLift > brightLift)
        // Nothing can be brighter than full, so at the top of the curve the offset does nothing.
        assertEquals(1f, curve.brightnessFor(6_000f, 0.5f), 0f)
    }

    @Test
    fun `the result stays within what the display accepts`() {
        // Cubing the dimmest table value would ask for 6e-7, which is the panel off, not dim.
        assertEquals(Brightness.MIN, curve.brightnessFor(0f, -1f), 0f)
        assertEquals(1f, curve.brightnessFor(9_000f, 1f), 0f)
    }

    @Test
    fun `brightness never falls as the room gets lighter`() {
        var previous = -1f
        var lux = 0f
        while (lux <= 6_000f) {
            val brightness = curve.brightnessFor(lux, 0.145f)
            assertTrue("$brightness at $lux lux is below $previous", brightness >= previous)
            previous = brightness
            lux += 5f
        }
    }
}
