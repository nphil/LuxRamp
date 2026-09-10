package dev.nphil.luxramp.engine

import dev.nphil.luxramp.data.Prefs
import kotlin.math.abs

/**
 * One tick of a simulated run: the same four numbers the controller publishes, on a synthetic clock.
 *
 * [filteredLux] is what the smoothing made of [lux], [targetLinear] is where the curve pointed the
 * ramp, and [currentLinear] is what would have been written to the panel at [timeMillis].
 */
data class SimSample(
    val timeMillis: Long,
    val lux: Float,
    val filteredLux: Float,
    val targetLinear: Float,
    val currentLinear: Float,
)

/** A stretch of a scenario holding one ambient reading, as if the room simply stayed that way. */
data class Step(val holdMillis: Long, val lux: Float)

/**
 * A light scenario to play a tuning against.
 *
 * [name] is never shown; it identifies the run in test failures and gives a future scenario picker
 * something stable to key on.
 */
data class Scenario(val name: String, val steps: List<Step>) {

    /** How long a full playback lasts, which is also the x axis of the preview chart. */
    val durationMillis: Long = steps.sumOf { it.holdMillis }

    companion object {
        /**
         * A walk through the light a tablet actually meets: a dim room, a lit room, bright daylight
         * indoors, direct sun, then back down through an overcast window to the dim room again.
         *
         * The holds are the shortest ones that let every ramp finish with the default tuning, which
         * is why the run is about fifteen seconds rather than the eight the up steps alone would
         * need. Dimming is deliberately the unhurried half of the loop (a 1.5 s smoothing time
         * constant feeding a 1.5 s fade), so a fall from sunlight to a dark room genuinely takes
         * around seven seconds to land. Cutting the last hold shorter would show a ramp caught in
         * flight and call it settled. The drop from sun to 300 lx is the one leg left unfinished on
         * purpose: the light changing again mid fade is the case the ramper exists to handle well.
         */
        val DEFAULT = Scenario(
            name = "room-walk",
            steps = listOf(
                Step(holdMillis = 700L, lux = 4f),
                Step(holdMillis = 2_100L, lux = 120f),
                Step(holdMillis = 1_600L, lux = 1_200f),
                Step(holdMillis = 1_800L, lux = 4_000f),
                Step(holdMillis = 2_000L, lux = 300f),
                Step(holdMillis = 7_400L, lux = 4f),
            ),
        )
    }
}

/**
 * Plays a tuning against a scenario, so the app can show what the loop would do before it does it.
 *
 * This is not a second model of the ramp. It drives the real [LuxFilter], the real
 * [BrightnessCurve.STOCK] and the real [Ramper], sequenced the way `BrightnessController` sequences
 * them, on a clock it steps itself: filter the reading, retarget only when the filter says the room
 * has actually changed, advance the fade every tick. Anything the preview shows is therefore a
 * property of the engine rather than of the preview, which is the only way a preview is worth
 * trusting while somebody is dragging a slider by it.
 *
 * The light is resubmitted on every tick even while a scenario step holds steady. On the device the
 * sensor is on-change and would go quiet, but the filter is a function of elapsed time either way,
 * and letting it go quiet here would freeze the smoothing halfway through a step.
 */
object Simulation {

    /** Same tolerance the controller uses: a target a rounding error away is the same target. */
    private const val TARGET_EPSILON = 0.0005f

    fun run(
        prefs: Prefs,
        scenario: Scenario = Scenario.DEFAULT,
        stepMillis: Long = 8L,
    ): List<SimSample> {
        val step = stepMillis.coerceAtLeast(1L)
        val duration = scenario.durationMillis
        // Sized up front: the caller is a preview that reruns this whenever a slider moves, and a
        // growing list would spend that time copying instead.
        val trace = ArrayList<SimSample>((duration / step).toInt() + 1)
        if (scenario.steps.isEmpty()) return trace

        val filter = LuxFilter(prefs.tauUpMillis, prefs.tauDownMillis, prefs.deadbandRatio)
        val ramper = Ramper(prefs.rampUpMillis, prefs.rampDownMillis)

        var legIndex = 0
        var legEndMillis = scenario.steps[0].holdMillis
        var primed = false

        /** Filtered lux at the last retarget, which is what the deadband is measured against. */
        var actedLux = Float.NaN

        var now = 0L
        while (now <= duration) {
            while (legIndex < scenario.steps.size - 1 && now >= legEndMillis) {
                legIndex++
                legEndMillis += scenario.steps[legIndex].holdMillis
            }
            val lux = scenario.steps[legIndex].lux
            val filtered = filter.submit(lux, now)
            if (!primed) {
                // The first reading is adopted outright, exactly as the screen coming back on is.
                primed = true
                actedLux = filtered
                ramper.prime(targetFor(filtered, prefs.offset))
            } else if (!filter.settled(actedLux)) {
                actedLux = filtered
                val next = targetFor(filtered, prefs.offset)
                // Past the prime the ramp always holds a real brightness, so the distance to the
                // live target is the whole test the controller applies here.
                if (abs(next - ramper.target) > TARGET_EPSILON) ramper.retarget(next, now)
            }
            val current = ramper.step(now)
            trace.add(SimSample(now, lux, filtered, ramper.target, current))
            now += step
        }
        return trace
    }

    private fun targetFor(lux: Float, offset: Float): Float =
        BrightnessCurve.STOCK.brightnessFor(lux, offset).coerceIn(Brightness.MIN, 1f)
}
