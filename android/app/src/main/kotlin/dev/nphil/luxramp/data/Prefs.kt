package dev.nphil.luxramp.data

/**
 * Everything the brightness loop is tuned by, and the one bit of state that survives a reboot.
 *
 * Defaults are the values measured on the target device: [offset] is the user's own
 * `screen_auto_brightness_adj`, the ramp and smoothing times are what a HyperOS ramp should have
 * been (fast up, unhurried down) rather than what it is.
 */
data class Prefs(
    val enabled: Boolean = false,
    val rampUpMillis: Long = 400,
    val rampDownMillis: Long = 1500,
    val tauUpMillis: Long = 300,
    val tauDownMillis: Long = 1500,
    val offset: Float = 0.145f,
    val deadbandRatio: Float = 0.10f,
)
