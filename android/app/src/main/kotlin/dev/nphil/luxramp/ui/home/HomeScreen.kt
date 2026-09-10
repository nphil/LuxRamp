package dev.nphil.luxramp.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nphil.luxramp.AppContainer
import dev.nphil.luxramp.R
import dev.nphil.luxramp.control.LABEL_TEMPORARY
import dev.nphil.luxramp.control.Telemetry
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.service.BrightnessService
import dev.nphil.luxramp.ui.LuxIcons
import dev.nphil.luxramp.ui.components.StatusPill
import dev.nphil.luxramp.ui.theme.ramp
import kotlinx.coroutines.launch

/** Scroll distance over which the top bar fades from clear to solid. */
private val BAR_FADE = 40.dp

/**
 * The screen the app lives on: what it is doing, and the four things you can
 * change about it.
 *
 * The body is a [LazyColumn] rather than a scrolling [Column] on purpose. Two
 * of these cards own a Canvas that redraws with the sensor, and lazy layout is
 * what keeps an offscreen chart from measuring and drawing on every frame of a
 * scroll.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    prefs: Prefs,
    telemetry: Telemetry,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shizuku by container.gateway.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Live drag feedback for the curve, held here instead of written through.
    // A drag would otherwise put a DataStore commit and a controller retune
    // behind every frame. It lives in a State object rather than a `by`
    // delegate so only the item that reads it recomposes while a finger moves.
    val curveDrag = remember { mutableStateOf<Float?>(null) }

    // Any change to the stored offset, including the one the controller
    // derives when you drag the system brightness slider, retires the local
    // value. This lives at screen scope, not in the curve item: the item is
    // disposed when it scrolls out of view, and an effect that dies with it
    // would leave the card pinned to a stale drag it can never clear.
    LaunchedEffect(prefs.offset) { curveDrag.value = null }

    // Every commit below is a durable intent, so it runs on the app scope
    // rather than a composition scope: tapping the settings icon straight
    // after a toggle removes this screen, and a write cancelled halfway would
    // leave the switch reading on with the service never started. The lambdas
    // are remembered so a telemetry emission, which arrives about four times a
    // second, re-runs the item lambdas but still lets the cards skip.
    val onToggle: (Boolean) -> Unit = remember(container, context) {
        { checked ->
            container.appScope.launch {
                // Persist first, and await it: the service reads the flag as it
                // starts, and the boot receiver reads it with no UI in the
                // process at all.
                container.prefs.setEnabled(checked)
                if (checked) BrightnessService.start(context) else BrightnessService.stop(context)
            }
        }
    }
    val onUpdate: ((Prefs) -> Prefs) -> Unit = remember(container) {
        { transform -> container.appScope.launch { container.prefs.update(transform) } }
    }
    val onOffsetDrag: (Float) -> Unit = remember(curveDrag) { { value -> curveDrag.value = value } }
    val onOffsetCommit: (Float) -> Unit = remember(container, curveDrag) {
        { value ->
            curveDrag.value = value
            container.appScope.launch { container.prefs.setOffset(value) }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // The scaffold paints nothing and claims no insets except the gesture
        // bar: the themed backdrop underneath is the only background, and every
        // surface pads its own content. That is what edge to edge means here.
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        topBar = {
            HomeTopBar(
                status = loopStatus(prefs.enabled, telemetry),
                listState = listState,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            // Padding rather than a margin, so cards slide under the bar and
            // the bar has something to fade in against.
            contentPadding = PaddingValues(
                start = 16.dp,
                top = inner.calculateTopPadding() + 6.dp,
                end = 16.dp,
                bottom = inner.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "master") {
                MasterCard(
                    enabled = prefs.enabled,
                    screenOn = telemetry.screenOn,
                    instantWriter = telemetry.writerLabel == LABEL_TEMPORARY,
                    shizukuReady = shizuku.ready,
                    onToggle = onToggle,
                    onOpenSettings = onOpenSettings,
                )
            }
            item(key = "live") {
                LiveCard(telemetry = telemetry, prefs = prefs)
            }
            item(key = "tune") {
                TuneCard(prefs = prefs, onUpdate = onUpdate)
            }
            item(key = "curve") {
                CurveCard(
                    offset = curveDrag.value ?: prefs.offset,
                    liveLux = telemetry.filteredLux,
                    onOffsetChange = onOffsetDrag,
                    onOffsetCommit = onOffsetCommit,
                )
            }
        }
    }
}

/** What the pill says, and in which of the meaning colours it says it. */
@Immutable
private data class LoopStatus(val label: String, val color: Color, val icon: ImageVector)

@Composable
private fun loopStatus(enabled: Boolean, telemetry: Telemetry): LoopStatus {
    val ramp = MaterialTheme.ramp
    return when {
        !enabled -> LoopStatus(stringResource(R.string.status_off), ramp.idle, LuxIcons.Power)
        !telemetry.screenOn ->
            LoopStatus(stringResource(R.string.status_screen_off), ramp.idle, LuxIcons.Moon)
        // The privileged writer is the whole point of the app, so the pill
        // distinguishes "running" from "running, but slowly".
        telemetry.writerLabel == LABEL_TEMPORARY ->
            LoopStatus(stringResource(R.string.status_running), ramp.active, LuxIcons.Bolt)
        else ->
            LoopStatus(stringResource(R.string.status_system_writer), ramp.warning, LuxIcons.Warning)
    }
}

/**
 * Brand on the left, live state on the right, settings at the end.
 *
 * The bar is transparent at rest and solid once anything has scrolled under it.
 * Both the surface and the tagline's fade are driven straight from the list's
 * scroll offset inside draw phase lambdas, so a scroll costs a repaint of one
 * row and nothing else: no recomposition, no relayout, no animation objects.
 */
@Composable
private fun HomeTopBar(
    status: LoopStatus,
    listState: LazyListState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val hairline = MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val shown = barProgress(listState)
                if (shown <= 0f) return@drawBehind
                drawRect(surface, alpha = shown * 0.94f)
                drawRect(
                    hairline,
                    topLeft = Offset(0f, size.height - 1f),
                    size = Size(size.width, 1f),
                    alpha = shown * 0.5f,
                )
            }
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
            .padding(start = 16.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.brand_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // The promise recedes as soon as you start using the thing that
                // makes it. Its space is kept, so nothing reflows on scroll.
                modifier = Modifier.graphicsLayer { alpha = 1f - barProgress(listState) },
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(label = status.label, color = status.color, icon = status.icon)
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = LuxIcons.Gear,
                contentDescription = stringResource(R.string.home_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How far the bar has been scrolled into, from 0 at rest to 1 once [BAR_FADE]
 * of content has passed under it.
 *
 * Called only from draw and layer lambdas: reading the list's scroll position
 * there registers a draw phase dependency, which is why this can be sampled
 * every frame without waking the composer. Both of those scopes are a
 * [Density], which is what lets the threshold be stated in dp.
 */
private fun Density.barProgress(state: LazyListState): Float =
    if (state.firstVisibleItemIndex > 0) 1f
    else (state.firstVisibleItemScrollOffset / BAR_FADE.toPx()).coerceIn(0f, 1f)
