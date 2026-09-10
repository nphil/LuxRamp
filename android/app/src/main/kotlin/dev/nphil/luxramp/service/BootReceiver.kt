package dev.nphil.luxramp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.nphil.luxramp.LuxRampApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings the service back after a reboot, for whichever duty the user left switched on: following
 * the light sensor, the floating window, or both.
 *
 * Both flags live in DataStore, so the answer needs a suspend read: [goAsync] holds the broadcast
 * open while it happens, and the pending result is finished on every path.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? LuxRampApp ?: return
        val container = app.container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val prefs = runCatching { container.prefs.prefs.first() }.getOrNull()
                if (prefs != null && (prefs.enabled || prefs.miniEnabled)) BrightnessService.start(app)
            } finally {
                pending.finish()
            }
        }
    }
}
