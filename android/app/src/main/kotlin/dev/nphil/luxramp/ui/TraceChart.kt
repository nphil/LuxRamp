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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.nphil.luxramp.control.Sample
import dev.nphil.luxramp.engine.Brightness
import kotlin.math.log10

/** The light sensor's ceiling on this device; the lux axis is the sensor's whole range. */
internal const val LUX_MAX = 4096f

/** Lux is plotted in log10(lux + 1) so that the 0-100 lx an indoor room lives in is not a smear. */
internal val LOG_LUX_MAX: Float = log10(LUX_MAX + 1f)

/** The window the controller keeps, so the chart never has to drop or hold samples of its own. */
private const val WINDOW_MILLIS = 120_000L

internal fun logLuxFraction(lux: Float): Float =
    (log10(lux.coerceAtLeast(0f) + 1f) / LOG_LUX_MAX).coerceIn(0f, 1f)

/**
 * The last two minutes: measured lux against the brightness the ramp actually put on the panel.
 *
 * The pair is the whole point of the chart — a step in lux followed by a lagging, rounded
 * brightness line is a ramp that is too slow, and a brightness line that twitches while lux is
 * flat is smoothing that is too tight.
 *
 * Both [Path]s are allocated once and rewound per frame, and nothing per-sample is allocated: the
 * controller replaces this list several times a second.
 */
@Composable
internal fun TraceChart(history: List<Sample>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val luxPath = remember { Path() }
    val brightnessPath = remember { Path() }

    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val hairline = 1.dp.toPx()
        val grid = scheme.onSurfaceVariant.copy(alpha = 0.16f)
        var line = 1
        while (line < 4) {
            val y = size.height * line / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), hairline)
            line++
        }
        if (history.size < 2) return@Canvas

        val end = history[history.size - 1].timeMillis
        val start = end - WINDOW_MILLIS
        val span = WINDOW_MILLIS.toFloat()

        luxPath.rewind()
        brightnessPath.rewind()
        var plotted = 0
        for (index in history.indices) {
            val sample = history[index]
            if (sample.timeMillis < start) continue
            if (sample.lux.isNaN() || sample.linear.isNaN()) continue
            val x = (sample.timeMillis - start) / span * size.width
            val luxY = size.height * (1f - logLuxFraction(sample.lux))
            val brightnessY = size.height * (1f - normalPercentFraction(sample.linear))
            if (plotted == 0) {
                luxPath.moveTo(x, luxY)
                brightnessPath.moveTo(x, brightnessY)
            } else {
                luxPath.lineTo(x, luxY)
                brightnessPath.lineTo(x, brightnessY)
            }
            plotted++
        }
        if (plotted < 2) return@Canvas

        drawPath(luxPath, scheme.tertiary, alpha = 0.85f, style = Stroke(width = 1.5.dp.toPx()))
        drawPath(brightnessPath, scheme.primary, style = Stroke(width = 2.dp.toPx()))
    }
}

/** Brightness as a fraction of the normal (non-sunlight) range, which is what the trace plots. */
private fun normalPercentFraction(linear: Float): Float =
    (Brightness.toPercent(linear) / 100f).coerceIn(0f, 1f)
