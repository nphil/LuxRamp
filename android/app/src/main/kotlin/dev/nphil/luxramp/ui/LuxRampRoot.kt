package dev.nphil.luxramp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nphil.luxramp.AppContainer
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.ui.components.AppBackdrop
import dev.nphil.luxramp.ui.home.HomeScreen
import dev.nphil.luxramp.ui.onboarding.OnboardingScreen
import dev.nphil.luxramp.ui.settings.SettingsScreen
import dev.nphil.luxramp.ui.theme.Motion
import kotlinx.coroutines.launch

/** Ordered so a transition can tell a push from a pop by comparing ordinals. */
private enum class Screen { Onboarding, Home, Settings }

/**
 * The app's one decision point: which screen, and where its data comes from.
 *
 * Preferences and telemetry are collected exactly here. Everything below takes
 * them as parameters, so a 4 Hz telemetry emission wakes one composable rather
 * than one per card, and no screen can quietly open a second subscription to
 * the same flow.
 */
@Composable
fun LuxRampRoot(container: AppContainer) {
    // Null until the store answers. The activity already held the splash for
    // this read, so it lands within a frame or two; drawing a guessed default
    // in the meantime would show onboarding to somebody who is set up.
    val prefs: Prefs? = container.prefs.prefs.collectAsStateWithLifecycle<Prefs?>(null).value
    val telemetry by container.controller.telemetry.collectAsStateWithLifecycle()

    // Shizuku can be started, and any grant given, while the app is in the
    // background. Nothing tells us that happened except coming back.
    LifecycleResumeEffect(container) {
        container.gateway.refresh()
        onPauseOrDispose { }
    }

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val screen = when {
        prefs?.onboarded != true -> Screen.Onboarding
        settingsOpen -> Screen.Settings
        else -> Screen.Home
    }

    // Settings is a push over home, not a sibling, so back has to unwind it
    // instead of leaving the app. The activity opts into the predictive back
    // callback, which is what lets the system animate this gesture.
    BackHandler(enabled = screen == Screen.Settings) { settingsOpen = false }

    // Screens keep their own saveable state (scroll position, expanded rows)
    // across a settings round trip: coming back should land where you left.
    val stateHolder = rememberSaveableStateHolder()

    AppBackdrop {
        if (prefs != null) {
            AnimatedContent(
                targetState = screen,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { pushOrPop(targetState.ordinal >= initialState.ordinal) },
                label = "screen",
            ) { current ->
                stateHolder.SaveableStateProvider(current.name) {
                    when (current) {
                        // The write goes to the app scope, not a composition
                        // scope: finishing onboarding is what removes this
                        // screen, and a cancelled write would put the user
                        // through first run again on the next launch.
                        Screen.Onboarding -> OnboardingScreen(
                            container = container,
                            onFinished = {
                                container.appScope.launch { container.prefs.setOnboarded(true) }
                            },
                        )

                        Screen.Home -> HomeScreen(
                            container = container,
                            prefs = prefs,
                            telemetry = telemetry,
                            onOpenSettings = { settingsOpen = true },
                        )

                        Screen.Settings -> SettingsScreen(
                            container = container,
                            prefs = prefs,
                            onBack = { settingsOpen = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fade, plus a twentieth of the screen of travel in the direction of the move.
 *
 * Enough displacement to read as a direction, too little to look like a page
 * turn: these are two views of one app, not a stack of documents. The outgoing
 * screen only fades, and faster than the incoming one arrives, so the two never
 * fight for the same pixels.
 */
private fun pushOrPop(forward: Boolean): ContentTransform {
    val travel = tween<IntOffset>(Motion.MEDIUM_MILLIS, easing = Motion.Emphasised)
    val enter = fadeIn(tween(Motion.MEDIUM_MILLIS, easing = Motion.Emphasised)) +
        slideInVertically(travel) { height -> if (forward) height / 20 else -height / 20 }
    return enter togetherWith fadeOut(tween(Motion.FAST_MILLIS, easing = Motion.Standard))
}
