package dev.nphil.luxramp.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln1p
import kotlin.math.max

/**
 * Smooths the light sensor into something worth reacting to.
 *
 * An on-change light sensor delivers a spray of readings whenever a hand passes over the panel, so
 * the raw value cannot drive the backlight directly. This is an exponential moving average with two
 * properties chosen for that job:
 *
 *  - it averages in the log domain (`ln(lux + 1)`), because a jump from 10 to 20 lux matters as much
 *    to the eye as one from 1000 to 2000, and a linear average would let one sunlit sample dominate
 *    a minute of indoor readings;
 *  - it is asymmetric. [tauUpMillis] is normally much shorter than [tauDownMillis]: walking into
 *    sunlight has to be followed quickly, while a shadow crossing the sensor must not dim the
 *    screen. Tau is the time constant, so after `tau` the filter has closed 63 % of the gap.
 *
 * Time comes in from the caller (`SystemClock.elapsedRealtime()` in the app) so the filter stays
 * pure and testable; a clock that fails to advance simply leaves the value alone.
 */
class LuxFilter(
    var tauUpMillis: Long,
    var tauDownMillis: Long,
    var deadbandRatio: Float = 0.10f,
) {

    /** The filtered reading in lux, `NaN` until the first sample. */
    var value: Float = Float.NaN
        private set

    /** The same value in the domain the averaging happens in, kept so it survives round-tripping. */
    private var logValue: Float = 0f

    private var lastMillis: Long = 0L

    /** Forget the history; the next sample snaps, as it does when the screen comes back on. */
    fun reset() {
        value = Float.NaN
        logValue = 0f
        lastMillis = 0L
    }

    /**
     * Feed one sensor reading and get the filtered value back.
     *
     * The first sample after construction or [reset] is adopted outright: there is nothing to
     * average against, and pretending otherwise would fade the screen up from black.
     */
    fun submit(lux: Float, nowMillis: Long): Float {
        val sample = lux.coerceAtLeast(0f)
        if (value.isNaN()) return snapTo(sample, nowMillis)

        val dt = (nowMillis - lastMillis).coerceAtLeast(0L)
        lastMillis = nowMillis
        if (dt == 0L) return value

        val tau = if (sample > value) tauUpMillis else tauDownMillis
        val alpha = if (tau <= 0L) 1f else 1f - exp(-dt.toFloat() / tau.toFloat())
        if (alpha >= 1f) return snapTo(sample, nowMillis)

        logValue += alpha * (ln1p(sample) - logValue)
        value = expm1(logValue).coerceAtLeast(0f)
        return value
    }

    /**
     * Whether [target] is close enough to the filtered value to be treated as the same reading.
     *
     * The comparison is relative, not absolute: 1 lux of drift is noise at 300 lux and a different
     * room at 3 lux. Callers use this to leave the backlight alone rather than chase the sensor.
     */
    fun settled(target: Float): Boolean {
        val current = value
        if (current.isNaN()) return false
        val scale = max(abs(current), abs(target))
        if (scale <= 0f) return true
        return abs(current - target) <= deadbandRatio * scale
    }

    private fun snapTo(sample: Float, nowMillis: Long): Float {
        logValue = ln1p(sample)
        lastMillis = nowMillis
        value = sample
        return sample
    }
}
