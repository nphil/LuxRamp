package dev.nphil.luxramp.engine

/**
 * Moves the backlight from where it is to where it should be over a fixed time.
 *
 * The interpolation runs in percent (gamma) space rather than on the linear float, because equal
 * steps of linear brightness are not equal steps to the eye: a linear fade crawls at the dim end
 * and then lunges at the bright end. Percent space is the scale the OS itself shows, so a ramp
 * across it looks like a steady fade.
 *
 * Duration is fixed per direction — [upMillis] short so a room lighting up is followed promptly,
 * [downMillis] long so dimming is something you notice only afterwards — rather than proportional
 * to the distance, which would make small corrections feel instant and large ones sluggish.
 *
 * The percent scale continues past 100 into high-brightness mode ([Brightness.fromPercent] is not
 * clamped at [Brightness.NORMAL_MAX]), so a fade into direct sunlight is one continuous motion.
 *
 * The caller owns the clock (`SystemClock.elapsedRealtime()` in the app) and calls [step] on its own
 * cadence; nothing here starts a timer.
 */
class Ramper(
    var upMillis: Long,
    var downMillis: Long,
) {

    /** Where the backlight is now, as a linear brightness. `NaN` until [prime] or [retarget]. */
    var current: Float = Float.NaN
        private set

    /** Where it is heading. Equal to [current] when no ramp is running. */
    var target: Float = Float.NaN
        private set

    /** Whether [step] still has work to do. */
    var isRamping: Boolean = false
        private set

    private var startPercent: Float = 0f
    private var targetPercent: Float = 0f
    private var startMillis: Long = 0L
    private var durationMillis: Long = 0L

    /**
     * Adopt [linear] with no ramp — the screen is already there, or has just come back on and any
     * fade would be a visible flash of the wrong brightness.
     */
    fun prime(linear: Float) {
        current = linear
        target = linear
        isRamping = false
        durationMillis = 0L
    }

    /**
     * Aim at [linear] from wherever the ramp currently is.
     *
     * Asking for the target already in flight is a no-op, so a sensor that keeps confirming the
     * same reading cannot restart the fade and stall it short of its destination. Asking for a
     * different one mid-ramp restarts the clock from the present brightness, which is what makes
     * the light coming back on halfway through a dim feel like one movement instead of two.
     */
    fun retarget(linear: Float, nowMillis: Long) {
        if (current.isNaN()) {
            prime(linear)
            return
        }
        if (linear == target) return

        startPercent = Brightness.toPercent(current)
        targetPercent = Brightness.toPercent(linear)
        target = linear
        startMillis = nowMillis
        durationMillis = if (linear > current) upMillis else downMillis
        if (durationMillis <= 0L) {
            current = linear
            isRamping = false
        } else {
            isRamping = true
        }
    }

    /**
     * Advance the ramp to [nowMillis] and return the brightness to write.
     *
     * A step at the instant of the retarget, or after a clock that went backwards, returns the
     * present value untouched rather than the round trip of it: no time has passed, so nothing
     * should move, not even by a rounding error.
     */
    fun step(nowMillis: Long): Float {
        if (!isRamping) return current
        val elapsed = (nowMillis - startMillis).coerceIn(0L, durationMillis)
        if (elapsed == 0L) return current
        if (elapsed >= durationMillis) {
            // Land on the target exactly: a fade that stops an ulp short never settles.
            current = target
            isRamping = false
            return current
        }
        val t = elapsed.toFloat() / durationMillis.toFloat()
        current = Brightness.fromPercent(startPercent + (targetPercent - startPercent) * t)
        return current
    }
}
