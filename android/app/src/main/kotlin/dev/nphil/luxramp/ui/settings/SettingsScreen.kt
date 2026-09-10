package dev.nphil.luxramp.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nphil.luxramp.AppContainer
import dev.nphil.luxramp.R
import dev.nphil.luxramp.data.Prefs
import dev.nphil.luxramp.data.ThemeMode
import dev.nphil.luxramp.service.BrightnessService
import dev.nphil.luxramp.ui.LuxIcons
import dev.nphil.luxramp.ui.components.Explainer
import dev.nphil.luxramp.ui.components.SectionCard
import dev.nphil.luxramp.ui.components.SettingSlider
import dev.nphil.luxramp.ui.components.SettingToggle
import dev.nphil.luxramp.ui.onboarding.Grants
import dev.nphil.luxramp.ui.onboarding.PermissionRow
import dev.nphil.luxramp.ui.onboarding.SetupItem
import dev.nphil.luxramp.ui.onboarding.autostartIntent
import dev.nphil.luxramp.ui.onboarding.launchIfResolvable
import dev.nphil.luxramp.ui.onboarding.notificationChannelIntent
import dev.nphil.luxramp.ui.onboarding.pendingRequired
import dev.nphil.luxramp.ui.onboarding.readGrants
import dev.nphil.luxramp.ui.onboarding.request
import dev.nphil.luxramp.ui.theme.AppPalettes
import dev.nphil.luxramp.ui.theme.LocalIsDarkTheme
import dev.nphil.luxramp.ui.theme.MonoTextStyle
import dev.nphil.luxramp.ui.theme.ramp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The fade delay lands on quarter seconds: finer than that is a distinction nobody can perceive. */
private const val FADE_DELAY_STEP_MILLIS = 250L

