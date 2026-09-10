package dev.nphil.luxramp.ui.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.nphil.luxramp.R
import dev.nphil.luxramp.control.SHIZUKU_PACKAGE
import dev.nphil.luxramp.control.ShizukuGateway
import dev.nphil.luxramp.control.ShizukuState
import dev.nphil.luxramp.service.BrightnessService
import dev.nphil.luxramp.ui.LuxIcons
import dev.nphil.luxramp.ui.components.Explainer
import dev.nphil.luxramp.ui.components.StatusPill
import dev.nphil.luxramp.ui.theme.ramp

/** HyperOS' Security app; the only place autostart can be granted, and only by the user. */
private const val MIUI_SECURITY_PACKAGE = "com.miui.securitycenter"
private const val MIUI_AUTOSTART_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"

/** Where somebody without Shizuku goes to get it. */
private const val SHIZUKU_SITE = "https://shizuku.rikka.app/"

/**
 * Everything LuxRamp has to be given, read in one go.
 *
 * None of these arrive through a callback: four are granted on a screen outside the app and one in
 * a system dialog, so polling on resume is the only reading that stays true. Shizuku is carried as
 * its whole state rather than a boolean because "not installed", "not started" and "not authorised"
 * are three different next steps for the user.
 */
data class Grants(
    val shizuku: ShizukuState,
    val canWriteSettings: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val canDrawOverlays: Boolean,
)

fun readGrants(context: Context, shizuku: ShizukuState): Grants = Grants(
    shizuku = shizuku,
    canWriteSettings = Settings.System.canWrite(context),
    notifications = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED,
    batteryUnrestricted = context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName),
    canDrawOverlays = Settings.canDrawOverlays(context),
)

/**
 * The five things first run asks for, in the order it asks for them.
 *
 * Required means the app cannot do its job at all without it. Shizuku is not required even though
 * it is the point of the app: LuxRamp degrades to the platform's own slow ramp rather than refusing
 * to start, and saying so is more honest than a blocking gate.
 */
enum class SetupItem(
    @StringRes val title: Int,
    @StringRes val explain: Int,
    val required: Boolean,
) {
    WRITE_SETTINGS(R.string.setup_item_write_title, R.string.setup_item_write_explain, required = true),
    NOTIFICATIONS(R.string.setup_item_notifications_title, R.string.setup_item_notifications_explain, required = true),
    BATTERY(R.string.setup_item_battery_title, R.string.setup_item_battery_explain, required = true),
    SHIZUKU(R.string.setup_item_shizuku_title, R.string.setup_item_shizuku_explain, required = false),
    OVERLAY(R.string.setup_item_overlay_title, R.string.setup_item_overlay_explain, required = false);

    /** A getter, so the enum does not force [LuxIcons] to build every vector at class load. */
    val icon: ImageVector
        get() = when (this) {
            WRITE_SETTINGS -> LuxIcons.Sliders
            NOTIFICATIONS -> LuxIcons.Bell
            BATTERY -> LuxIcons.Battery
            SHIZUKU -> LuxIcons.Bolt
            OVERLAY -> LuxIcons.Window
        }

    fun isGranted(grants: Grants): Boolean = when (this) {
        WRITE_SETTINGS -> grants.canWriteSettings
        NOTIFICATIONS -> grants.notifications
        BATTERY -> grants.batteryUnrestricted
        SHIZUKU -> grants.shizuku.ready
        OVERLAY -> grants.canDrawOverlays
    }
}

/** Still missing, in ask order. */
fun Grants.pending(): List<SetupItem> = SetupItem.entries.filter { !it.isGranted(this) }

fun Grants.pendingRequired(): Int = SetupItem.entries.count { it.required && !it.isGranted(this) }

/**
 * Asks for one item.
 *
 * Every branch except notifications leaves the app, which is why nothing here reports a result:
 * the answer is read back on resume by whoever called this.
 */
