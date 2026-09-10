package dev.nphil.luxramp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import dev.nphil.luxramp.data.ThemeMode

val LocalRampColors: ProvidableCompositionLocal<RampColors> =
    staticCompositionLocalOf { RampColors.Dark }

/** The meaning-bearing palette for the current light/dark state. */
val MaterialTheme.ramp: RampColors
    @Composable get() = LocalRampColors.current

/**
 * Gradient stops for the ambient backdrop every screen sits on, provided by the
 * theme so the wash is identical in the main window and in the floating one.
 */
val LocalBackdropColors: ProvidableCompositionLocal<List<Color>> =
    staticCompositionLocalOf { emptyList() }

/** True when the resolved theme is dark, regardless of what the system is doing. */
val LocalIsDarkTheme: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

@Composable
fun LuxRampTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    themeId: String = DEFAULT_PALETTE_ID,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current

    val scheme: ColorScheme = remember(dark, dynamicColor, themeId, context) {
        when {
            dynamicColor ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            else -> {
                val spec = paletteById(themeId).let { if (dark) it.dark else it.light }
                if (dark) spec.toDarkScheme() else spec.toLightScheme()
            }
        }
    }

    val backdrop: List<Color> = remember(scheme, dynamicColor, themeId, dark) {
        if (dynamicColor) {
            // Material You has no seed glows of its own; borrow the containers,
            // which carry the wallpaper's hue at a usable strength.
            listOf(
                blend(scheme.background, scheme.primaryContainer, if (dark) 0.35f else 0.55f),
                blend(scheme.background, scheme.tertiaryContainer, if (dark) 0.12f else 0.25f),
                scheme.background,
            )
        } else {
            backdropColors(paletteById(themeId).let { if (dark) it.dark else it.light }, dark)
        }
    }

    CompositionLocalProvider(
        LocalRampColors provides if (dark) RampColors.Dark else RampColors.Light,
        LocalBackdropColors provides backdrop,
        LocalIsDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = LuxRampTypography,
            shapes = LuxRampShapes,
            content = content,
        )
    }
}

private fun blend(a: Color, b: Color, t: Float): Color = lerp(a, b, t).copy(alpha = 1f)
