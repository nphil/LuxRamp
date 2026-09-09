package dev.nphil.luxramp.control

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/** Package name of the Shizuku manager app, used for the install/open deep links. */
const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/**
 * Availability of the privileged binder LuxRamp needs for instant brightness writes.
 *
 * Shizuku is an optimisation, never a requirement: without it the controller writes
 * `Settings.System.SCREEN_BRIGHTNESS` and the platform performs its own slow ramp.
 */
sealed interface ShizukuState {
    /** Neither the Shizuku manager nor Sui is present on the device. */
    data object NotInstalled : ShizukuState

    /** Shizuku exists but no live binder: not started after reboot, or too old to use. */
    data object NotRunning : ShizukuState

    /** Binder alive, the user has not authorised this app yet. */
    data object PermissionNeeded : ShizukuState

    /** Instant writes are available. [uid] is 2000 for the ADB backend and 0 for the root backend. */
    data class Ready(val uid: Int, val version: Int) : ShizukuState

    val ready: Boolean get() = this is Ready

    /** One line of human text describing the state, for the setup checklist. */
    val detail: String
        get() = when (this) {
            is NotInstalled -> "Shizuku is not installed. LuxRamp will use the slower system ramp."
            is NotRunning -> "Shizuku is installed but not running. Start it, then come back."
            is PermissionNeeded -> "Shizuku is running. Authorise LuxRamp for instant brightness writes."
            is Ready -> "Shizuku ready (uid $uid, API v$version)."
        }
}

/**
 * Tracks whether Shizuku can hand us a privileged binder.
 *
 * Process-wide singleton: the Shizuku listeners it registers are static and must be attached
 * exactly once, so [get] hands out the one instance instead of letting every caller add another
 * listener that would never be removed.
 */
class ShizukuGateway private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val onBinderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        // Sui (the Magisk-module flavour of Shizuku) delivers its binder only after init().
        runCatching { Sui.init(appContext.packageName) }
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
        refresh()
    }

    /** Re-probes the binder; safe to call from any thread. */
    fun refresh() {
        _state.value = probe()
    }

    private fun probe(): ShizukuState {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            return if (isManagerInstalled()) ShizukuState.NotRunning else ShizukuState.NotInstalled
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            return ShizukuState.NotRunning
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) return ShizukuState.PermissionNeeded
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        val version = runCatching { Shizuku.getVersion() }.getOrDefault(-1)
        return ShizukuState.Ready(uid, version)
    }

    private fun isManagerInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false) || runCatching { Sui.isSui() }.getOrDefault(false)

    /** Shows Shizuku's authorisation dialog. The result arrives through [state]. */
    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            refresh()
            return
        }
        runCatching { Shizuku.requestPermission(requestCode) }.onFailure { refresh() }
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 4411

        @Volatile
        private var instance: ShizukuGateway? = null

        fun get(context: Context): ShizukuGateway =
            instance ?: synchronized(this) {
                instance ?: ShizukuGateway(context).also { instance = it }
            }
    }
}
