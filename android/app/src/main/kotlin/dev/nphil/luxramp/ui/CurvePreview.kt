package dev.nphil.luxramp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.nphil.luxramp.engine.Brightness
import dev.nphil.luxramp.engine.BrightnessCurve
import kotlin.math.pow

/** Enough points that the curve's kinks are visible and few enough to redraw on every slider frame. */
private const val CURVE_STEPS = 96

/**
 * Top of the y axis. The stock curve runs past the normal maximum into the sunlight boost, so the
 * axis has to as well or the interesting end of the curve would be a flat line against the ceiling.
 */
private val PERCENT_AXIS_TOP: Float = maxOf(100f, Brightness.toPercent(1f))

/** Decade gridlines, the numbers a room actually sits between. */
private val LUX_GUIDES = floatArrayOf(10f, 100f, 1000f)

/**
 * The lux-to-brightness curve the controller is aiming at, redrawn as the offset slider moves.
 *
 * This is the offset's only honest feedback: the number itself is a gamma exponent and means
 * nothing, but the shape shows immediately that positive lifts the dim end hardest.
 */
@Composable
internal fun CurvePreview(offset: Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val path = remember { Path() }
    val density = LocalDensity.current
    val dashes = remember(density) {
        val dash = with(density) { 4.dp.toPx() }
        PathEffect.dashPathEffect(floatArrayOf(dash, dash))
    }

    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val hairline = 1.dp.toPx()
        val grid = scheme.onSurfaceVariant.copy(alpha = 0.16f)
        for (lux in LUX_GUIDES) {
            val x = logLuxFraction(lux) * size.width
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), hairline)
        }

        val normalMax = size.height * (1f - 100f / PERCENT_AXIS_TOP)
        drawLine(
            color = scheme.tertiary.copy(alpha = 0.8f),
            start = Offset(0f, normalMax),
            end = Offset(size.width, normalMax),
            strokeWidth = hairline,
            pathEffect = dashes,
        )

        path.rewind()
        var step = 0
        while (step < CURVE_STEPS) {
            val fraction = step / (CURVE_STEPS - 1f)
            val lux = 10f.pow(fraction * LOG_LUX_MAX) - 1f
            val percent = Brightness.toPercent(BrightnessCurve.STOCK.brightnessFor(lux, offset))
            val x = fraction * size.width
            val y = size.height * (1f - (percent / PERCENT_AXIS_TOP).coerceIn(0f, 1f))
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            step++
        }
        drawPath(path, scheme.primary, style = Stroke(width = 2.dp.toPx()))
    }
}
