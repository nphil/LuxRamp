package dev.nphil.luxramp.ui.mini

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nphil.luxramp.R
import dev.nphil.luxramp.control.LABEL_SETTINGS
import dev.nphil.luxramp.control.LABEL_TEMPORARY
import dev.nphil.luxramp.ui.LuxIcons
import dev.nphil.luxramp.ui.theme.LuxRampTheme
import dev.nphil.luxramp.ui.theme.Motion
import dev.nphil.luxramp.ui.theme.MonoTextStyle
import dev.nphil.luxramp.ui.theme.ramp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Everything the floating panel draws, flattened by the host so this file owns no sources. */
@Immutable
data class MiniUiState(
    val brightnessPercent: Float,
    val offset: Float,
    val autoEnabled: Boolean,
    val collapsed: Boolean,
    val writerLabel: String,
    val screenOn: Boolean,
    /** Alpha to settle at once the panel has been left alone. 1f or more disables the fade. */
    val idleAlpha: Float,
    val fadeDelayMillis: Long,
)

/**
 * Everything the panel can ask for.
 *
 * The drag callbacks report raw incremental pixel deltas because the thing being moved is a
 * WindowManager window, not a composable: the host adds them straight onto its layout params
 * and persists the result when [onHandleDragEnd] arrives.
 */
@Immutable
data class MiniActions(
    val onBrightnessDrag: (Float) -> Unit,
    val onBrightnessCommit: (Float) -> Unit,
    val onOffsetDrag: (Float) -> Unit,
    val onOffsetCommit: (Float) -> Unit,
    val onToggleAuto: (Boolean) -> Unit,
    val onCollapse: (Boolean) -> Unit,
    val onOpenApp: () -> Unit,
    val onClose: () -> Unit,
    val onHandleDrag: (dx: Float, dy: Float) -> Unit,
    val onHandleDragEnd: () -> Unit,
)

/**
 * Motion for the collapse, spelled as tweens rather than through [Motion.medium].
 *
 * `animateContentSize` and `Crossfade` both demand a `FiniteAnimationSpec`, which the generic
 * `Motion` helpers cannot promise, so the vocabulary is borrowed by constant instead. Hoisted to
 * the file so a recomposition mid-collapse does not hand the animation a fresh spec.
 */
private val CollapseSizeSpec: FiniteAnimationSpec<IntSize> =
    tween(Motion.MEDIUM_MILLIS, easing = Motion.Emphasised)
private val CollapseFadeSpec: FiniteAnimationSpec<Float> =
    tween(Motion.MEDIUM_MILLIS, easing = Motion.Standard)

private const val EXPANDED_WIDTH_DP = 236
private const val COLLAPSED_WIDTH_DP = 96
private const val COLLAPSED_HEIGHT_DP = 52

/** Touch targets are below the 48dp guideline on purpose: this window sits on top of somebody else's app. */
private const val TAP_TARGET_DP = 26

/**
 * The remote control: a floating window over whatever else is on screen.
 *
 * It carries the three things worth reaching for without leaving the current app (brightness,
 * automatic control, ramp offset) and nothing else. Explanations, charts and settings live in the
 * main app, because every row added here is a row of somebody else's app it covers up.
 */
@Composable
fun MiniPanel(state: MiniUiState, actions: MiniActions, modifier: Modifier = Modifier) {
    val panelAlpha = remember { Animatable(1f) }
    val fadeEnabled = state.idleAlpha < 1f

    Surface(
        modifier = modifier
            // Read in the draw phase, so the whole idle fade runs without a single recomposition.
            .graphicsLayer { alpha = panelAlpha.value }
            .idleFade(panelAlpha, fadeEnabled, state.idleAlpha, state.fadeDelayMillis)
            // Alignment is passed even though it is the default: the one-argument form is
            // ambiguous between two overloads. Top start is also the honest anchor here, since
            // the host positions this window by its top left corner.
            .animateContentSize(CollapseSizeSpec, Alignment.TopStart),
        shape = MaterialTheme.shapes.large,
        // Slightly translucent so it admits it is floating, plus a hairline so it still has an
        // edge against a busy app behind it. Tonal colour alone is not enough over live content.
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Crossfade(
            targetState = state.collapsed,
            animationSpec = CollapseFadeSpec,
            label = "mini-mode",
        ) { collapsed ->
            if (collapsed) {
                CollapsedPill(state = state, actions = actions)
            } else {
                ExpandedBody(state = state, actions = actions)
            }
        }
    }
}

