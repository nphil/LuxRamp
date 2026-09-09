package dev.nphil.luxramp.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The display's brightness scales, and the conversions between them.
 *
 * Three numbers describe the same backlight and none of them are interchangeable:
 *
 *  - the **linear** float the platform actually applies, `0f..1f`, where [NORMAL_MAX] is the top of
 *    the normal range and anything above it is high-brightness (sunlight) mode;
 *  - the **percent** the OS shows the user, which is not the linear value scaled by 100 but the
 *    AOSP hybrid-log-gamma curve (`BrightnessUtils`) over `min = 0f, max = NORMAL_MAX`. Ramping in
 *    this space is what makes a fade look even to the eye;
 *  - the **setting** int stored in `Settings.System.SCREEN_BRIGHTNESS`, `0..`[SETTING_MAX].
 *
 * Ground truth from `dumpsys display` on the target device (Xiaomi Pad 8 Pro): 0.10131326 shows as
 * 70 %, 0.001709819 as 10 %, 0.18362173 as 81 % (setting 94), 0.03750879 as 47 %.
 */
object Brightness {
    /** Lowest brightness the display accepts; below this the panel is off, not dim. */
    const val MIN = 0.001709819f

    /** Top of the normal range: exactly 100 % to the user. Above it is HBM. */
    const val NORMAL_MAX = 0.49975574f

    /** `Settings.System.SCREEN_BRIGHTNESS` is 0..512 on this device, not the usual 0..255. */
    const val SETTING_MAX = 512

    // Hybrid log gamma constants, verbatim from AOSP com.android.settingslib.display.BrightnessUtils.
    private const val R = 0.5f
    private const val A = 0.17883277f
    private const val B = 0.28466892f
    private const val C = 0.55991073f

    /** HLG normalises to 0..12 rather than 0..1. */
    private const val HLG_RANGE = 12f

    /**
     * The percent the OS would show for [linear]. Deliberately not clamped at the top: a linear
     * value in high-brightness mode is genuinely more than 100 % of the normal range, and the UI
     * should say so rather than pretend the slider is pinned.
     */
    fun toPercent(linear: Float): Float {
        val normalized = (linear.coerceAtLeast(0f) / NORMAL_MAX) * HLG_RANGE
        val gamma = if (normalized <= 1f) sqrt(normalized) * R else A * ln(normalized - B) + C
        return gamma * 100f
    }

    /**
     * Inverse of [toPercent], extended past 100 % so a ramp into high-brightness mode is one
     * continuous fade rather than a hold at [NORMAL_MAX] followed by a jump. Upper bound is the
     * percent of linear 1f; a negative percent gives 0f rather than the positive brightness
     * squaring it would produce.
     */
    fun fromPercent(percent: Float): Float {
        val gamma = (percent / 100f).coerceAtLeast(0f)
        val normalized = if (gamma <= R) {
            val scaled = gamma / R
            scaled * scaled
        } else {
            exp((gamma - C) / A) + B
        }
        return (normalized / HLG_RANGE * NORMAL_MAX).coerceIn(0f, 1f)
    }

    /** The int to write to `Settings.System.SCREEN_BRIGHTNESS` for [linear]. */
    fun toSetting(linear: Float): Int {
        if (linear.isNaN()) return 0
        return (linear * SETTING_MAX).roundToInt().coerceIn(0, SETTING_MAX)
    }

    /** The linear brightness a stored setting int stands for. */
    fun fromSetting(value: Int): Float = value.coerceIn(0, SETTING_MAX) / SETTING_MAX.toFloat()
}
