package dev.nphil.luxramp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.nphil.luxramp.LuxRampApp
import dev.nphil.luxramp.R
import dev.nphil.luxramp.engine.Brightness
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The one foreground anchor in the process, for two duties that switch on and off independently:
 * following the light sensor, and holding the floating window up.
 *
 * They have to share a service because the floating window can switch automatic control off from
 * inside itself, and it must survive doing so. Neither duty is commanded directly: both are read
 * from the preferences, which are also what the UI, the notification actions and the boot receiver
 * write, so there is exactly one answer to "what should be running" and no way for two entry
 * points to disagree about it.
 */
class BrightnessService : LifecycleService() {

    private val container by lazy { (application as LuxRampApp).container }

    private var prefsJob: Job? = null
    private var telemetryJob: Job? = null

    private var controlEnabled = false
    private var windowEnabled = false

    private var lastNotifiedAt = 0L
    private var lastText: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Foreground first, whatever brought us here: the platform gives us seconds, and every
        // action below only writes a preference for the reconciler to pick up.
        if (!goForeground()) return START_NOT_STICKY

        when (intent?.action) {
            // Still "turn automatic control off", not "go away": the floating window may be the
            // reason this service exists, and the reconciler is what decides our lifetime.
            ACTION_STOP -> setEnabled(false)
            ACTION_SHOW_MINI -> setMiniEnabled(true)
            ACTION_HIDE_MINI -> setMiniEnabled(false)
        }

