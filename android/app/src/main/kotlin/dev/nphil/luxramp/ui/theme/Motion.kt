package dev.nphil.luxramp.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * One vocabulary of motion for the whole app.
 *
 * Everything visible here is either following a physical quantity (the panel's
 * brightness, the room's light) or acknowledging a touch, so the specs come in
 * two families: springs for anything that tracks a value, tweens for anything
 * that appears, disappears or changes colour. Nothing bounces: an overshoot on
 * a brightness readout would be a lie about what the panel did.
 */
object Motion {

    /** Emphasised M3 easing, for entrances and expansions. */
    val Emphasised: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Standard M3 easing, for colour and alpha. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val FAST_MILLIS = 140
    const val MEDIUM_MILLIS = 260
    const val SLOW_MILLIS = 420

    /** Value tracking: critically damped so a number never overshoots the value it reports. */
    fun <T> track(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Snappier tracking for things a finger is holding, where lag reads as lag. */
    fun <T> follow(): AnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    fun <T> fast(): AnimationSpec<T> = tween(FAST_MILLIS, easing = Standard)

    fun <T> medium(): AnimationSpec<T> = tween(MEDIUM_MILLIS, easing = Emphasised)

    fun <T> slow(): AnimationSpec<T> = tween(SLOW_MILLIS, easing = Emphasised)
}
