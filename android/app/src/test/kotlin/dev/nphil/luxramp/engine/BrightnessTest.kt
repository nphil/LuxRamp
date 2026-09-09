package dev.nphil.luxramp.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The percent LuxRamp displays and ramps through must be the number the OS itself would show for
 * the same linear brightness, or the app and the system slider disagree about what "70 %" means.
 * The four pairs below were read off `dumpsys display` on the target device; a percent scale that
 * was linear in the float, or gamma'd against the full 0..1 range instead of [Brightness.NORMAL_MAX],
 * misses all four.
 */
class BrightnessTest {

    /** A percent is only ever shown as a whole number, so being inside half a point is exact enough. */
    private val tolerance = 0.6f

    @Test
    fun `percent matches what the system reports for the same linear float`() {
        assertEquals(70f, Brightness.toPercent(0.10131326f), tolerance)
        assertEquals(10f, Brightness.toPercent(0.001709819f), tolerance)
        assertEquals(81f, Brightness.toPercent(0.18362173f), tolerance)
        assertEquals(47f, Brightness.toPercent(0.03750879f), tolerance)
    }

    @Test
    fun `the normal range tops out at 100 percent and sunlight mode reads above it`() {
        assertEquals(100f, Brightness.toPercent(Brightness.NORMAL_MAX), 0.01f)
        // Clamping the input would hide HBM behind a readout stuck at full.
        assertTrue("HBM must read above 100 %", Brightness.toPercent(1f) > 100f)
    }

    @Test
    fun `percent round trips back to the brightness it came from`() {
        val brightnesses = floatArrayOf(
            Brightness.MIN,
            0.03750879f,
            0.10131326f,
            0.18362173f,
            0.25f,
            Brightness.NORMAL_MAX,
            0.75f,
            1f,
        )
        for (linear in brightnesses) {
            assertEquals(linear, Brightness.fromPercent(Brightness.toPercent(linear)), 1e-4f)
        }
    }

    @Test
    fun `out of range percents land inside the display's limits`() {
        assertEquals(0f, Brightness.fromPercent(-20f), 0f)
        assertEquals(1f, Brightness.fromPercent(400f), 0f)
        assertEquals(0f, Brightness.toPercent(-0.5f), 0f)
    }

    @Test
    fun `the settings int uses this device's 0 to 512 scale`() {
        // Ground truth: setting 94 is the 0.18362 float the display reports.
        assertEquals(94, Brightness.toSetting(0.18362173f))
        assertEquals(0.18359375f, Brightness.fromSetting(94), 0f)

        assertEquals(0, Brightness.toSetting(0f))
        assertEquals(Brightness.SETTING_MAX, Brightness.toSetting(1f))
        assertEquals(Brightness.SETTING_MAX, Brightness.toSetting(1.5f))
        assertEquals(0, Brightness.toSetting(-1f))
        assertEquals(0f, Brightness.fromSetting(-30), 0f)
        assertEquals(1f, Brightness.fromSetting(9_000), 0f)
    }
}
