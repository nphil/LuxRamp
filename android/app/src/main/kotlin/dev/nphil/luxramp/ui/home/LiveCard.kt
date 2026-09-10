package dev.nphil.luxramp.ui.home

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nphil.luxramp.R
import dev.nphil.luxramp.control.Sample
import dev.nphil.luxramp.control.Telemetry
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.engine.Brightness
import dev.nphil.luxramp.engine.SimSample
import dev.nphil.luxramp.engine.Simulation
import dev.nphil.luxramp.ui.LuxIcons
import dev.nphil.luxramp.ui.components.Explainer
import dev.nphil.luxramp.ui.components.SectionCard
import dev.nphil.luxramp.ui.components.StatusPill
import dev.nphil.luxramp.ui.theme.MonoDisplayStyle
import dev.nphil.luxramp.ui.theme.MonoTextStyle
import dev.nphil.luxramp.ui.theme.Motion
import dev.nphil.luxramp.ui.theme.ramp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** The window the controller keeps, so the chart never has to drop or hold samples of its own. */
private const val WINDOW_MILLIS = 120_000L

/** The light sensor's ceiling on this device; the lux axis is the sensor's whole range. */
private val LUX_LOG_MAX: Float = log10(4097f)

/** 100 % to the user, which is the top of the normal range and not the top of the scale. */
private const val PERCENT_FULL = 100f

/**
 * The percent of full linear brightness, about 113.
 *
 * Both the gauge and the trace are scaled to this rather than to 100, so a fade into the panel's
 * sunlight boost reads as overshoot past the full mark instead of pinning flat against the top.
 */
private val PERCENT_MAX: Float = Brightness.toPercent(1f)

/** Below this the readout would print the same digits, so the gauge is telling us it has arrived. */
private const val RAMP_VISIBLE_PERCENT = 0.5f

private const val PREVIEW_STEP_MILLIS = 8L
private const val PREVIEW_LOOP_GAP_MILLIS = 1_500L

/** Points per curve. Two minutes of samples is more detail than a card's width can resolve. */
private const val MAX_TRACE_POINTS = 240

private val TRACE_HEIGHT = 132.dp
private val GAUGE_HEIGHT = 16.dp

/** The ambient reading, sized to sit beside the hero percentage without competing with it. */
private val LuxReadoutStyle: TextStyle =
    MonoTextStyle.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)

/**
 * What the loop is seeing and doing, right now.
 *
 * The card answers one question at three time scales: the panel's brightness this instant (the hero
 * readout), where the ramp is heading (the gauge), and how the last two minutes went (the trace).
 * Together they separate "the room changed" from "the tuning is wrong", which is the only diagnosis
 * a user can act on.
 *
 * Nothing that moves at panel rate recomposes. The gauge, the trace and the simulated panel all
 * read their values inside a draw lambda, so a frame costs a redraw of one canvas; the readouts are
 * the single exception, and they are handed a value already rounded to the digits they print so
 * they recompose only when those digits change.
 */
