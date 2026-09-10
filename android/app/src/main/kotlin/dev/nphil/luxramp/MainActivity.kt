package dev.nphil.luxramp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.data.ThemeMode
import dev.nphil.luxramp.ui.LuxRampRoot
import dev.nphil.luxramp.ui.theme.LuxRampTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The window, and nothing else: colour, refresh rate, and the one composition.
 *
 * Everything the app does lives in [LuxRampRoot] and the controller behind it.
 * What is left here is the handful of decisions that can only be made against a
 * real Window, and all three of them are about the first second of the app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hold the splash until the stored preferences have been read once. The
        // launch decision (onboarding or home) and the resolved theme are both
        // in them, so composing before they arrive would flash the wrong screen
        // in the wrong colours at somebody who is already set up.
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        lifecycleScope.launch {
            val initial = container().prefs.prefs.first()
            ready = true
            render(initial)
        }

        // Provisional: the real styles are applied from the resolved theme in
        // render(), but the window has to be edge to edge before the first
        // frame or the backdrop would start inset and then jump.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferHighestRefreshRate()
    }

    private fun render(initial: Prefs) {
        setContent {
            // Seeded with the value the splash waited for, so this collection
            // never renders a default. DataStore serves both this collector and
            // the root's from the same in-memory copy.
            val prefs by container().prefs.prefs.collectAsStateWithLifecycle(initial)

            // Bar icon contrast has to track the theme the APP resolved, not the
            // system's day/night flag: with the theme forced dark on a
            // light-mode tablet, SystemBarStyle.auto would draw dark icons over
            // our dark backdrop and the clock would vanish.
            val dark = when (prefs.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle =
                        if (dark) SystemBarStyle.dark(TRANSPARENT)
                        else SystemBarStyle.light(TRANSPARENT, TRANSPARENT),
                    navigationBarStyle =
                        if (dark) SystemBarStyle.dark(TRANSPARENT)
                        else SystemBarStyle.light(TRANSPARENT, TRANSPARENT),
                )
            }

            LuxRampTheme(
                themeMode = prefs.themeMode,
                dynamicColor = prefs.dynamicColor,
                themeId = prefs.themeId,
            ) {
                LuxRampRoot(container())
            }
        }
    }

    private fun container() = (application as LuxRampApp).container

    /**
     * Ask for the panel's fastest mode.
     *
     * Android caps a window at 60 Hz on many devices unless it asks otherwise,
     * which would silently halve the frame rate of an app that is a live chart,
     * a ramping number and a curve, no matter how cheap the frames are. minSdk
     * is 34, so the modern lever is the only one worth carrying: state a
     * preferred rate and let the platform pick the mode, rather than pinning a
     * display mode id and risking a resolution change.
     */
    private fun preferHighestRefreshRate() {
        val fastest = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: return
        val params: WindowManager.LayoutParams = window.attributes
        if (params.preferredRefreshRate == fastest) return
        params.preferredRefreshRate = fastest
        window.attributes = params
    }

    private companion object {
        const val TRANSPARENT = 0x00000000
    }
}
