package dev.nphil.luxramp.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nphil.luxramp.R
import dev.nphil.luxramp.engine.Brightness
import dev.nphil.luxramp.engine.BrightnessCurve
import dev.nphil.luxramp.ui.LuxIcons
import dev.nphil.luxramp.ui.components.Explainer
import dev.nphil.luxramp.ui.components.SectionCard
import dev.nphil.luxramp.ui.components.StatusPill
import dev.nphil.luxramp.ui.theme.Motion
import dev.nphil.luxramp.ui.theme.MonoTextStyle
import dev.nphil.luxramp.ui.theme.ramp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Enough points that the table's kinks survive, few enough to rebuild while a finger is moving. */
private const val CURVE_POINTS = 96

/** The bright end of the axis: past this a phone is outdoors and the curve has already topped out. */
private const val LUX_MAX = 4096f

/** Lux is plotted in log10(lux + 1) so the 0 to 100 lx an indoor room lives in is not a smear. */
private val LOG_LUX_MAX: Float = log10(LUX_MAX + 1f)

/**
 * Top of the y axis. The curve runs past the normal maximum into the sunlight boost, so the axis
 * has to as well, otherwise the interesting end would be a flat line pinned to the ceiling.
 */
private val PERCENT_AXIS_TOP: Float = max(100f, Brightness.toPercent(1f))

/** Decade gridlines: the numbers a real room actually sits between. */
private val LUX_DECADES = floatArrayOf(10f, 100f, 1000f)

private val ChartHeight = 196.dp

private fun luxAt(fraction: Float): Float = 10f.pow(fraction.coerceIn(0f, 1f) * LOG_LUX_MAX) - 1f

private fun luxFraction(lux: Float): Float =
    (log10(lux.coerceAtLeast(0f) + 1f) / LOG_LUX_MAX).coerceIn(0f, 1f)

/**
 * Both curves in pixel space, plus the band between them, built once per (offset, size).
 *
 * Sampling the table 96 times and closing three paths is far too much work for a draw pass that
 * runs on every scrub frame, so all of it happens in composition and the draw scope only replays
 * the result.
 */
private class CurveGeometry(val width: Float, val height: Float, offset: Float) {
    val stockY = FloatArray(CURVE_POINTS)
    val modifiedY = FloatArray(CURVE_POINTS)
    val stockPath = Path()
    val modifiedPath = Path()

    /** Stock forward, modified back: the closed area is exactly what the offset changed. */
    val deltaPath = Path()

    init {
        var i = 0
        while (i < CURVE_POINTS) {
            val fraction = i / (CURVE_POINTS - 1f)
            val lux = luxAt(fraction)
            val x = fraction * width
            val sy = yFor(BrightnessCurve.STOCK.brightnessFor(lux, 0f))
            val my = yFor(BrightnessCurve.STOCK.brightnessFor(lux, offset))
            stockY[i] = sy
            modifiedY[i] = my
            if (i == 0) {
                stockPath.moveTo(x, sy)
                modifiedPath.moveTo(x, my)
                deltaPath.moveTo(x, sy)
            } else {
                stockPath.lineTo(x, sy)
                modifiedPath.lineTo(x, my)
                deltaPath.lineTo(x, sy)
            }
            i++
        }
        i = CURVE_POINTS - 1
        while (i >= 0) {
            deltaPath.lineTo(i / (CURVE_POINTS - 1f) * width, modifiedY[i])
            i--
        }
        deltaPath.close()
    }

    private fun yFor(linear: Float): Float {
        val percent = Brightness.toPercent(linear)
        return height * (1f - (percent / PERCENT_AXIS_TOP).coerceIn(0f, 1f))
    }

    /** Sampled y at an arbitrary x fraction, interpolated so a dot never snaps between samples. */
    fun yAt(ys: FloatArray, fraction: Float): Float {
        val position = fraction.coerceIn(0f, 1f) * (CURVE_POINTS - 1)
        val index = position.toInt().coerceIn(0, CURVE_POINTS - 2)
        return ys[index] + (ys[index + 1] - ys[index]) * (position - index)
    }
}