@Composable
fun LiveCard(telemetry: Telemetry, prefs: Prefs, modifier: Modifier = Modifier) {
    var previewing by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val ramp = MaterialTheme.ramp

    // Built only while the preview is up, and rebuilt whenever a preference changes, so playback
    // always reflects the sliders as they stand rather than as they were when it started.
    val sim: List<SimSample>? = if (previewing) {
        remember(prefs) { Simulation.run(prefs, stepMillis = PREVIEW_STEP_MILLIS) }
    } else {
        null
    }

    // Both clocks are written from a frame callback and read only in the draw phase.
    val playhead = remember { mutableFloatStateOf(0f) }
    val clockMillis = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val livePanel = Brightness.toPercent(telemetry.currentLinear)
    val liveTarget = Brightness.toPercent(telemetry.targetLinear)
    val liveLux = telemetry.filteredLux
    val panelAnim = remember { Animatable(livePanel) }
    val targetAnim = remember { Animatable(liveTarget) }
    val luxAnim = remember { Animatable(liveLux) }

    // Telemetry lands four times a second. The springs are not there to smooth the measurement (the
    // engine's own filter has already done that) but to carry the readout across the gap between
    // publishes, so a value glides to where the panel went instead of stepping there.
    LaunchedEffect(previewing, livePanel, liveTarget, liveLux) {
        if (previewing) return@LaunchedEffect
        launch { targetAnim.animateTo(liveTarget, Motion.track()) }
        launch { luxAnim.animateTo(liveLux, Motion.track()) }
        panelAnim.animateTo(livePanel, Motion.track())
    }

    // One place decides where every number on the card comes from: the controller, or the playhead.
    val panelPercent: State<Float> = remember(previewing, prefs) {
        derivedStateOf {
            if (sim != null) Brightness.toPercent(sim.at(playhead.floatValue).currentLinear)
            else panelAnim.value
        }
    }
    val targetPercent: State<Float> = remember(previewing, prefs) {
        derivedStateOf {
            if (sim != null) Brightness.toPercent(sim.at(playhead.floatValue).targetLinear)
            else targetAnim.value
        }
    }
    val luxValue: State<Float> = remember(previewing, prefs) {
        derivedStateOf {
            if (sim != null) sim.at(playhead.floatValue).filteredLux else luxAnim.value
        }
    }

    // Quantised to what actually gets printed, so the leaves below recompose on a changed digit and
    // not on a changed float.
    val panelDigits: State<Int> = remember(panelPercent) { derivedStateOf { panelPercent.value.roundToInt() } }
    val targetDigits: State<Int> = remember(targetPercent) { derivedStateOf { targetPercent.value.roundToInt() } }
    val luxDigits: State<Float> = remember(luxValue) { derivedStateOf { quantiseLux(luxValue.value) } }
    val ramping: State<Boolean> = remember(panelPercent, targetPercent) {
        derivedStateOf { abs(targetPercent.value - panelPercent.value) > RAMP_VISIBLE_PERCENT }
    }

    val liveTrace = telemetry.screenOn && telemetry.history.size >= 2

    // The samples arrive at 4 Hz but the window they sit in ends at now, so the x axis has to
    // advance every frame or the trace steps sideways four times a second instead of gliding. The
    // frame time itself is unusable here: samples are stamped on the boot clock, so that is the
    // clock the axis has to be read from. Nothing runs while there is nothing moving to draw.
    LaunchedEffect(previewing, liveTrace) {
        if (previewing || !liveTrace) return@LaunchedEffect
        // Seeded before the first frame callback lands, so the trace never draws one frame against
        // whatever the clock held the last time it was running.
        clockMillis.longValue = SystemClock.elapsedRealtime()
        val advance: (Long) -> Unit = { clockMillis.longValue = SystemClock.elapsedRealtime() }
        while (true) withFrameNanos(advance)
    }

    // Playback is one lookup per frame: the playhead is a time rather than a cursor, so a dropped
    // frame skips ahead instead of falling behind. Editing the tuning mid run restarts it, which is
    // the honest thing to show, since the run in flight was of the old numbers.
    LaunchedEffect(previewing, prefs) {
        val trace = sim ?: return@LaunchedEffect
        val duration = trace[trace.size - 1].timeMillis
        var started = false
        var origin = 0L
        val advance: (Long) -> Unit = { frameNanos ->
            if (!started) {
                started = true
                origin = frameNanos
            }
            val elapsed = (frameNanos - origin) / 1_000_000L
            if (elapsed >= duration + PREVIEW_LOOP_GAP_MILLIS) {
                // Hold the settled end for a beat before starting over, so the loop reads as a run
                // that finished rather than a chart that jumped.
                origin = frameNanos
                playhead.floatValue = 0f
            } else {
                playhead.floatValue = (if (elapsed > duration) duration else elapsed).toFloat()
            }
        }
        while (true) withFrameNanos(advance)
    }

    SectionCard(
        modifier = modifier,
        title = stringResource(R.string.live_title),
        subtitle = stringResource(R.string.live_subtitle),
        icon = LuxIcons.Pulse,
        trailing = { PreviewToggle(previewing = previewing, onToggle = { previewing = it }) },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.live_panel_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                DigitReadout(panelDigits, R.string.live_value_percent, MonoDisplayStyle, scheme.onSurface)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.live_ambient_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                LuxReadout(luxDigits, ramp.lux)
            }
        }

        RampGauge(panelPercent = panelPercent, targetPercent = targetPercent)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DigitReadout(
                value = targetDigits,
                textId = R.string.live_target_caption,
                style = MonoTextStyle,
                color = ramp.target,
                modifier = Modifier.weight(1f),
            )
            StateBadge(previewing = previewing, ramping = ramping)
        }

        if (previewing || liveTrace) {
            TraceCanvas(
                history = telemetry.history,
                sim = sim,
                clockMillis = clockMillis,
                playhead = playhead,
            )
            LegendRow()
        } else {
            Explainer(
                stringResource(
                    if (telemetry.screenOn) R.string.live_waiting else R.string.live_screen_off,
                ),
            )
        }

        if (previewing) {
            Text(
                text = stringResource(R.string.live_preview_panel),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            SimulatedPanel(panelPercent)
            Explainer(stringResource(R.string.live_preview_explain))
        }
    }
}

