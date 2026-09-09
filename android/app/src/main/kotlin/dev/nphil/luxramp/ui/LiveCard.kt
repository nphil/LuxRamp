package dev.nphil.luxramp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nphil.luxramp.R
import dev.nphil.luxramp.control.Telemetry
import dev.nphil.luxramp.engine.Brightness
import dev.nphil.luxramp.ui.theme.MonoFamily

/**
 * What the loop is seeing and doing, right now.
 *
 * Raw against filtered lux shows the smoothing working; target against current shows the ramp
 * working. Both pairs are needed to tell "the room changed" from "the tuning is wrong".
 */
@Composable
internal fun LiveCard(telemetry: Telemetry, modifier: Modifier = Modifier) {
    SectionCard(title = stringResource(R.string.live_title), modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell(
                label = stringResource(R.string.live_lux_raw),
                value = luxText(telemetry.rawLux),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = stringResource(R.string.live_lux_filtered),
                value = luxText(telemetry.filteredLux),
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell(
                label = stringResource(R.string.live_target),
                value = percentText(telemetry.targetLinear),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = stringResource(R.string.live_current),
                value = percentText(telemetry.currentLinear),
                modifier = Modifier.weight(1f),
            )
        }
        TraceChart(telemetry.history)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(R.string.live_legend_lux),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = stringResource(R.string.live_legend_brightness),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (telemetry.history.size < 2) {
            Text(
                text = stringResource(R.string.live_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = MonoFamily,
        )
    }
}

/** Both the filter and the ramper report NaN until their first sample, which is a state, not a value. */
@Composable
private fun luxText(lux: Float): String =
    if (lux.isNaN()) stringResource(R.string.value_unknown) else stringResource(R.string.value_lux, lux)

@Composable
private fun percentText(linear: Float): String = if (linear.isNaN()) {
    stringResource(R.string.value_unknown)
} else {
    stringResource(R.string.value_percent, Brightness.toPercent(linear))
}