/** Axis text, measured once so the draw pass never lays out a glyph. */
private class ChartLabels(
    val decades: List<TextLayoutResult>,
    val luxMin: TextLayoutResult,
    val luxMax: TextLayoutResult,
    val normalTop: TextLayoutResult,
)

/**
 * The offset's only honest feedback.
 *
 * The number itself is a gamma exponent and means nothing to anybody, but the gap between the two
 * curves says immediately that brightening lifts a dim room hardest. The chart is grabbable in both
 * directions: sideways reads a light level, up and down retunes, which is why the card exists at
 * all rather than a second slider.
 */
@Composable
fun CurveCard(
    offset: Float,
    liveLux: Float,
    onOffsetChange: (Float) -> Unit,
    onOffsetCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rampColors = MaterialTheme.ramp
    val offsetText = remember(offset) { String.format(Locale.getDefault(), "%+.2f", offset) }

    SectionCard(
        modifier = modifier,
        title = stringResource(R.string.curve_title),
        subtitle = stringResource(R.string.curve_subtitle),
        icon = LuxIcons.Pulse,
        trailing = {
            // Nothing to reset at zero, and an always-on button would invite an accidental tap.
            if (abs(offset) > 0.001f) {
                TextButton(
                    onClick = { onOffsetCommit(0f) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.curve_reset))
                }
            }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(
                label = stringResource(R.string.curve_legend_stock),
                color = rampColors.stock,
                filled = false,
            )
            StatusPill(
                label = stringResource(R.string.curve_legend_modified),
                color = rampColors.modified,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.curve_offset, offsetText),
                style = MonoTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CurveChart(
            offset = offset,
            liveLux = liveLux,
            onOffsetChange = onOffsetChange,
            onOffsetCommit = onOffsetCommit,
        )

        Explainer(stringResource(R.string.curve_explain_offset))
        Explainer(stringResource(R.string.curve_explain_drag))
    }
}

