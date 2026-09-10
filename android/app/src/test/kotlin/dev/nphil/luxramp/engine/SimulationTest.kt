package dev.nphil.luxramp.engine

import dev.nphil.luxramp.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The preview is only worth showing if it is the loop rather than a picture of it, so what is
 * checked here is that the tuning still reaches the playback: a shorter ramp arrives sooner, a
 * higher offset lands brighter, the result stays inside the range the display will accept, and the
 * default scenario is long enough for its last fade to finish.
 */
class SimulationTest {

    private val defaults = Prefs()

    /** Dim room into bright daylight indoors: one brightening step, held long enough to settle. */
    private val brightening = Scenario(
        name = "step-up",
        steps = listOf(Step(holdMillis = 600L, lux = 4f), Step(holdMillis = 5_000L, lux = 1_200f)),
    )

    @Test
    fun `a shorter ramp covers a brightening step sooner`() {
        val fast = Simulation.run(defaults.copy(rampUpMillis = 150L), brightening)
        val slow = Simulation.run(defaults.copy(rampUpMillis = 1_400L), brightening)

        // Both runs end on the same brightness, so the two timings are measured against the same
        // distance and the comparison is about the ramp alone.
        assertEquals(slow.last().currentLinear, fast.last().currentLinear, 1e-5f)
        val quick = millisToCover(fast)
        val sluggish = millisToCover(slow)
        assertTrue("150 ms ramp took $quick ms, 1400 ms ramp took $sluggish ms", quick < sluggish)
    }

    @Test
    fun `the simulation never leaves the range the display accepts`() {
        // Both extremes of the offset: the gamma pushes the curve off the bottom of the panel's
        // range at -1 and against the top of it at +1, which is where a missing clamp would show.
        for (offset in floatArrayOf(-1f, 0f, 1f)) {
            val trace = Simulation.run(defaults.copy(offset = offset))
            assertTrue("offset $offset produced no samples", trace.size > 100)
            for (sample in trace) {
                assertTrue(
                    "offset $offset wrote ${sample.currentLinear} at ${sample.timeMillis} ms",
                    sample.currentLinear >= Brightness.MIN && sample.currentLinear <= 1f,
                )
                assertTrue(
                    "offset $offset aimed at ${sample.targetLinear} at ${sample.timeMillis} ms",
                    sample.targetLinear >= Brightness.MIN && sample.targetLinear <= 1f,
                )
            }
        }
    }

    @Test
    fun `a higher offset settles brighter at the same light`() {
        var previous = 0f
        for (offset in floatArrayOf(-0.2f, 0f, 0.145f, 0.4f)) {
            val settled = Simulation.run(defaults.copy(offset = offset), brightening).last().currentLinear
            assertTrue("offset $offset settled at $settled, which is not above $previous", settled > previous)
            previous = settled
        }
    }

    @Test
    fun `the default scenario is long enough for its last fade to land`() {
        val last = Simulation.run(defaults).last()

        // A tenth of a percent of linear brightness is well under the digit the readout shows; a
        // fade still in flight at the end of the run is out by tens of times this.
        assertTrue(
            "ended aiming at ${last.targetLinear} while showing ${last.currentLinear}",
            abs(last.targetLinear - last.currentLinear) < 1e-3f,
        )
    }

    /** When the run first covers nine tenths of the perceived distance it ends up travelling. */
    private fun millisToCover(trace: List<SimSample>): Long {
        val start = Brightness.toPercent(trace.first().currentLinear)
        val end = Brightness.toPercent(trace.last().currentLinear)
        val threshold = start + (end - start) * 0.9f
        return trace.first { Brightness.toPercent(it.currentLinear) >= threshold }.timeMillis
    }
}
