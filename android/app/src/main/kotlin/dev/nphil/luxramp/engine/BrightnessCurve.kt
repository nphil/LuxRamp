package dev.nphil.luxramp.engine

import kotlin.math.log10
import kotlin.math.pow

/**
 * A lux -> linear-brightness table, interpolated the way the platform does it.
 *
 * Two details matter and both come from how the eye works rather than from the numbers:
 *
 *  - interpolation happens in `log10(lux + 1)`, so the dense low end of the table (0, 1, 2, 4, 6
 *    lux) is not squashed against the axis and a candle-lit room still resolves into steps. The
 *    `+ 1` keeps 0 lux on the axis instead of at minus infinity;
 *  - the user's brightness preference is applied as the gamma Android uses for
 *    `screen_auto_brightness_adj`, `b' = b ^ (3 ^ -offset)`, not as an additive nudge. Because the
 *    exponent shrinks below 1 for a positive offset, brightening lifts the dim end of the curve
 *    much more than the bright end — which is exactly what "too dark indoors" means.
 *
 * The table's values are on the full `0f..1f` scale where `1f` is HBM; [Brightness.NORMAL_MAX]
 * (100 % to the user) is reached at roughly 1500 lux.
 */
class BrightnessCurve(lux: FloatArray, brightness: FloatArray) {

    /** Table lux, pre-converted to the interpolation domain. */
    private val xs: FloatArray

    /** Table brightness, on the 0..1 linear scale. */
    private val ys: FloatArray

    init {
        require(lux.isNotEmpty()) { "a curve needs at least one point" }
        require(lux.size == brightness.size) {
            "lux and brightness must be the same length, got ${lux.size} and ${brightness.size}"
        }
        for (i in 1 until lux.size) {
            require(lux[i] > lux[i - 1]) { "lux must ascend, got ${lux[i - 1]} then ${lux[i]} at $i" }
        }
        xs = FloatArray(lux.size) { domain(lux[it]) }
        ys = brightness.copyOf()
    }

    /**
     * Linear brightness for an ambient reading, with the user's [offset] gamma applied and the
     * result clamped to a value the display will accept.
     *
     * Off the ends of the table the nearest end value holds: darker than the first point is still
     * the floor brightness, brighter than the last is still full brightness. Passing `0f` as the
     * offset returns the table value itself, which is how a caller recovers the un-shaped curve.
     */
    fun brightnessFor(lux: Float, offset: Float): Float {
        val raw = tableAt(domain(lux))
        val shaped = raw.coerceIn(0f, 1f).pow(3f.pow(-offset))
        return shaped.coerceIn(Brightness.MIN, 1f)
    }

    private fun tableAt(x: Float): Float {
        val last = xs.size - 1
        if (x <= xs[0]) return ys[0]
        if (x >= xs[last]) return ys[last]
        // xs[low] <= x < xs[high] holds throughout, so the loop ends on the containing segment.
        var low = 0
        var high = last
        while (low + 1 < high) {
            val mid = (low + high) ushr 1
            if (xs[mid] <= x) low = mid else high = mid
        }
        val span = xs[low + 1] - xs[low]
        if (span <= 0f) return ys[low + 1]
        val t = (x - xs[low]) / span
        return ys[low] + (ys[low + 1] - ys[low]) * t
    }

    private fun domain(lux: Float): Float = log10(lux.coerceAtLeast(0f) + 1f)

    companion object {
        /**
         * The stock HyperOS auto-brightness table read out of `dumpsys display` on the target
         * device, used as the default curve so LuxRamp starts out behaving like the OS did.
         */
        val STOCK: BrightnessCurve = BrightnessCurve(
            lux = floatArrayOf(
                0f, 1f, 2f, 4f, 6f, 8f, 10f, 15f, 20f, 25f,
                30f, 35f, 40f, 45f, 50f, 55f, 60f, 65f, 70f, 75f,
                80f, 85f, 90f, 95f, 100f, 120f, 140f, 160f, 180f, 200f,
                220f, 240f, 260f, 280f, 300f, 320f, 340f, 360f, 380f, 400f,
                420f, 440f, 460f, 480f, 500f, 700f, 900f, 1100f, 1300f, 1500f,
                1700f, 1900f, 2000f, 2500f, 3000f, 3500f, 4000f, 4500f, 5000f, 5500f,
                6000f,
            ),
            brightness = floatArrayOf(
                0.008549097f, 0.012945774f, 0.015632633f, 0.039570104f, 0.048363462f,
                0.059110895f, 0.06765999f, 0.09159747f, 0.11773327f, 0.15168537f,
                0.16170007f, 0.16365413f, 0.16365413f, 0.16365413f, 0.16560821f,
                0.16560821f, 0.16560821f, 0.16756229f, 0.16756229f, 0.16976061f,
                0.16976061f, 0.16976061f, 0.16976061f, 0.17171472f, 0.17171472f,
                0.17366879f, 0.17757696f, 0.17977528f, 0.18172936f, 0.1856375f,
                0.1875916f, 0.191744f, 0.19369812f, 0.19760624f, 0.1998046f,
                0.20175865f, 0.2076209f, 0.20957497f, 0.2117733f, 0.2156815f,
                0.21763556f, 0.221788f, 0.22374207f, 0.22765021f, 0.23180263f,
                0.27381533f, 0.3136297f, 0.35979486f, 0.40962386f, 0.45163655f,
                0.51367855f, 0.55984366f, 0.5898876f, 0.7379091f, 0.79995114f,
                0.8331705f, 0.8666341f, 0.89985347f, 0.93331707f, 0.96653634f,
                1.0f,
            ),
        )
    }
}
