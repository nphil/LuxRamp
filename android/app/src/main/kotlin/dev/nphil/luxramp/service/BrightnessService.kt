package dev.nphil.luxramp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.nphil.luxramp.LuxRampApp
import dev.nphil.luxramp.R
import dev.nphil.luxramp.control.Telemetry
import dev.nphil.luxramp.engine.Brightness
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the process alive so [dev.nphil.luxramp.control.BrightnessController] can follow the light
 * sensor while LuxRamp is not on screen.
 *
 * The service owns nothing but its own lifetime: the controller is app-scoped (one per process,
 * shared with the UI), so starting and stopping the service is exactly starting and stopping the
 * sensor plumbing.
 */
class BrightnessService : LifecycleService() {

    private val container by lazy { (application as LuxRampApp).container }

    private var telemetryJob: Job? = null
    private var lastNotifiedAt = 0L
    private var lastText: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            // The switch in the UI is the single source of truth for "should LuxRamp be running",
            // so the notification's Stop has to flip it too - and on a scope that outlives us.
            container.appScope.launch { runCatching { container.prefs.setEnabled(false) } }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val text = describe(container.controller.telemetry.value)
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
            return START_NOT_STICKY
        }
        lastNotifiedAt = SystemClock.elapsedRealtime()
        lastText = text

        container.controller.start()
        observeTelemetry()
        return START_STICKY
    }

    override fun onDestroy() {
        telemetryJob?.cancel()
        telemetryJob = null
        container.controller.stop()
        super.onDestroy()
    }

    private fun observeTelemetry() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = container.controller.telemetry
            .onEach { telemetry -> updateNotification(telemetry) }
            .launchIn(lifecycleScope)
    }

    /** The notification is ambient information, not a progress bar: at most one update per 5 s. */
    private fun updateNotification(telemetry: Telemetry) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifiedAt < NOTIFICATION_INTERVAL_MILLIS) return
        lastNotifiedAt = now
        val text = describe(telemetry)
        if (text == lastText) return
        lastText = text
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun describe(telemetry: Telemetry): String {
        if (!telemetry.screenOn) return "Screen off - waiting for light"
        val percent = Brightness.toPercent(telemetry.currentLinear).roundToInt()
        val lux = telemetry.filteredLux.roundToInt()
        return "$percent% at $lux lx - ${telemetry.writerLabel}"
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background service",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Shown while LuxRamp is following the light sensor"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, BrightnessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_luxramp)
            .setContentTitle("LuxRamp active")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "luxramp_service"
        const val ACTION_STOP = "dev.nphil.luxramp.action.STOP"

        private const val NOTIFICATION_ID = 0x4C58
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val NOTIFICATION_INTERVAL_MILLIS = 5_000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BrightnessService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BrightnessService::class.java)) }
        }
    }
}
