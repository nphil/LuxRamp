package dev.nphil.luxramp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nphil.luxramp.R
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.ui.theme.MonoFamily
import kotlin.math.roundToLong

private val RAMP_UP_RANGE = 100f..2000f
private val RAMP_DOWN_RANGE = 200f..4000f
private val TAU_UP_RANGE = 50f..2000f
private val TAU_DOWN_RANGE = 200f..5000f
private val OFFSET_RANGE = -1f..1f

/**
 * The five numbers the loop is made of, plus the curve they land on.
 *
 * Every commit goes through [PreferencesRepository][dev.nphil.luxramp.data.PreferencesRepository];
 * the controller observes the same flow, so a slider release retunes the running loop without the
 * UI ever touching it.
 */
@Composable
internal fun TuningCard(
    prefs: Prefs,
    onUpdate: ((Prefs) -> Prefs) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = stringResource(R.string.tuning_title), modifier = modifier) {
        SliderRow(
            label = stringResource(R.string.tuning_ramp_up),
            value = prefs.rampUpMillis.toFloat(),
            range = RAMP_UP_RANGE,
            format = { stringResource(R.string.value_millis, it.roundToLong()) },
            onCommit = { millis -> onUpdate { it.copy(rampUpMillis = millis.roundToLong()) } },
        )
        SliderRow(
            label = stringResource(R.string.tuning_ramp_down),
            value = prefs.rampDownMillis.toFloat(),
            range = RAMP_DOWN_RANGE,
            format = { stringResource(R.string.value_millis, it.roundToLong()) },
            onCommit = { millis -> onUpdate { it.copy(rampDownMillis = millis.roundToLong()) } },
        )
        SliderRow(
            label = stringResource(R.string.tuning_tau_up),
            value = prefs.tauUpMillis.toFloat(),
            range = TAU_UP_RANGE,
            format = { stringResource(R.string.value_millis, it.roundToLong()) },
            onCommit = { millis -> onUpdate { it.copy(tauUpMillis = millis.roundToLong()) } },
        )
        SliderRow(
            label = stringResource(R.string.tuning_tau_down),
            value = prefs.tauDownMillis.toFloat(),
            range = TAU_DOWN_RANGE,
            format = { stringResource(R.string.value_millis, it.roundToLong()) },
            onCommit = { millis -> onUpdate { it.copy(tauDownMillis = millis.roundToLong()) } },
        )
        SliderRow(
            label = stringResource(R.string.tuning_offset),
            value = prefs.offset,
            range = OFFSET_RANGE,
            format = { stringResource(R.string.value_offset, it) },
            onCommit = { offset -> onUpdate { it.copy(offset = offset) } },
        )
        CurvePreview(prefs.offset)
        Text(
            text = stringResource(R.string.tuning_curve_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A slider that reads from preferences but writes only on release.
 *
 * Dragging would otherwise put a DataStore commit — and a controller retune — behind every pixel.
 * The local value is re-seeded whenever the stored one changes, which is how the offset slider
 * follows along when the controller derives a new offset from the system brightness slider.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: @Composable (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    val local = remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = format(local.floatValue),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = local.floatValue,
            onValueChange = { local.floatValue = it },
            valueRange = range,
            onValueChangeFinished = { onCommit(local.floatValue) },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}
