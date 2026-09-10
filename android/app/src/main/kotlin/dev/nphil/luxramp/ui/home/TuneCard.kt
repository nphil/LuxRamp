package dev.nphil.luxramp.ui.home

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.nphil.luxramp.R
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.ui.LuxIcons
import dev.nphil.luxramp.ui.components.CardDivider
import dev.nphil.luxramp.ui.components.SectionCard
import dev.nphil.luxramp.ui.components.SectionHeader
import dev.nphil.luxramp.ui.components.SettingSlider
import kotlin.math.roundToLong

/** The values a fresh install starts from, and what "reset" means. */
private val DEFAULTS = Prefs()

private val RAMP_UP_RANGE = 100f..2000f
private val RAMP_DOWN_RANGE = 200f..4000f
private val TAU_UP_RANGE = 50f..2000f
private val TAU_DOWN_RANGE = 200f..5000f
private val OFFSET_RANGE = -1f..1f

/**
 * The five numbers the loop is made of.
 *
 * They are grouped by the question they answer rather than by the code they
 * configure: two are about how fast the screen moves, two are about how sure
 * LuxRamp has to be before it moves at all, and one is about you. Nobody
 * arrives here knowing what a time constant is, so every slider carries the
 * sentence that says which way to drag it.
 */
@Composable
internal fun TuneCard(
    prefs: Prefs,
    onUpdate: ((Prefs) -> Prefs) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // SettingSlider takes a plain formatter, not a composable one, so these are
    // hoisted: a fresh lambda per pass would defeat the slider's own skipping.
    val millis: (Float) -> String = remember(context) {
        { value -> context.getString(R.string.value_millis, value.roundToLong()) }
    }
    val signed: (Float) -> String = remember(context) {
        { value -> context.getString(R.string.value_offset, value) }
    }

    SectionCard(
        modifier = modifier,
        title = stringResource(R.string.tune_title),
        icon = LuxIcons.Sliders,
        trailing = {
            TextButton(
                onClick = {
                    // Only the loop's five numbers: the theme, the floating
                    // window and the master switch are not tuning, and finding
                    // them reset by a button in this card would be a surprise.
                    onUpdate {
                        it.copy(
                            rampUpMillis = DEFAULTS.rampUpMillis,
                            rampDownMillis = DEFAULTS.rampDownMillis,
                            tauUpMillis = DEFAULTS.tauUpMillis,
                            tauDownMillis = DEFAULTS.tauDownMillis,
                            offset = DEFAULTS.offset,
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.tune_reset))
            }
        },
        spacing = 6,
    ) {
        SectionHeader(
            title = stringResource(R.string.tune_group_speed),
            subtitle = stringResource(R.string.tune_group_speed_detail),
        )
        SettingSlider(
            label = stringResource(R.string.tune_ramp_up),
            value = prefs.rampUpMillis.toFloat(),
            range = RAMP_UP_RANGE,
            valueText = millis,
            explain = stringResource(R.string.tune_ramp_up_explain),
            onCommit = { value -> onUpdate { it.copy(rampUpMillis = value.roundToLong()) } },
        )
        SettingSlider(
            label = stringResource(R.string.tune_ramp_down),
            value = prefs.rampDownMillis.toFloat(),
            range = RAMP_DOWN_RANGE,
            valueText = millis,
            explain = stringResource(R.string.tune_ramp_down_explain),
            onCommit = { value -> onUpdate { it.copy(rampDownMillis = value.roundToLong()) } },
        )

        CardDivider()
        SectionHeader(
            title = stringResource(R.string.tune_group_steadiness),
            subtitle = stringResource(R.string.tune_group_steadiness_detail),
        )
        SettingSlider(
            label = stringResource(R.string.tune_tau_up),
            value = prefs.tauUpMillis.toFloat(),
            range = TAU_UP_RANGE,
            valueText = millis,
            explain = stringResource(R.string.tune_tau_up_explain),
            onCommit = { value -> onUpdate { it.copy(tauUpMillis = value.roundToLong()) } },
        )
        SettingSlider(
            label = stringResource(R.string.tune_tau_down),
            value = prefs.tauDownMillis.toFloat(),
            range = TAU_DOWN_RANGE,
            valueText = millis,
            explain = stringResource(R.string.tune_tau_down_explain),
            onCommit = { value -> onUpdate { it.copy(tauDownMillis = value.roundToLong()) } },
        )

        CardDivider()
        SectionHeader(
            title = stringResource(R.string.tune_group_preference),
            subtitle = stringResource(R.string.tune_group_preference_detail),
        )
        SettingSlider(
            label = stringResource(R.string.tune_offset),
            value = prefs.offset,
            range = OFFSET_RANGE,
            valueText = signed,
            explain = stringResource(R.string.tune_offset_explain),
            onCommit = { value -> onUpdate { it.copy(offset = value) } },
        )
    }
}
