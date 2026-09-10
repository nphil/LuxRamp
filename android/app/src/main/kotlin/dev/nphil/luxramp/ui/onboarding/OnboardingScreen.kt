package dev.nphil.luxramp.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nphil.luxramp.AppContainer
import dev.nphil.luxramp.R
import dev.nphil.luxramp.ui.components.Explainer
import dev.nphil.luxramp.ui.components.SectionCard
import dev.nphil.luxramp.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Rows arrive one after another rather than all at once, so the eye is led down the list. */
private const val STAGGER_MILLIS = 45L
private const val RISE_DP = 8f

/**
 * Long enough for the return transition to be over before the next screen is thrown on top of it.
 * Shorter and the launch lands mid-animation, which the window manager sometimes simply drops.
 */
private const val SETTLE_MILLIS = 450L

/**
 * First run: everything the app needs, asked for on one screen.
 *
 * The previous version dripped permission rows into the main screen and left them there forever,
 * which meant the app never looked finished and the user never knew when they were done. This
 * screen exists once, states the whole cost up front, and then gets out of the way.
 */
@Composable
fun OnboardingScreen(
    container: AppContainer,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shizuku by container.gateway.state.collectAsStateWithLifecycle()

    // Four of the five grants have no callback of any kind, so they are re-read on a tick that
    // every path bumps: a resume, a permission result, or Shizuku's own state changing.
    var probe by remember { mutableIntStateOf(0) }
    val grants = remember(shizuku, probe) { readGrants(context, shizuku) }
    val setupRun = remember { SetupRun() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { probe++ }
    val askNotifications = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }

    LifecycleResumeEffect(Unit) {
        // Resume is the only honest moment to look. The walk reads the gateway directly instead of
        // the collected state so its decision is made on the same values the rows are about to
        // show, not on whatever the last composition happened to hold.
        container.gateway.refresh()
        probe++
        val fresh = readGrants(context, container.gateway.state.value)
        setupRun.onReturned { !it.isGranted(fresh) }
        onPauseOrDispose { }
    }

    // The ask fires from an effect keyed on the cursor, never from the resume callback. That is
    // what makes "never ask twice" structural: the cursor is the only thing that can start a step,
    // and it only ever counts up.
    LaunchedEffect(setupRun.active, setupRun.cursor) {
        if (!setupRun.active) return@LaunchedEffect
        val item = setupRun.current ?: return@LaunchedEffect
        delay(SETTLE_MILLIS)
        item.request(
            context = context,
            grants = readGrants(context, container.gateway.state.value),
            gateway = container.gateway,
            requestNotifications = askNotifications,
        )
    }

    val finish = {
        setupRun.stop()
        // The app scope, not the composition's: this screen is about to be replaced, and a
        // cancelled write would put the user through first run again on the next launch.
        container.appScope.launch { container.prefs.setOnboarded(true) }
        onFinished()
    }

    val pending = grants.pending()
    val requiredOk = grants.pendingRequired() == 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrandHeader(Modifier.entrance(rememberEntrance(0)))

        SectionCard(
            title = stringResource(R.string.setup_permissions_title),
            subtitle = stringResource(R.string.setup_permissions_subtitle),
            spacing = 18,
        ) {
            // The rows only fade and slide, so the card is its final height from the first frame
            // and nothing below it reflows while the entrance plays.
            SetupItem.entries.forEachIndexed { index, item ->
                PermissionRow(
                    item = item,
                    grants = grants,
                    onGrant = {
                        // A tap takes over: two things driving the same queue would race on resume.
                        setupRun.stop()
                        item.request(context, grants, container.gateway, askNotifications)
                    },
                    modifier = Modifier.entrance(rememberEntrance(index + 1)),
                )
            }
        }

        FooterCard(
            setupRun = setupRun,
            pending = pending,
            requiredOk = requiredOk,
            onStart = {
                // Required first. Shizuku may well end the walk early, because the honest answer
                // for somebody without it is a web page, and a user who bails out there must still
                // come away with a working app.
                setupRun.start(pending.sortedBy { !it.required })
            },
            onStop = { setupRun.stop() },
            onFinish = finish,
            modifier = Modifier.entrance(rememberEntrance(SetupItem.entries.size + 1)),
        )
    }
}