@Composable
private fun CurveChart(
    offset: Float,
    liveLux: Float,
    onOffsetChange: (Float) -> Unit,
    onOffsetCommit: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val rampColors = MaterialTheme.ramp

    val stockColor = rampColors.stock
    val modifiedColor = rampColors.modified
    val glowColor = remember(modifiedColor) { modifiedColor.copy(alpha = 0.12f) }
    val gridColor = remember(scheme.onSurfaceVariant) {
        scheme.onSurfaceVariant.copy(alpha = 0.14f)
    }
    val ruleColor = remember(scheme.onSurfaceVariant) {
        scheme.onSurfaceVariant.copy(alpha = 0.28f)
    }
    val labelColor = remember(scheme.onSurfaceVariant) {
        scheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val dropColor = remember(modifiedColor) { modifiedColor.copy(alpha = 0.35f) }
    val crosshairColor = remember(scheme.onSurface) { scheme.onSurface.copy(alpha = 0.45f) }
    val dotCoreColor = scheme.surfaceContainer

    // Strokes, dashes and gradients are objects: built here, never inside a draw pass.
    val stockStroke = remember(density) {
        with(density) {
            val dash = 5.dp.toPx()
            Stroke(
                width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
            )
        }
    }
    val modifiedStroke = remember(density) {
        with(density) { Stroke(width = 2.5.dp.toPx()) }
    }
    val glowStroke = remember(density) {
        with(density) { Stroke(width = 8.dp.toPx()) }
    }
    val ruleDash = remember(density) {
        with(density) {
            val dash = 3.dp.toPx()
            PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.5f))
        }
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val decade10 = stringResource(R.string.curve_axis_lux_10)
    val decade100 = stringResource(R.string.curve_axis_lux_100)
    val decade1k = stringResource(R.string.curve_axis_lux_1k)
    val luxMinText = stringResource(R.string.curve_axis_lux_min)
    val luxMaxText = stringResource(R.string.curve_axis_lux_max)
    val normalTopText = stringResource(R.string.curve_axis_normal_top)
    val labels = remember(
        measurer, labelStyle, decade10, decade100, decade1k, luxMinText, luxMaxText, normalTopText,
    ) {
        ChartLabels(
            decades = listOf(
                measurer.measure(decade10, labelStyle),
                measurer.measure(decade100, labelStyle),
                measurer.measure(decade1k, labelStyle),
            ),
            luxMin = measurer.measure(luxMinText, labelStyle),
            luxMax = measurer.measure(luxMaxText, labelStyle),
            normalTop = measurer.measure(normalTopText, labelStyle),
        )
    }

    // The scrub position lives outside composition: the finger moves at 120 Hz and only the draw
    // pass and the readout need to hear about it.
    val scrubX = remember { mutableFloatStateOf(0f) }
    val scrubbing = remember { mutableStateOf(false) }

    val liveVisible = liveLux.isFinite() && liveLux > 0f
    val liveAnimation = animateFloatAsState(
        targetValue = if (liveVisible) luxFraction(liveLux) else 0f,
        animationSpec = Motion.track(),
        label = "curve-live-lux",
    )

    val chartDescription = stringResource(R.string.curve_chart_description)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChartHeight)
            .clipToBounds()
            .semantics { contentDescription = chartDescription },
    ) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val geometry = remember(offset, widthPx, heightPx) {
            CurveGeometry(widthPx, heightPx, offset)
        }
        val deltaBrush = remember(modifiedColor, heightPx) {
            // The gradient runs to zero below the axis, not at it. The band is widest at the dim
            // end, which sits low in the chart, and fading out by the bottom edge would erase
            // exactly the part that shows what the offset is for.
            Brush.verticalGradient(
                colors = listOf(modifiedColor.copy(alpha = 0.20f), modifiedColor.copy(alpha = 0f)),
                startY = 0f,
                endY = heightPx * 2f,
            )
        }

        // Read through updated state so the gesture loop, keyed on Unit, never sees a stale offset.
        val currentOffset = rememberUpdatedState(offset)
        val currentChange = rememberUpdatedState(onOffsetChange)
        val currentCommit = rememberUpdatedState(onOffsetCommit)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        scrubX.floatValue = down.position.x
                        scrubbing.value = true
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                        // The dominant axis is decided once, at slop, and then held for the whole
                        // gesture: a scrub that drifts upwards must not quietly retune the curve.
                        var vertical = false
                        val past = awaitTouchSlopOrCancellation(down.id) { change, over ->
                            vertical = abs(over.y) > abs(over.x)
                            if (vertical) scrubbing.value = false
                            change.consume()
                        }
                        if (past == null) {
                            scrubbing.value = false
                            return@awaitEachGesture
                        }

                        var working = currentOffset.value
                        var lastY = past.position.y
                        val height = size.height.toFloat()
                        val width = size.width.toFloat()
                        drag(past.id) { change ->
                            if (vertical) {
                                // Full chart height covers the whole range, and up brightens.
                                val delta = (change.position.y - lastY) / height * 2f
                                lastY = change.position.y
                                working = (working - delta).coerceIn(-1f, 1f)
                                currentChange.value(working)
                            } else {
                                scrubX.floatValue = change.position.x.coerceIn(0f, width)
                            }
                            change.consume()
                        }
                        if (vertical) currentCommit.value(working)
                        scrubbing.value = false
                    }
                },
        ) {
            if (geometry.width <= 0f || geometry.height <= 0f) return@Canvas
            val hairline = 1.dp.toPx()

            // The band is the explanation, so it goes down first and the curves read on top of it.
            drawPath(geometry.deltaPath, deltaBrush)

            var i = 0
            while (i < LUX_DECADES.size) {
                val x = luxFraction(LUX_DECADES[i]) * geometry.width
                drawLine(gridColor, Offset(x, 0f), Offset(x, geometry.height), hairline)
                i++
            }

            val normalY = geometry.height * (1f - 100f / PERCENT_AXIS_TOP)
            drawLine(
                color = ruleColor,
                start = Offset(0f, normalY),
                end = Offset(geometry.width, normalY),
                strokeWidth = hairline,
                pathEffect = ruleDash,
            )

            val labelPad = 3.dp.toPx()
            val baseline = geometry.height - labels.luxMin.size.height - labelPad
            drawText(labels.luxMin, labelColor, Offset(labelPad, baseline))
            val maxLabelX = geometry.width - labels.luxMax.size.width - labelPad
            drawText(labels.luxMax, labelColor, Offset(maxLabelX, baseline))
            i = 0
            while (i < LUX_DECADES.size) {
                val label = labels.decades[i]
                val x = luxFraction(LUX_DECADES[i]) * geometry.width + labelPad
                // Drop a decade rather than let it collide with the end label.
                if (x + label.size.width < maxLabelX - labelPad) {
                    drawText(label, labelColor, Offset(x, baseline))
                }
                i++
            }
            drawText(
                labels.normalTop,
                labelColor,
                Offset(geometry.width - labels.normalTop.size.width - labelPad, normalY + labelPad),
            )

            drawPath(geometry.stockPath, stockColor, style = stockStroke)
            drawPath(geometry.modifiedPath, glowColor, style = glowStroke)
            drawPath(geometry.modifiedPath, modifiedColor, style = modifiedStroke)

            if (liveVisible) {
                val fraction = liveAnimation.value.coerceIn(0f, 1f)
                val x = fraction * geometry.width
                val y = geometry.yAt(geometry.modifiedY, fraction)
                drawLine(dropColor, Offset(x, y), Offset(x, geometry.height), hairline)
                drawCircle(modifiedColor, 4.dp.toPx(), Offset(x, y))
                drawCircle(dotCoreColor, 1.75.dp.toPx(), Offset(x, y))
            }

            if (scrubbing.value) {
                val x = scrubX.floatValue.coerceIn(0f, geometry.width)
                val fraction = if (geometry.width > 0f) x / geometry.width else 0f
                drawLine(crosshairColor, Offset(x, 0f), Offset(x, geometry.height), hairline)
                drawCircle(stockColor, 3.dp.toPx(), Offset(x, geometry.yAt(geometry.stockY, fraction)))
                drawCircle(
                    modifiedColor,
                    3.5.dp.toPx(),
                    Offset(x, geometry.yAt(geometry.modifiedY, fraction)),
                )
            }
        }

        if (scrubbing.value) {
            ScrubReadout(
                scrubX = scrubX,
                widthPx = widthPx,
                offset = offset,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/**
 * The floating figures that follow the finger.
 *
 * This is the one part of the gesture that genuinely has to recompose, because the numbers are
 * text. Keeping it in its own function keeps the invalidation to a single small subtree while the
 * chart itself stays in the draw phase.
 */
@Composable
private fun ScrubReadout(
    scrubX: MutableFloatState,
    widthPx: Float,
    offset: Float,
    modifier: Modifier = Modifier,
) {
    val fraction = if (widthPx > 0f) (scrubX.floatValue / widthPx).coerceIn(0f, 1f) else 0f
    val lux = luxAt(fraction)
    val stock = Brightness.toPercent(BrightnessCurve.STOCK.brightnessFor(lux, 0f)).roundToInt()
    val modified = Brightness.toPercent(BrightnessCurve.STOCK.brightnessFor(lux, offset)).roundToInt()
    val delta = modified - stock
    val luxText = stringResource(R.string.curve_readout_lux, lux.roundToInt().toString())
    val valuesText = stringResource(
        R.string.curve_readout_values,
        stock.toString(),
        modified.toString(),
        if (delta > 0) "+$delta" else delta.toString(),
    )
    // Two short lines rather than one long one: a single row would wrap on a phone and the panel
    // has to stay narrow enough to sit beside the finger.
    val lineStyle = remember { MonoTextStyle.copy(fontSize = 11.sp) }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .padding(top = 4.dp)
            // Clamped in the layout pass, where the readout's own width is finally known, so the
            // panel stays inside the card at either end of the axis.
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val limit = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
                val x = (scrubX.floatValue - placeable.width / 2f).roundToInt().coerceIn(0, limit)
                layout(constraints.maxWidth, placeable.height) { placeable.place(x, 0) }
            },
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(text = luxText, style = lineStyle, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = valuesText,
                style = lineStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