/**
 * Everything that is not the loop itself.
 *
 * The caller owns the backdrop, so this draws no background of its own; it is a plain scrolling
 * column of cards over whatever wash the theme provides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    prefs: Prefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizuku by container.gateway.state.collectAsStateWithLifecycle()

    // Same polling contract as first run: a grant given on a settings screen shows up on resume or
    // not at all.
    var probe by remember { mutableIntStateOf(0) }
    val grants = remember(shizuku, probe) { readGrants(context, shizuku) }
    LifecycleResumeEffect(Unit) {
        container.gateway.refresh()
        probe++
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { probe++ }
    val askNotifications = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // A value rather than a local function: it is handed to the cards below, and a read-modify-write
    // of the whole record is what keeps a slider commit from clobbering an offset the controller
    // wrote a moment earlier.
    val commit: ((Prefs) -> Prefs) -> Unit = { transform ->
        scope.launch { container.prefs.update(transform) }
    }

    val percentFormat = stringResource(R.string.setup_value_percent)
    val secondsFormat = stringResource(R.string.setup_value_seconds)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LuxIcons.ArrowBack, contentDescription = stringResource(R.string.setup_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppearanceCard(prefs = prefs, onUpdate = commit)

            FloatingCard(
                prefs = prefs,
                canDrawOverlays = grants.canDrawOverlays,
                percentFormat = percentFormat,
                secondsFormat = secondsFormat,
                onUpdate = commit,
                onGrantOverlay = {
                    SetupItem.OVERLAY.request(context, grants, container.gateway, askNotifications)
                },
                onMiniEnabled = { on ->
                    // These own the preference as well as the service: they write miniEnabled and
                    // only then start, because the service decides what to run from the stored
                    // value. A second edit from here would race that ordering for no gain.
                    if (on) BrightnessService.showMini(context) else BrightnessService.hideMini(context)
                },
            )

            SectionCard(
                title = stringResource(R.string.setup_behaviour_title),
                icon = LuxIcons.Pulse,
            ) {
                SettingSlider(
                    label = stringResource(R.string.setup_deadband),
                    value = prefs.deadbandRatio,
                    range = 0.02f..0.30f,
                    valueText = { percentFormat.format((it * 100f).roundToInt()) },
                    explain = stringResource(R.string.setup_deadband_explain),
                    onCommit = { value -> commit { it.copy(deadbandRatio = value) } },
                )
            }

            PermissionsCard(
                grants = grants,
                onGrant = { item ->
                    item.request(context, grants, container.gateway, askNotifications)
                },
            )

            ExtrasCard()

            AboutCard()
        }
    }
}

@Composable
private fun AppearanceCard(prefs: Prefs, onUpdate: ((Prefs) -> Prefs) -> Unit) {
    SectionCard(
        title = stringResource(R.string.setup_appearance_title),
        icon = LuxIcons.Palette,
        spacing = 16,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.setup_theme_mode_label),
                style = MaterialTheme.typography.labelLarge,
            )
            ThemeModeRow(selected = prefs.themeMode, onSelect = { mode -> onUpdate { it.copy(themeMode = mode) } })
            Explainer(stringResource(R.string.setup_theme_mode_explain))
        }

        SettingToggle(
            label = stringResource(R.string.setup_material_you),
            explain = stringResource(R.string.setup_material_you_explain),
            checked = prefs.dynamicColor,
            onCheckedChange = { on -> onUpdate { it.copy(dynamicColor = on) } },
        )

        // Hidden rather than disabled while Material You is on: a picker whose choice is being
        // overridden is a control that lies about what it does.
        AnimatedVisibility(visible = !prefs.dynamicColor) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.setup_palettes_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                PalettePicker(
                    selectedId = prefs.themeId,
                    onPick = { id -> onUpdate { it.copy(themeId = id) } },
                )
                Explainer(stringResource(R.string.setup_palettes_explain))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val modes = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                icon = {
                    Icon(themeModeIcon(mode), contentDescription = null, modifier = Modifier.size(16.dp))
                },
                label = { Text(stringResource(themeModeLabel(mode))) },
            )
        }
    }
}

private fun themeModeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.SYSTEM -> LuxIcons.Gear
    ThemeMode.LIGHT -> LuxIcons.Sun
    ThemeMode.DARK -> LuxIcons.Moon
}

private fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.setup_theme_system
    ThemeMode.LIGHT -> R.string.setup_theme_light
    ThemeMode.DARK -> R.string.setup_theme_dark
}

/**
 * Every palette drawn as itself: its own background under its own three accents, in the mode the
 * device is in now. Recolouring the active theme for a preview would show every swatch as a lie.
 *
 * One Canvas per swatch rather than a clipped Box around three more. Twenty swatches built out of
 * layout nodes is eighty nodes and sixty clip layers on a page that is one long vertical scroll,
 * which is exactly the shape of screen that turns a palette picker into the slowest thing in an app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PalettePicker(
    selectedId: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalIsDarkTheme.current
    val ring = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current

    // Hoisted out of the draw scope: these never change, and allocating them per swatch per frame
    // would be sixty objects a frame for a picture that is completely static.
    val arcStroke = remember(density) { Stroke(width = with(density) { 5.dp.toPx() }) }
    val idleRing = remember(density) { Stroke(width = with(density) { 1.dp.toPx() }) }
    val pickedRing = remember(density) { Stroke(width = with(density) { 2.5.dp.toPx() }) }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppPalettes.forEach { palette ->
            val spec = if (dark) palette.dark else palette.light
            val selected = palette.id == selectedId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onPick(palette.id) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            ) {
                Canvas(Modifier.size(44.dp)) {
                    val radius = size.minDimension / 2f
                    val centre = Offset(radius, radius)
                    drawCircle(spec.background, radius = radius, center = centre)

                    // Three arcs of one circle, with a gap between them so the accents read as
                    // three colours rather than one banded ring.
                    val inset = arcStroke.width / 2f + 2f
                    val corner = Offset(inset, inset)
                    val span = (radius - inset) * 2f
                    val extent = Size(span, span)
                    drawArc(spec.primary, -88f, 112f, false, corner, extent, style = arcStroke)
                    drawArc(spec.secondary, 32f, 112f, false, corner, extent, style = arcStroke)
                    drawArc(spec.tertiary, 152f, 112f, false, corner, extent, style = arcStroke)

                    val outline = if (selected) pickedRing else idleRing
                    drawCircle(
                        color = if (selected) ring else idle,
                        radius = radius - outline.width / 2f,
                        center = centre,
                        style = outline,
                    )
                }
                Text(
                    text = palette.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) ring else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FloatingCard(
    prefs: Prefs,
    canDrawOverlays: Boolean,
    percentFormat: String,
    secondsFormat: String,
    onUpdate: ((Prefs) -> Prefs) -> Unit,
    onGrantOverlay: () -> Unit,
    onMiniEnabled: (Boolean) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.setup_floating_title),
        icon = LuxIcons.Window,
        spacing = 16,
    ) {
        SettingToggle(
            label = stringResource(R.string.setup_mini_enabled),
            explain = stringResource(R.string.setup_mini_enabled_explain),
            checked = prefs.miniEnabled,
            onCheckedChange = { on ->
                // Without the overlay grant the window cannot exist, so send the user to it and
                // leave the switch where it was. A switch that flips itself back is worse than one
                // that never moved.
                if (on && !canDrawOverlays) onGrantOverlay() else onMiniEnabled(on)
            },
        )

        if (!canDrawOverlays) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.setup_mini_needs_overlay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ramp.warning,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onGrantOverlay) {
                    Text(stringResource(R.string.setup_action_open))
                }
            }
        }

        SettingToggle(
            label = stringResource(R.string.setup_mini_fade),
            explain = stringResource(R.string.setup_mini_fade_explain),
            checked = prefs.miniFadeEnabled,
            onCheckedChange = { on -> onUpdate { it.copy(miniFadeEnabled = on) } },
        )

        SettingSlider(
            label = stringResource(R.string.setup_mini_delay),
            value = prefs.miniFadeDelayMillis.toFloat(),
            range = 1_000f..10_000f,
            valueText = { secondsFormat.format(it / 1_000f) },
            explain = stringResource(R.string.setup_mini_delay_explain),
            onCommit = { value ->
                val stepped = (value / FADE_DELAY_STEP_MILLIS).roundToInt() * FADE_DELAY_STEP_MILLIS
                onUpdate { it.copy(miniFadeDelayMillis = stepped) }
            },
            enabled = prefs.miniFadeEnabled,
        )

        SettingSlider(
            label = stringResource(R.string.setup_mini_alpha),
            value = prefs.miniIdleAlpha,
            range = 0.15f..1f,
            valueText = { percentFormat.format((it * 100f).roundToInt()) },
            explain = stringResource(R.string.setup_mini_alpha_explain),
            onCommit = { value -> onUpdate { it.copy(miniIdleAlpha = value) } },
            enabled = prefs.miniFadeEnabled,
        )
    }
}

/**
 * The same rows as first run, collapsed.
 *
 * Nobody opens Settings to read five permission explanations again, but the one person who does is
 * the person trying to work out what they gave the app, so the list stays available behind its
 * summary rather than disappearing once everything is granted.
 */
