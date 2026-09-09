package dev.nphil.luxramp.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nphil.luxramp.AppContainer
import dev.nphil.luxramp.R
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.service.BrightnessService
import kotlinx.coroutines.launch

private val DEFAULT_PREFS = Prefs()

/**
 * The whole app: one switch, whatever setup is still missing, what the loop is doing, and the
 * numbers behind it.
 *
 * There is no navigation because there is nothing to navigate to - the tuning only makes sense
 * next to the live trace it changes.
 */
@Composable
fun MainScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by container.prefs.prefs.collectAsStateWithLifecycle(DEFAULT_PREFS)
    val telemetry by container.controller.telemetry.collectAsStateWithLifecycle()
    val shizuku by container.gateway.state.collectAsStateWithLifecycle()

    var grants by remember { mutableStateOf(readGrants(context)) }
    var extrasExpanded by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { grants = readGrants(context) }

    // Shizuku may have been started, and any of the three grants given, while this screen was away.
    LifecycleResumeEffect(container) {
        container.gateway.refresh()
        grants = readGrants(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MasterSwitchCard(
            enabled = prefs.enabled,
            screenOn = telemetry.screenOn,
            writerLabel = telemetry.writerLabel,
            onToggle = { checked ->
                scope.launch {
                    // Persist first: the service reads the flag as it starts, and the boot
                    // receiver reads it with no UI in the process at all.
                    container.prefs.setEnabled(checked)
                    if (checked) BrightnessService.start(context) else BrightnessService.stop(context)
                }
            },
        )
        SetupChecklist(
            shizuku = shizuku,
            grants = grants,
            gateway = container.gateway,
            onRequestNotifications = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
        LiveCard(telemetry)
        TuningCard(
            prefs = prefs,
            onUpdate = { transform -> scope.launch { container.prefs.update(transform) } },
        )
        HyperOsExtras(expanded = extrasExpanded, onToggle = { extrasExpanded = !extrasExpanded })
    }
}

@Composable
private fun MasterSwitchCard(
    enabled: Boolean,
    screenOn: Boolean,
    writerLabel: String,
    onToggle: (Boolean) -> Unit,
) {
    val status = when {
        !enabled -> stringResource(R.string.master_status_off)
        !screenOn -> stringResource(R.string.master_status_screen_off)
        else -> stringResource(R.string.master_status_writer, writerLabel)
    }
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.master_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/** One padded surface per section, so the screen reads as a stack of subjects rather than a form. */
@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}
