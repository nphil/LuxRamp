package dev.nphil.luxramp.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colours that carry meaning, kept outside the Material scheme.
 *
 * Material You recolours the whole app from the wallpaper, and every palette in
 * [AppPalettes] recolours it again. But "this line is the light sensor", "this
 * line is what the panel is doing", "this is the stock curve you are replacing"
 * and "this permission is still missing" have to mean the same thing on every
 * theme, or the charts stop being readable the moment someone changes their
 * wallpaper. So they vary by light/dark only.
 */
@Immutable
data class RampColors(
    /** Ambient light, everywhere it is plotted. */
    val lux: Color,
    /** What the panel is actually showing. */
    val brightness: Color,
    /** Where the ramp is heading, drawn as a marker rather than a line. */
    val target: Color,
    /** HyperOS' own curve, the thing LuxRamp exists to replace. */
    val stock: Color,
    /** The curve with the user's offset applied. */
    val modified: Color,
    /** Running and writing. */
    val active: Color,
    /** Alive but with nothing to do: screen off, or control switched off. */
    val idle: Color,
    /** Degraded: running on the slow settings writer, or a grant is missing. */
    val warning: Color,
) {
    companion object {
        val Dark = RampColors(
            lux = Color(0xFFFFC46B),
            brightness = Color(0xFF7FD8FF),
            target = Color(0xFFC4A6FF),
            stock = Color(0xFF8E8A97),
            modified = Color(0xFF7FD8FF),
            active = Color(0xFF6FE0A2),
            idle = Color(0xFFAFAAB8),
            warning = Color(0xFFFFA062),
        )

        val Light = RampColors(
            lux = Color(0xFFB06A00),
            brightness = Color(0xFF0F6C99),
            target = Color(0xFF6D28D9),
            stock = Color(0xFF6E6A76),
            modified = Color(0xFF0F6C99),
            active = Color(0xFF116B3C),
            idle = Color(0xFF5F5F6B),
            warning = Color(0xFFB4530C),
        )
    }
}