/**
 * The "Set up everything" walk.
 *
 * Termination is structural rather than timed. [cursor] only moves forward, and only when the item
 * under it has been dealt with, so each item is asked for at most once and the walk runs out of
 * queue after at most [total] steps. The one case that could otherwise stall it, a user who returns
 * having granted nothing, is caught by counting consecutive returns that changed nothing: the item
 * has already had its turn, so a second unchanged return is taken as a no and ends the walk.
 */
@Stable
private class SetupRun {

    var queue: List<SetupItem> by mutableStateOf(emptyList())
        private set

    var cursor by mutableIntStateOf(0)
        private set

    var active by mutableStateOf(false)
        private set

    /** True once the current item has come back ungranted, which changes what the footer says. */
    var stalled by mutableStateOf(false)
        private set

    private var stalls = 0

    val total: Int get() = queue.size
    val step: Int get() = (cursor + 1).coerceAtMost(total)
    val current: SetupItem? get() = queue.getOrNull(cursor)

    fun start(items: List<SetupItem>) {
        queue = items
        cursor = 0
        stalls = 0
        stalled = false
        active = items.isNotEmpty()
    }

    fun stop() {
        active = false
        stalled = false
        queue = emptyList()
        cursor = 0
        stalls = 0
    }

    /** One call per resume while the walk is live; [pending] reads the grants just polled. */
    fun onReturned(pending: (SetupItem) -> Boolean) {
        if (!active) return
        val item = current
        if (item == null) {
            stop()
            return
        }
        if (pending(item)) {
            stalls++
            stalled = true
            if (stalls >= 2) stop()
            return
        }
        stalls = 0
        stalled = false
        // Skip anything the user granted along the way, so a settings screen that hands over two
        // permissions at once does not cost two steps.
        var next = cursor + 1
        while (next < queue.size && !pending(queue[next])) next++
        cursor = next
        if (next >= queue.size) stop()
    }
}

/**
 * Fade and rise, driven from the draw phase.
 *
 * The state is read inside the [graphicsLayer] block, so the animation re-renders one layer per
 * frame and recomposes nothing: at 120 Hz the difference between this and `Modifier.alpha(state)`
 * is six rows of recomposition per frame against none.
 */
private fun Modifier.entrance(progress: Animatable<Float, AnimationVector1D>): Modifier =
    graphicsLayer {
        val t = progress.value
        alpha = t
        translationY = (1f - t) * RISE_DP.dp.toPx()
    }

@Composable
private fun rememberEntrance(index: Int): Animatable<Float, AnimationVector1D> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * STAGGER_MILLIS)
        progress.animateTo(1f, Motion.medium())
    }
    return progress
}

/** Mark, name and one sentence saying what the app is for. */
@Composable
private fun BrandHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(34.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp,
                ),
            )
            Text(
                text = stringResource(R.string.setup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The walk, and the two ways out of first run.
 *
 * Skip is deliberately as reachable as Continue. LuxRamp without its grants is a worse app, not a
 * broken one, and holding somebody on a permissions screen they have already read is not a
 * negotiating position worth having.
 */
@Composable
private fun FooterCard(
    setupRun: SetupRun,
    pending: List<SetupItem>,
    requiredOk: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier, spacing = 14) {
        val current = setupRun.current
        if (setupRun.active && current != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.setup_run_progress, setupRun.step, setupRun.total),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Explainer(
                        stringResource(
                            if (setupRun.stalled) R.string.setup_run_waiting else R.string.setup_run_opening,
                            stringResource(current.title),
                        ),
                    )
                }
                TextButton(onClick = onStop) { Text(stringResource(R.string.setup_run_stop)) }
            }
        } else if (pending.isNotEmpty()) {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_run_all))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onFinish, enabled = requiredOk, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.setup_continue))
            }
            TextButton(onClick = onFinish) { Text(stringResource(R.string.setup_skip)) }
        }
        Explainer(
            stringResource(
                if (requiredOk) R.string.setup_footer_ready else R.string.setup_footer_blocked,
            ),
        )
    }
}