/**
 * Wakes the panel on touch and puts it back to sleep after [delayMillis] of being left alone.
 *
 * The wake has to happen on [PointerEventPass.Initial] because the touch that matters most is the
 * one starting a slider drag, and by the main pass the slider has consumed it. Pressing only snaps
 * to full opacity; the countdown restarts when the last finger lifts, so a long drag never fades
 * under the hand.
 */
private fun Modifier.idleFade(
    alpha: Animatable<Float, AnimationVector1D>,
    enabled: Boolean,
    idleAlpha: Float,
    delayMillis: Long,
): Modifier = this.pointerInput(enabled, idleAlpha, delayMillis) {
    if (!enabled) {
        alpha.snapTo(1f)
        return@pointerInput
    }
    coroutineScope {
        // The panel appears without being touched, so the first countdown starts itself.
        var countdown: Job? = launch {
            delay(delayMillis)
            alpha.animateTo(idleAlpha, Motion.medium())
        }
        var held = false
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.any { it.pressed }
                if (pressed == held) continue
                held = pressed
                countdown?.cancel()
                countdown = launch {
                    if (held) {
                        alpha.snapTo(1f)
                    } else {
                        delay(delayMillis)
                        alpha.animateTo(idleAlpha, Motion.medium())
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedBody(state: MiniUiState, actions: MiniActions) {
    val brightness = remember { MiniSliderState(state.brightnessPercent) }
    val offset = remember { MiniSliderState(state.offset) }
    // Follow the panel and the stored offset, but never while a finger owns the value: seeding
    // mid-drag is how a slider ends up fighting the thumb it is under.
    SideEffect {
        if (!brightness.dragging) brightness.value.floatValue = state.brightnessPercent
        if (!offset.dragging) offset.value.floatValue = state.offset
    }

    val enabled = state.screenOn
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier.width(EXPANDED_WIDTH_DP.dp).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MiniHeader(state = state, actions = actions)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = LuxIcons.SunDim,
                contentDescription = null,
                tint = dim,
                modifier = Modifier.size(13.dp),
            )
            MiniSlider(
                tracker = brightness,
                start = 0f,
                end = 100f,
                accent = MaterialTheme.ramp.brightness,
                enabled = enabled,
                description = stringResource(R.string.mini_brightness_slider),
                onDrag = actions.onBrightnessDrag,
                onCommit = actions.onBrightnessCommit,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = LuxIcons.Sun,
                contentDescription = null,
                tint = dim,
                modifier = Modifier.size(16.dp),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.mini_offset_caption),
                    style = MaterialTheme.typography.labelSmall,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                OffsetReadout(tracker = offset, color = MaterialTheme.ramp.modified)
            }
            MiniSlider(
                tracker = offset,
                start = -1f,
                end = 1f,
                accent = MaterialTheme.ramp.modified,
                enabled = enabled,
                description = stringResource(R.string.mini_offset_slider),
                onDrag = actions.onOffsetDrag,
                onCommit = actions.onOffsetCommit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MiniHeader(state: MiniUiState, actions: MiniActions) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DragHandle(actions = actions, tint = dim)

        Row(
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.extraSmall)
                // The readout is the way back into the app, so the tap needs a label of its own:
                // "62%" tells a screen reader nothing about what tapping it does.
                .clickable(
                    onClickLabel = stringResource(R.string.mini_open_app),
                    onClick = actions.onOpenApp,
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WriterDot(writerLabel = state.writerLabel)
            if (state.screenOn) {
                Text(
                    text = stringResource(
                        R.string.mini_percent,
                        state.brightnessPercent.roundToInt(),
                    ),
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            } else {
                // With the panel off there is no percentage worth reporting, so the header spends
                // its width on the reason the controls are dead instead.
                Text(
                    text = stringResource(R.string.mini_screen_off),
                    style = MaterialTheme.typography.labelSmall,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        AutoButton(on = state.autoEnabled, onToggle = actions.onToggleAuto)
        // ChevronDown rather than the Collapse glyph: two chevrons folding in make
        // an hourglass, and next to the close button that reads as a second X.
        MiniIconButton(
            icon = LuxIcons.ChevronDown,
            description = stringResource(R.string.mini_collapse),
            tint = dim,
            onClick = { actions.onCollapse(true) },
        )
        MiniIconButton(
            icon = LuxIcons.Close,
            description = stringResource(R.string.mini_close),
            tint = dim,
            onClick = actions.onClose,
        )
    }
}

/**
 * The only part of the panel that moves the window.
 *
 * Keeping the gesture on this leaf is what lets the sliders keep their own drags: a drag modifier
 * on the surface would win the slop race against everything inside it.
 */
@Composable
private fun DragHandle(actions: MiniActions, tint: Color) {
    // Held by reference so the gesture is never restarted just because the host rebuilt its lambdas.
    val current by rememberUpdatedState(actions)
    Icon(
        imageVector = LuxIcons.Grip,
        contentDescription = stringResource(R.string.mini_handle),
        tint = tint,
        modifier = Modifier
            .size(TAP_TARGET_DP.dp)
            .padding(4.dp)
            .pointerInput(Unit) {
                // Spelled out rather than delegated to detectDragGestures: the deltas go to a
                // WindowManager, so the drag should start on the first movement instead of after
                // touch slop, and nothing inside this icon is competing for the pointer.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            change.consume()
                            moved = true
                            current.onHandleDrag(delta.x, delta.y)
                        }
                    }
                    // Only a real move is worth persisting, a stray tap on the grip is not.
                    if (moved) current.onHandleDragEnd()
                }
            },
    )
}

/**
 * Automatic control, as a square rather than a Material `Switch`.
 *
 * A switch is 52dp of track for one bit of state, which is a fifth of this window's width.
 */
@Composable
private fun AutoButton(on: Boolean, onToggle: (Boolean) -> Unit) {
    val target = if (on) MaterialTheme.ramp.active else MaterialTheme.ramp.idle
    val tint by animateColorAsState(target, Motion.medium(), label = "mini-auto")
    Surface(
        onClick = { onToggle(!on) },
        modifier = Modifier.size(TAP_TARGET_DP.dp),
        shape = MaterialTheme.shapes.small,
        color = tint.copy(alpha = if (on) 0.22f else 0.10f),
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = LuxIcons.Power,
                contentDescription = stringResource(
                    if (on) R.string.mini_auto_on else R.string.mini_auto_off,
                ),
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

/** Icon button sized for this window rather than for a thumb on a phone home screen. */
@Composable
private fun MiniIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(TAP_TARGET_DP.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/**
 * Which writer is in use, as one 6dp dot.
 *
 * The label itself is a sentence, and a sentence does not fit here, but hiding the difference
 * would be a lie: the fallback writer ramps on the platform's schedule, not on ours.
 */
@Composable
private fun WriterDot(writerLabel: String) {
    val privileged = writerLabel == LABEL_TEMPORARY
    val target = if (privileged) MaterialTheme.ramp.active else MaterialTheme.ramp.warning
    val color by animateColorAsState(target, Motion.medium(), label = "mini-writer")
    val description = stringResource(
        if (privileged) R.string.mini_writer_privileged else R.string.mini_writer_fallback,
    )
    Box(
        Modifier
            .size(6.dp)
            .semantics { contentDescription = description }
            .background(color, CircleShape),
    )
}

/** The parked form: brightness and a way back, nothing that can be nudged by accident. */
@Composable
private fun CollapsedPill(state: MiniUiState, actions: MiniActions) {
    val current by rememberUpdatedState(actions)
    Row(
        modifier = Modifier
            .width(COLLAPSED_WIDTH_DP.dp)
            .height(COLLAPSED_HEIGHT_DP.dp)
            .clickable { actions.onCollapse(false) }
            // Collapsed there is no grip, and a pill that cannot be moved is a pill stuck
            // wherever it was parked, so the whole surface drags. Nothing is consumed until the
            // touch passes slop, which is what leaves the tap above intact.
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var travelled = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        travelled += delta.getDistance()
                        if (travelled > slop) dragging = true
                        if (dragging && delta != Offset.Zero) {
                            change.consume()
                            current.onHandleDrag(delta.x, delta.y)
                        }
                    }
                    if (dragging) current.onHandleDragEnd()
                }
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (state.screenOn) {
                stringResource(R.string.mini_percent, state.brightnessPercent.roundToInt())
            } else {
                stringResource(R.string.mini_screen_off)
            },
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = LuxIcons.Expand,
            contentDescription = stringResource(R.string.mini_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * The value a compact slider is showing, deliberately outside composition.
 *
 * A drag produces a value every frame. Held in a float state that the track reads in the draw
 * phase, that costs one redraw of one 24dp box; held in a normal state read by the layout, it
 * would recompose the whole panel at the rate of the finger.
 */
@Stable
private class MiniSliderState(initial: Float) {
    val value = mutableFloatStateOf(initial)

    /** True while a finger owns the value. Never read from composition, so it needs no state. */
    var dragging = false
}

/**
 * A slider drawn rather than assembled.
 *
 * Material's `Slider` is a 44dp row once its touch target is honoured, and three of those do not
 * fit in a window this size. A track plus a thumb in one `drawBehind` fits in 24dp, and reading
 * the value in the draw phase is what keeps a drag off the recomposition path.
 */
@Composable
private fun MiniSlider(
    tracker: MiniSliderState,
    start: Float,
    end: Float,
    accent: Color,
    enabled: Boolean,
    description: String,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = end - start
    val activeColor = if (enabled) accent else MaterialTheme.colorScheme.outline
    val inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val density = LocalDensity.current
    val thumbRadius = with(density) { 7.dp.toPx() }
    val trackWidth = with(density) { 4.dp.toPx() }
    val dragHandler by rememberUpdatedState(onDrag)
    val commitHandler by rememberUpdatedState(onCommit)

    Box(
        modifier
            .height(24.dp)
            .semantics { contentDescription = description }
            .drawBehind {
                // Everything this scope needs is captured, not built: no Path, Brush or list here.
                val fraction = ((tracker.value.floatValue - start) / span).coerceIn(0f, 1f)
                val left = thumbRadius
                val right = size.width - thumbRadius
                val centreY = size.height / 2f
                val thumbX = left + (right - left) * fraction
                drawLine(
                    color = inactiveColor,
                    start = Offset(left, centreY),
                    end = Offset(right, centreY),
                    strokeWidth = trackWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = activeColor,
                    start = Offset(left, centreY),
                    end = Offset(thumbX, centreY),
                    strokeWidth = trackWidth,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = activeColor,
                    radius = thumbRadius,
                    center = Offset(thumbX, centreY),
                )
            }
            .pointerInput(enabled, start, end) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    tracker.dragging = true
                    // A tap anywhere on the track is a move to that value: the thumb is 14dp wide
                    // and hunting for it with a fingertip is not usable at this size.
                    reportValue(down.position.x, thumbRadius, start, span, tracker, dragHandler)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            change.consume()
                            reportValue(
                                change.position.x,
                                thumbRadius,
                                start,
                                span,
                                tracker,
                                dragHandler,
                            )
                        }
                    }
                    tracker.dragging = false
                    commitHandler(tracker.value.floatValue)
                }
            },
    )
}

/** Turns a touch x into a value, clamped to the usable track the thumb can actually reach. */
private fun PointerInputScope.reportValue(
    x: Float,
    inset: Float,
    start: Float,
    span: Float,
    tracker: MiniSliderState,
    onDrag: (Float) -> Unit,
) {
    val usable = (size.width - inset * 2f).coerceAtLeast(1f)
    val fraction = ((x - inset) / usable).coerceIn(0f, 1f)
    val value = start + fraction * span
    tracker.value.floatValue = value
    onDrag(value)
}

/**
 * The offset number, in its own composable on purpose.
 *
 * Reading the dragged value here rather than in the row above means a drag recomposes one `Text`
 * instead of the whole panel.
 */
@Composable
private fun OffsetReadout(tracker: MiniSliderState, color: Color) {
    Text(
        text = stringResource(R.string.mini_offset_value, tracker.value.floatValue),
        style = MonoTextStyle,
        color = color,
        maxLines = 1,
    )
}

@Preview(name = "Mini expanded", showBackground = true)
@Composable
private fun MiniPanelExpandedPreview() {
    LuxRampTheme(dynamicColor = false) {
        MiniPanel(
            state = MiniUiState(
                brightnessPercent = 62f,
                offset = 0.145f,
                autoEnabled = true,
                collapsed = false,
                writerLabel = LABEL_TEMPORARY,
                screenOn = true,
                idleAlpha = 1f,
                fadeDelayMillis = 3_000,
            ),
            actions = PreviewActions,
        )
    }
}

@Preview(name = "Mini collapsed", showBackground = true)
@Composable
private fun MiniPanelCollapsedPreview() {
    LuxRampTheme(dynamicColor = false) {
        MiniPanel(
            state = MiniUiState(
                brightnessPercent = 38f,
                offset = -0.2f,
                autoEnabled = false,
                collapsed = true,
                writerLabel = LABEL_SETTINGS,
                screenOn = true,
                idleAlpha = 1f,
                fadeDelayMillis = 3_000,
            ),
            actions = PreviewActions,
        )
    }
}

private val PreviewActions = MiniActions(
    onBrightnessDrag = {},
    onBrightnessCommit = {},
    onOffsetDrag = {},
    onOffsetCommit = {},
    onToggleAuto = {},
    onCollapse = {},
    onOpenApp = {},
    onClose = {},
    onHandleDrag = { _, _ -> },
    onHandleDragEnd = {},
)
