package dev.nphil.luxramp.data

/** Which colour scheme to resolve, independent of what the system is doing. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Palette used when Material You is switched off. Matches `AppPalettes.first()`. */
const val DEFAULT_THEME_ID = "nebula"

/**
 * Everything the brightness loop is tuned by, everything the app looks like, and
 * the handful of bits that have to survive a reboot.
 *
 * Tuning defaults are the values measured on the target device: [offset] is the
 * user's own `screen_auto_brightness_adj`, and the ramp and smoothing times are
 * what a HyperOS ramp should have been (fast up, unhurried down) rather than
 * what it is.
 */
data class Prefs(
    val enabled: Boolean = false,
    val rampUpMillis: Long = 400,
    val rampDownMillis: Long = 1500,
    val tauUpMillis: Long = 300,
    val tauDownMillis: Long = 1500,
    val offset: Float = 0.145f,
    val deadbandRatio: Float = 0.10f,

    /** False until the first-run flow has been seen; it is the app's launch decision. */
    val onboarded: Boolean = false,

    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val themeId: String = DEFAULT_THEME_ID,

    /** Show the floating mini window. Independent of [enabled]: the window can turn control off. */
    val miniEnabled: Boolean = false,
    /** Fade the floating window down once it has been left alone. */
    val miniFadeEnabled: Boolean = true,
    val miniFadeDelayMillis: Long = 3_000,
    /** Opacity the floating window settles at while idle. */
    val miniIdleAlpha: Float = 0.35f,
    /** Floating window position in pixels, or -1 for "place it in the default corner". */
    val miniX: Int = -1,
    val miniY: Int = -1,
    /** Floating window collapsed to its pill. */
    val miniCollapsed: Boolean = false,
)