fun SetupItem.request(
    context: Context,
    grants: Grants,
    gateway: ShizukuGateway,
    requestNotifications: () -> Unit,
) {
    when (this) {
        SetupItem.WRITE_SETTINGS ->
            launchIfResolvable(context, packageIntent(Settings.ACTION_MANAGE_WRITE_SETTINGS, context))

        SetupItem.NOTIFICATIONS -> requestNotifications()

        SetupItem.BATTERY ->
            launchIfResolvable(context, packageIntent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, context))

        SetupItem.OVERLAY ->
            launchIfResolvable(context, packageIntent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, context))

        // Three different problems wearing one name. Sending somebody to the authorisation dialog
        // when the binder is dead just shows them nothing at all.
        SetupItem.SHIZUKU -> when (grants.shizuku) {
            ShizukuState.PermissionNeeded -> gateway.requestPermission()
            ShizukuState.NotRunning -> launchIfResolvable(context, shizukuOrSiteIntent(context))
            ShizukuState.NotInstalled -> launchIfResolvable(context, siteIntent())
            is ShizukuState.Ready -> Unit
        }
    }
}

/** The button on the row: what the user is about to be shown, not what they will end up granting. */
@StringRes
fun SetupItem.actionLabel(grants: Grants): Int = when (this) {
    SetupItem.NOTIFICATIONS -> R.string.setup_action_allow
    SetupItem.SHIZUKU -> when (grants.shizuku) {
        ShizukuState.NotInstalled -> R.string.setup_action_install
        ShizukuState.NotRunning -> R.string.setup_action_start
        ShizukuState.PermissionNeeded -> R.string.setup_action_authorise
        is ShizukuState.Ready -> R.string.setup_action_open
    }
    else -> R.string.setup_action_open
}

/** A second line, only where the static explanation cannot say what to do next. */
@StringRes
fun SetupItem.note(grants: Grants): Int? = if (this != SetupItem.SHIZUKU) null else when (grants.shizuku) {
    ShizukuState.NotInstalled -> R.string.setup_shizuku_missing
    ShizukuState.NotRunning -> R.string.setup_shizuku_stopped
    ShizukuState.PermissionNeeded -> R.string.setup_shizuku_unauthorised
    is ShizukuState.Ready -> R.string.setup_shizuku_ready
}

/**
 * One permission, rendered the same way on first run and in Settings.
 *
 * A granted row keeps its place instead of vanishing: this list is also where somebody comes back
 * months later to check what they gave the app, and a list that only shows failures cannot answer
 * that question.
 */
@Composable
internal fun PermissionRow(
    item: SetupItem,
    grants: Grants,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val granted = item.isGranted(grants)
    val stateLabel = stringResource(
        when {
            granted -> R.string.setup_state_granted
            item.required -> R.string.setup_state_needed
            else -> R.string.setup_state_optional
        },
    )
    val stateColor = when {
        granted -> MaterialTheme.ramp.active
        item.required -> MaterialTheme.ramp.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            tint = if (granted) MaterialTheme.ramp.active else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 3.dp).size(20.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(stringResource(item.title), style = MaterialTheme.typography.bodyLarge)
            Explainer(stringResource(item.explain))
            val note = item.note(grants)
            if (note != null) {
                Text(
                    text = stringResource(note),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) MaterialTheme.ramp.active else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            StatusPill(
                label = stateLabel,
                color = stateColor,
                icon = if (granted) LuxIcons.Check else null,
                filled = granted,
            )
            if (!granted) {
                TextButton(onClick = onGrant) { Text(stringResource(item.actionLabel(grants))) }
            }
        }
    }
}

/**
 * Vendor screens come and go between HyperOS builds, and a missing one must not take the app down
 * with it.
 */
internal fun launchIfResolvable(context: Context, intent: Intent) {
    if (intent.resolveActivity(context.packageManager) == null) return
    runCatching { context.startActivity(intent) }
}

internal fun autostartIntent(): Intent =
    Intent().setComponent(ComponentName(MIUI_SECURITY_PACKAGE, MIUI_AUTOSTART_ACTIVITY))

/**
 * The channel screen only exists once the service has created the channel; before that the user is
 * sent to the app's notification settings, which is where the channel will appear.
 */
internal fun notificationChannelIntent(context: Context): Intent {
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

private fun packageIntent(action: String, context: Context): Intent =
    Intent(action, Uri.fromParts("package", context.packageName, null))

private fun siteIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_SITE))

/** Sui has no launcher activity of its own, so a stopped binder can still mean "go and read this". */
private fun shizukuOrSiteIntent(context: Context): Intent =
    context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: siteIntent()