/**
 * The header control that swaps live telemetry for playback.
 *
 * It carries the target colour while it is on, which is the same colour the preview pill and the
 * ramp marker use, so the whole card reads as "this is a projection" at a glance.
 */
@Composable
private fun PreviewToggle(previewing: Boolean, onToggle: (Boolean) -> Unit) {
    val tint = if (previewing) MaterialTheme.ramp.target else MaterialTheme.colorScheme.primary
    Surface(
        onClick = { onToggle(!previewing) },
        shape = CircleShape,
        color = tint.copy(alpha = if (previewing) 0.20f else 0.10f),
        contentColor = tint,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Icon(
                imageVector = if (previewing) LuxIcons.Stop else LuxIcons.Play,
                contentDescription = stringResource(
                    if (previewing) R.string.live_preview_stop else R.string.live_preview_start,
                ),
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = stringResource(R.string.live_preview),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Where the panel is and where it is going, in one capsule.
 *
 * The bar is drawn in percent rather than in linear brightness so it agrees with the number above
 * it, and the space between the bar and the marker is tinted while they disagree: that tinted gap
 * is the ramp, and watching it open and close is how the ramp durations get tuned.
 */
@Composable
private fun RampGauge(
    panelPercent: State<Float>,
    targetPercent: State<Float>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val ramp = MaterialTheme.ramp
    val density = LocalDensity.current

    // Anchored to the whole track rather than to the filled part, so a colour means one brightness
    // however far the bar has travelled.
    val fill = remember(ramp.brightness) {
        Brush.horizontalGradient(
            listOf(ramp.brightness.copy(alpha = 0.42f), ramp.brightness),
        )
    }
    val track = scheme.surfaceContainerHighest
    val chase = ramp.target.copy(alpha = 0.30f)
    val marker = ramp.target
    val fullTick = scheme.onSurfaceVariant.copy(alpha = 0.55f)
    val markerWidth = remember(density) { with(density) { 3.dp.toPx() } }
    val hairline = remember(density) { with(density) { 1.dp.toPx() } }

    Canvas(modifier.fillMaxWidth().height(GAUGE_HEIGHT)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = radius)

        val current = (panelPercent.value / PERCENT_MAX).coerceIn(0f, 1f) * size.width
        val target = (targetPercent.value / PERCENT_MAX).coerceIn(0f, 1f) * size.width

        // The chase goes down first so the fill and the marker sit on top of it.
        if (abs(target - current) > hairline) {
            clipRect(left = minOf(current, target), right = maxOf(current, target)) {
                drawRoundRect(color = chase, cornerRadius = radius)
            }
        }
        if (current > 0f) {
            // Clipped rather than resized: the gradient is created once against the full track and
            // reused every frame, which a shrinking rect would defeat.
            clipRect(right = current) { drawRoundRect(brush = fill, cornerRadius = radius) }
        }

        val tick = size.width * (PERCENT_FULL / PERCENT_MAX)
        drawLine(
            color = fullTick,
            start = Offset(tick, size.height * 0.22f),
            end = Offset(tick, size.height * 0.78f),
            strokeWidth = hairline,
        )
        drawRoundRect(
            color = marker,
            topLeft = Offset((target - markerWidth / 2f).coerceIn(0f, size.width - markerWidth), 0f),
            size = Size(markerWidth, size.height),
            cornerRadius = CornerRadius(markerWidth / 2f),
        )
    }
}

/**
 * Two minutes of ambient light against the brightness it produced.
 *
 * A step in lux followed by a lagging, rounded brightness line is a ramp that is too slow; a
 * brightness line that twitches while lux is flat is smoothing that is too tight. Everything here
 * is built to survive being redrawn at 120 Hz: three paths allocated once and rewound, two brushes
 * and two strokes hoisted out of the draw scope, and no per sample object at all.
 *
 * In preview the axis is the scenario instead of the clock, so the run draws itself in from the
 * left and the shape can be compared against the light that caused it.
 */
@Composable
private fun TraceCanvas(
    history: List<Sample>,
    sim: List<SimSample>?,
    clockMillis: LongState,
    playhead: FloatState,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val ramp = MaterialTheme.ramp
    val density = LocalDensity.current

    val luxPath = remember { Path() }
    val brightPath = remember { Path() }
    val fillPath = remember { Path() }
    val under = remember(ramp.brightness) {
        Brush.verticalGradient(
            listOf(ramp.brightness.copy(alpha = 0.28f), ramp.brightness.copy(alpha = 0f)),
        )
    }
    val luxStroke = remember(density) {
        Stroke(
            width = with(density) { 1.6.dp.toPx() },
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    }
    val brightStroke = remember(density) {
        Stroke(
            width = with(density) { 2.2.dp.toPx() },
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    }
    val hairline = remember(density) { with(density) { 1.dp.toPx() } }
    val haloRadius = remember(density) { with(density) { 7.dp.toPx() } }
    val dotRadius = remember(density) { with(density) { 3.dp.toPx() } }
    val grid = scheme.onSurfaceVariant.copy(alpha = 0.14f)
    val fullLine = ramp.brightness.copy(alpha = 0.22f)
    val halo = ramp.brightness.copy(alpha = 0.22f)

    Canvas(modifier.fillMaxWidth().height(TRACE_HEIGHT)) {
        val width = size.width
        val height = size.height
        // The newest sample carries a glowing dot, so the plot stops a dot short of the right edge
        // rather than hanging half of it off the canvas.
        val plotWidth = width - haloRadius

        var line = 1
        while (line < 4) {
            val y = height * line / 4f
            drawLine(grid, Offset(0f, y), Offset(width, y), hairline)
            line++
        }
        // Where 100 % sits on the brightness axis. Above it the panel is in its sunlight boost, and
        // a trace that clipped there would hide the most interesting thing it ever does.
        val fullY = height * (1f - PERCENT_FULL / PERCENT_MAX)
        drawLine(fullLine, Offset(0f, fullY), Offset(width, fullY), hairline)

        // The two draw phase reads that make this canvas move without recomposing.
        val count: Int
        val start: Long
        val span: Float
        if (sim != null) {
            count = (playhead.floatValue / PREVIEW_STEP_MILLIS).toInt().coerceIn(0, sim.size - 1) + 1
            start = 0L
            span = sim[sim.size - 1].timeMillis.toFloat()
        } else {
            count = history.size
            start = clockMillis.longValue - WINDOW_MILLIS
            span = WINDOW_MILLIS.toFloat()
        }
        if (count < 2 || span <= 0f) return@Canvas

        // Strided from the newest end so the head of the trace is always the newest sample: it
        // carries the glowing dot, and a dot one sample behind the line would read as a glitch.
        val stride = ((if (sim != null) sim.size else count) / MAX_TRACE_POINTS).coerceAtLeast(1)

        luxPath.rewind()
        brightPath.rewind()
        fillPath.rewind()

        var plotted = 0
        var firstX = 0f
        var prevX = 0f
        var prevLuxY = 0f
        var prevBrightY = 0f
        var index = (count - 1) % stride
        while (index < count) {
            val timeMillis: Long
            val lux: Float
            val linear: Float
            if (sim != null) {
                val sample = sim[index]
                timeMillis = sample.timeMillis
                lux = sample.filteredLux
                linear = sample.currentLinear
            } else {
                val sample = history[index]
                timeMillis = sample.timeMillis
                lux = sample.lux
                linear = sample.linear
            }
            index += stride
            if (timeMillis < start) continue
            if (lux.isNaN() || linear.isNaN()) continue

            val x = (timeMillis - start).toFloat() / span * plotWidth
            val luxY = height * (1f - luxFraction(lux))
            val brightY = height * (1f - percentFraction(linear))
            if (plotted == 0) {
                firstX = x
                luxPath.moveTo(x, luxY)
                brightPath.moveTo(x, brightY)
                fillPath.moveTo(x, brightY)
            } else {
                // Midpoint quadratics: the control point is the sample and the curve passes through
                // the midpoints, which rounds the 4 Hz staircase without inventing overshoot the
                // way a spline through every point would.
                val midX = (prevX + x) / 2f
                luxPath.quadraticTo(prevX, prevLuxY, midX, (prevLuxY + luxY) / 2f)
                brightPath.quadraticTo(prevX, prevBrightY, midX, (prevBrightY + brightY) / 2f)
                fillPath.quadraticTo(prevX, prevBrightY, midX, (prevBrightY + brightY) / 2f)
            }
            prevX = x
            prevLuxY = luxY
            prevBrightY = brightY
            plotted++
        }
        if (plotted < 2) return@Canvas

        luxPath.lineTo(prevX, prevLuxY)
        brightPath.lineTo(prevX, prevBrightY)
        fillPath.lineTo(prevX, prevBrightY)
        fillPath.lineTo(prevX, height)
        fillPath.lineTo(firstX, height)
        fillPath.close()

        drawPath(fillPath, under)
        drawPath(luxPath, ramp.lux, alpha = 0.9f, style = luxStroke)
        drawPath(brightPath, ramp.brightness, style = brightStroke)
        // The head of the trace is the only part still moving, so it is lit to read as the live end
        // of the line rather than as where the data happens to stop.
        drawCircle(halo, radius = haloRadius, center = Offset(prevX, prevBrightY))
        drawCircle(ramp.brightness, radius = dotRadius, center = Offset(prevX, prevBrightY))
    }
}

/**
 * A swatch that gets lighter as the simulated panel does.
 *
 * The numbers say what the ramp does; this says what it feels like, which is the thing somebody is
 * actually trying to judge when they drag a ramp duration around. The fraction is the percent
 * scale, already the perceptual one the ramp interpolates in, so the swatch fades as evenly as the
 * panel would.
 */
@Composable
private fun SimulatedPanel(panelPercent: State<Float>, modifier: Modifier = Modifier) {
    // Neutral, not the theme's brightness hue: the swatch is standing in for a lit panel, and
    // tinting it would mix "how bright" with "what colour" in the one place the answer has to be
    // read off by eye.
    val lit = Color(0xFFF6F4EF)
    val unlit = MaterialTheme.colorScheme.surfaceContainerLowest
    val edge = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, edge, MaterialTheme.shapes.small)
            .drawBehind {
                drawRect(lerp(unlit, lit, (panelPercent.value / PERCENT_MAX).coerceIn(0f, 1f)))
            },
    )
}

/** Preview beats ramp state: while playback is up, what the card is showing matters more. */
@Composable
private fun StateBadge(previewing: Boolean, ramping: State<Boolean>) {
    val ramp = MaterialTheme.ramp
    if (previewing) {
        // Deliberately not "Preview" with a play glyph: the header already carries the button, and
        // a second one worded the same way reads as a control rather than as a state.
        StatusPill(
            label = stringResource(R.string.live_state_simulated),
            color = ramp.target,
            icon = LuxIcons.Pulse,
        )
        return
    }
    val moving = ramping.value
    StatusPill(
        label = stringResource(
            if (moving) R.string.live_state_ramping else R.string.live_state_settled,
        ),
        color = if (moving) ramp.target else ramp.active,
        icon = if (moving) LuxIcons.Bolt else LuxIcons.Check,
    )
}

@Composable
private fun LegendRow(modifier: Modifier = Modifier) {
    val ramp = MaterialTheme.ramp
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(ramp.lux, stringResource(R.string.live_legend_lux))
        LegendItem(ramp.brightness, stringResource(R.string.live_legend_brightness))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The one composable that reads a moving number.
 *
 * The value arrives already rounded to what gets printed, so this is the only thing that recomposes
 * while a ramp runs, and only when the text would genuinely differ.
 */
@Composable
private fun DigitReadout(
    value: State<Int>,
    textId: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(textId, value.value),
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun LuxReadout(value: State<Float>, color: Color, modifier: Modifier = Modifier) {
    val lux = value.value
    // Under ten lux the integer part stops carrying the information: a candle-lit room and a dark
    // one are 4 lx and 0.4 lx, and the curve treats those as different rooms.
    val text = if (lux < 10f) {
        stringResource(R.string.live_value_lux_fine, lux)
    } else {
        stringResource(R.string.live_value_lux, lux.roundToInt())
    }
    Text(text = text, style = LuxReadoutStyle, color = color, maxLines = 1, modifier = modifier)
}

/** Rounded to the digits the readout prints, so an unchanged reading costs no recomposition. */
private fun quantiseLux(lux: Float): Float =
    if (lux < 10f) (lux * 10f).roundToInt() / 10f else lux.roundToInt().toFloat()

/** Lux is plotted in log10(lux + 1) so the 0 to 100 lx an indoor room lives in is not a smear. */
private fun luxFraction(lux: Float): Float =
    (log10(lux.coerceAtLeast(0f) + 1f) / LUX_LOG_MAX).coerceIn(0f, 1f)

private fun percentFraction(linear: Float): Float =
    (Brightness.toPercent(linear) / PERCENT_MAX).coerceIn(0f, 1f)

/**
 * The sample a playback time lands on.
 *
 * The simulation steps a fixed interval, so this is an index rather than a search: one multiply and
 * a clamp, which is what makes driving the whole card from a frame callback affordable.
 */
private fun List<SimSample>.at(timeMillis: Float): SimSample =
    this[(timeMillis / PREVIEW_STEP_MILLIS).toInt().coerceIn(0, size - 1)]
