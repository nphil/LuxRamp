package dev.nphil.luxramp

import android.content.Context
import dev.nphil.luxramp.control.BrightnessController
import dev.nphil.luxramp.control.ShizukuGateway
import dev.nphil.luxramp.data.PreferencesRepository
import dev.nphil.luxramp.service.MiniWindowHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The one graph in the process.
 *
 * The service and the activity are two independent entry points onto the same hardware: one light
 * sensor registration, one ramp, one brightness writer. They therefore share a single
 * [BrightnessController] rather than each building their own, which would fight over the display.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val prefs = PreferencesRepository(appContext)

    /** Process-wide singleton: its Shizuku listeners are static and must be attached exactly once. */
    val gateway: ShizukuGateway = ShizukuGateway.get(appContext)

    /** Outlives every composition and every service binding; the ramp must not stop when the UI does. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val controller = BrightnessController(appContext, prefs, gateway, appScope)

    /**
     * One overlay per process, built on first use: the window is optional, and its WindowManager
     * plumbing costs nothing until something asks for it. Two hosts would mean two windows.
     */
    val miniWindow by lazy { MiniWindowHost(appContext) }
}