        observePreferences()
        observeTelemetry()
        return START_STICKY
    }

    override fun onDestroy() {
        prefsJob?.cancel()
        prefsJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        container.controller.stop()
        // The window is ours: nothing may outlive the notification that justifies it.
        if (windowEnabled) container.miniWindow.hide()
        super.onDestroy()
    }

    /**
     * Only the two duty flags are watched, not the whole record: the tuning, the palette and the
     * window position all live in the same store, and rebuilding a notification because a slider
     * moved would be pure noise.
     */
    private fun observePreferences() {
        if (prefsJob?.isActive == true) return
        prefsJob = container.prefs.prefs
            .map { it.enabled to it.miniEnabled }
            .distinctUntilChanged()
            .onEach { (control, window) -> reconcile(control, window) }
            .launchIn(lifecycleScope)
    }

    private fun reconcile(control: Boolean, window: Boolean) {
        // Hiding is only ever asked of a window that was asked to show: the host is built on
        // first use, and the common case (control on, no window) should never build it at all.
        val windowChanged = window != windowEnabled
        controlEnabled = control
        windowEnabled = window

        // Both duties are idempotent, which is what lets one change without disturbing the other;
        // the controller is app-scoped, so starting it here is starting the sensor.
        if (control) container.controller.start() else container.controller.stop()
        if (window) container.miniWindow.show() else if (windowChanged) container.miniWindow.hide()

        if (!control && !window) {
            // Every caller writes the preference before it starts us, so an emission with nothing
            // wanted is the real answer and not a start we have raced.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // A duty changing is exactly the moment the notification is wrong, rate limit or not.
        updateNotification(force = true)
    }

    private fun observeTelemetry() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = container.controller.telemetry
            .onEach { updateNotification(force = false) }
            .launchIn(lifecycleScope)
    }

    private fun goForeground(): Boolean {
        val text = describe()
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
        if (started.isFailure) {
            // A SecurityException here means the specialUse type was refused (or the start came
            // from a state the platform disallows). Going down quietly beats crashing the process.
            stopSelf()
            return false
        }
        lastNotifiedAt = SystemClock.elapsedRealtime()
        lastText = text
        return true
    }

    /** The notification is ambient information, not a progress bar: at most one update per 5 s. */
    private fun updateNotification(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifiedAt < NOTIFICATION_INTERVAL_MILLIS) return
        lastNotifiedAt = now
        val text = describe()
        if (!force && text == lastText) return
        lastText = text
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /** What is actually running, in one line: control, the floating window, or both. */
    private fun describe(): String {
        val control = controlText() ?: return windowOnlyText()
        val window = windowFragment() ?: return control
        return getString(R.string.service_status_join, control, window)
    }

    private fun controlText(): String? {
        if (!controlEnabled) return null
        val telemetry = container.controller.telemetry.value
        if (!telemetry.screenOn) return getString(R.string.service_status_screen_off)
        return getString(
            R.string.service_status_live,
            Brightness.toPercent(telemetry.currentLinear).roundToInt(),
            telemetry.filteredLux.roundToInt(),
            telemetry.writerLabel,
        )
    }

    private fun windowOnlyText(): String = when {
        !windowEnabled -> getString(R.string.service_status_starting)
        canShowWindow() -> getString(R.string.service_status_window_only)
        else -> getString(R.string.service_status_window_blocked)
    }

    private fun windowFragment(): String? = when {
        !windowEnabled -> null
        canShowWindow() -> getString(R.string.service_status_window_fragment)
        else -> getString(R.string.service_status_window_blocked_fragment)
    }

    /**
     * The permission, not [MiniWindowHost.isShowing]: the window is added a moment after the
     * preference lands, and a notification that raced it would claim the window failed.
     */
    private fun canShowWindow(): Boolean = Settings.canDrawOverlays(this)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_luxramp)
            .setContentTitle(
                getString(
                    if (controlEnabled) R.string.service_title_control else R.string.service_title_window,
                ),
            )
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
        // Stopping is only an offer while there is something to stop; the window has its own
        // close button, so from here it is a show/hide toggle rather than a second way out.
        if (controlEnabled) {
            builder.addAction(0, getString(R.string.service_action_stop), serviceAction(REQUEST_STOP, ACTION_STOP))
        }
        if (windowEnabled) {
            builder.addAction(
                0,
                getString(R.string.service_action_hide_window),
                serviceAction(REQUEST_HIDE_MINI, ACTION_HIDE_MINI),
            )
        } else {
            builder.addAction(
                0,
                getString(R.string.service_action_show_window),
                serviceAction(REQUEST_SHOW_MINI, ACTION_SHOW_MINI),
            )
        }
        return builder.build()
    }

    private fun serviceAction(requestCode: Int, action: String): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, BrightnessService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setEnabled(enabled: Boolean) {
        // On the app scope, not ours: the write has to finish even if it is what stops us.
        container.appScope.launch { runCatching { container.prefs.setEnabled(enabled) } }
    }

    private fun setMiniEnabled(enabled: Boolean) {
        container.appScope.launch { runCatching { container.prefs.setMiniEnabled(enabled) } }
    }

    companion object {
        const val CHANNEL_ID = "luxramp_service"
        const val ACTION_STOP = "dev.nphil.luxramp.action.STOP"
        const val ACTION_SHOW_MINI = "dev.nphil.luxramp.action.SHOW_MINI"
        const val ACTION_HIDE_MINI = "dev.nphil.luxramp.action.HIDE_MINI"

        private const val NOTIFICATION_ID = 0x4C58
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val REQUEST_SHOW_MINI = 3
        private const val REQUEST_HIDE_MINI = 4
        private const val NOTIFICATION_INTERVAL_MILLIS = 5_000L

        /** Makes sure the anchor exists. What it then does is whatever the preferences say. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BrightnessService::class.java),
                )
            }
        }

        /**
         * Turns automatic control off. The service stops itself once nothing wants it, which is
         * not the same thing as now: the floating window may still be up.
         */
        fun stop(context: Context) {
            val app = context.applicationContext as? LuxRampApp ?: return
            app.container.appScope.launch { runCatching { app.container.prefs.setEnabled(false) } }
        }

        /**
         * Preference first, service second: the service decides what to run from the stored value,
         * so a start that overtook the write would read the old one and stop itself again.
         */
        fun showMini(context: Context) {
            val app = context.applicationContext as? LuxRampApp ?: return
            app.container.appScope.launch {
                runCatching { app.container.prefs.setMiniEnabled(true) }
                start(app)
            }
        }

        /** No start here: with the window unwanted there would be nothing for the service to do. */
        fun hideMini(context: Context) {
            val app = context.applicationContext as? LuxRampApp ?: return
            app.container.appScope.launch {
                runCatching { app.container.prefs.setMiniEnabled(false) }
            }
        }
    }
}