@Composable
private fun PermissionsCard(
    grants: Grants,
    onGrant: (SetupItem) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val outstanding = grants.pendingRequired()

    SectionCard(
        title = stringResource(R.string.setup_permissions_title),
        subtitle = if (outstanding == 0) {
            stringResource(R.string.setup_permissions_all_set)
        } else {
            pluralStringResource(R.plurals.setup_permissions_pending, outstanding, outstanding)
        },
        icon = if (outstanding == 0) LuxIcons.Check else LuxIcons.Warning,
        trailing = {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) LuxIcons.ChevronDown else LuxIcons.ChevronRight,
                    contentDescription = stringResource(
                        if (expanded) R.string.setup_action_hide else R.string.setup_action_show,
                    ),
                )
            }
        },
    ) {
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SetupItem.entries.forEach { item ->
                    PermissionRow(item = item, grants = grants, onGrant = { onGrant(item) })
                }
            }
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
private fun ExtrasCard() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    // Resolved once: the Security app either exists on this build or it does not.
    val hasAutostart = remember(context) {
        autostartIntent().resolveActivity(context.packageManager) != null
    }

    SectionCard(
        title = stringResource(R.string.setup_extras_title),
        icon = LuxIcons.Gear,
        trailing = {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.setup_action_hide else R.string.setup_action_show,
                    ),
                )
            }
        },
        spacing = 16,
    ) {
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Explainer(stringResource(R.string.setup_extras_explain))
                if (hasAutostart) {
                    ExtraRow(
                        title = stringResource(R.string.setup_extras_autostart),
                        explain = stringResource(R.string.setup_extras_autostart_explain),
                        onOpen = { launchIfResolvable(context, autostartIntent()) },
                    )
                }
                ExtraRow(
                    title = stringResource(R.string.setup_extras_hide_notification),
                    explain = stringResource(R.string.setup_extras_hide_notification_explain),
                    onOpen = { launchIfResolvable(context, notificationChannelIntent(context)) },
                )
            }
        }
    }
}

@Composable
private fun ExtraRow(title: String, explain: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Explainer(explain)
        }
        TextButton(onClick = onOpen) { Text(stringResource(R.string.setup_action_open)) }
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    // Read from the installed package rather than a generated constant, so it carries the debug
    // suffix and matches what the system settings page shows for this app.
    val version = remember(context) {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
                .versionName
        }.getOrNull().orEmpty()
    }

    SectionCard(title = stringResource(R.string.setup_about_title), icon = LuxIcons.Info, spacing = 6) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.setup_about_version, version),
            style = MonoTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Explainer(stringResource(R.string.setup_about_explain))
    }
}
