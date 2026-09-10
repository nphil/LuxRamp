package dev.nphil.luxramp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nphil.luxramp.R
import dev.nphil.luxramp.ui.LuxIcons
import dev.nphil.luxramp.ui.components.Explainer
import dev.nphil.luxramp.ui.components.SectionCard
import dev.nphil.luxramp.ui.theme.ramp

/**
 * The one control, and one honest sentence about what it is doing.
 *
 * It is deliberately the smallest card on the screen. The live view directly
 * below shows the room and the panel moving together, which is far better
 * evidence that the app is working than anything a status card could claim.
 */
@Composable
internal fun MasterCard(
    enabled: Boolean,
    screenOn: Boolean,
    instantWriter: Boolean,
    shizukuReady: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = when {
        !enabled -> stringResource(R.string.master_state_off)
        !screenOn -> stringResource(R.string.master_state_screen_off)
        instantWriter -> stringResource(R.string.master_state_running_instant)
        else -> stringResource(R.string.master_state_running_system)
    }

    SectionCard(
        modifier = modifier,
        title = stringResource(R.string.master_title),
        subtitle = state,
        icon = if (enabled) LuxIcons.Sun else LuxIcons.SunDim,
        trailing = { Switch(checked = enabled, onCheckedChange = onToggle) },
        spacing = 10,
    ) {
        Explainer(stringResource(R.string.master_explain))
        // The slow writer is the only degradation worth putting on the main
        // screen: everything else in setup either blocks the app entirely or
        // costs nothing you would notice. Settings owns the actual fix.
        if (!shizukuReady) SlowWriterNotice(onOpenSettings)
    }
}

@Composable
private fun SlowWriterNotice(onOpenSettings: () -> Unit) {
    val warning = MaterialTheme.ramp.warning
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = warning.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = LuxIcons.Warning,
                contentDescription = null,
                tint = warning,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.master_slow_writer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.master_slow_writer_action))
            }
        }
    }
}
