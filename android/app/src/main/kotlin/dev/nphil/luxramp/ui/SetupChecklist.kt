package dev.nphil.luxramp.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.nphil.luxramp.R
import dev.nphil.luxramp.control.ShizukuGateway
import dev.nphil.luxramp.control.ShizukuState
import dev.nphil.luxramp.service.BrightnessService

/** HyperOS' Security app; the only place autostart can be granted, and only by the user. */
private const val MIUI_SECURITY_PACKAGE = "com.miui.securitycenter"
private const val MIUI_AUTOSTART_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"

/**
 * The grants the loop needs, re-read whenever the screen resumes.
 *
 * All three are granted outside the app - two in Settings, one in a system dialog - so there is no
 * callback to hang state off; polling on resume is the only thing that stays true.
 */
internal data class SetupGrants(
    val canWriteSettings: Boolean,
    val notificationsGranted: Boolean,
    val batteryUnrestricted: Boolean,
)

internal fun readGrants(context: Context): SetupGrants = SetupGrants(
    canWriteSettings = Settings.System.canWrite(context),
    notificationsGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED,
    batteryUnrestricted = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName),
)

/**
 * Only what is still missing.
 *
 * A checklist of ticks is noise on every launch after the first; a row here always means there is
 * something left to do, so the card disappears entirely once the app is set up.
 */
@Composable
internal fun SetupChecklist(
    shizuku: ShizukuState,
    grants: SetupGrants,
    gateway: ShizukuGateway,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shizukuReady = shizuku is ShizukuState.Ready
    if (shizukuReady && grants.canWriteSettings && grants.notificationsGranted && grants.batteryUnrestricted) return

    SectionCard(title = stringResource(R.string.setup_title), modifier = modifier) {
        if (!shizukuReady) {
            SetupRow(
                title = stringResource(R.string.setup_shizuku),
                detail = shizukuDetail(shizuku),
                action = if (shizuku == ShizukuState.PermissionNeeded) {
                    stringResource(R.string.action_grant)
                } else {
                    stringResource(R.string.action_recheck)
                },
                onAction = {
                    if (shizuku == ShizukuState.PermissionNeeded) {
                        gateway.requestPermission(ShizukuGateway.PERMISSION_REQUEST_CODE)
                    } else {
                        gateway.refresh()
                    }
                },
            )
        }
        if (!grants.canWriteSettings) {
            SetupRow(
                title = stringResource(R.string.setup_write_settings),
                detail = stringResource(R.string.setup_write_settings_detail),
                action = stringResource(R.string.action_open),
                onAction = { launchIfResolvable(context, packageIntent(Settings.ACTION_MANAGE_WRITE_SETTINGS, context)) },
            )
        }
        if (!grants.notificationsGranted) {
            SetupRow(
                title = stringResource(R.string.setup_notifications),
                detail = stringResource(R.string.setup_notifications_detail),
                action = stringResource(R.string.action_grant),
                onAction = onRequestNotifications,
            )
        }
        if (!grants.batteryUnrestricted) {
            SetupRow(
                title = stringResource(R.string.setup_battery),
                detail = stringResource(R.string.setup_battery_detail),
                action = stringResource(R.string.action_open),
                onAction = {
                    launchIfResolvable(
                        context,
                        packageIntent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, context),
                    )
                },
            )
        }
    }
}

/**
 * The two vendor knobs that cannot be read back.
 *
 * Neither autostart nor "notifications off for this channel" is queryable, so neither can ever be
 * ticked off. They live behind a disclosure instead of pretending to be checklist items that are
 * permanently unmet.
 */
@Composable
internal fun HyperOsExtras(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    SectionCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.extras_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggle) {
                Text(
                    stringResource(if (expanded) R.string.action_hide else R.string.action_show),
                )
            }
        }
        if (!expanded) return@SectionCard

        val autostart = autostartIntent()
        if (autostart.resolveActivity(context.packageManager) != null) {
            SetupRow(
                title = stringResource(R.string.setup_autostart),
                detail = stringResource(R.string.setup_autostart_detail),
                action = stringResource(R.string.action_open),
                onAction = { launchIfResolvable(context, autostart) },
            )
        }
        SetupRow(
            title = stringResource(R.string.setup_hide_notification),
            detail = stringResource(R.string.setup_hide_notification_detail),
            action = stringResource(R.string.action_open),
            onAction = { launchIfResolvable(context, notificationChannelIntent(context)) },
        )
    }
}

@Composable
private fun SetupRow(title: String, detail: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun shizukuDetail(state: ShizukuState): String = when (state) {
    ShizukuState.NotInstalled -> stringResource(R.string.setup_shizuku_not_installed)
    ShizukuState.NotRunning -> stringResource(R.string.setup_shizuku_not_running)
    ShizukuState.PermissionNeeded -> stringResource(R.string.setup_shizuku_permission_needed)
    is ShizukuState.Ready -> stringResource(R.string.setup_shizuku_ready)
}

private fun packageIntent(action: String, context: Context): Intent =
    Intent(action, Uri.fromParts("package", context.packageName, null))

private fun autostartIntent(): Intent =
    Intent().setComponent(ComponentName(MIUI_SECURITY_PACKAGE, MIUI_AUTOSTART_ACTIVITY))

/**
 * The channel screen only exists once the service has created the channel; before that the user is
 * sent to the app's notification settings, which is where the channel will appear.
 */
private fun notificationChannelIntent(context: Context): Intent {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel = manager?.getNotificationChannel(BrightnessService.CHANNEL_ID)
    return if (channel == null) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, BrightnessService.CHANNEL_ID)
    }
}

/**
 * Vendor screens come and go between HyperOS builds, and a missing one must not take the app down
 * with it.
 */
private fun launchIfResolvable(context: Context, intent: Intent) {
    if (intent.resolveActivity(context.packageManager) == null) return
    runCatching { context.startActivity(intent) }
}
