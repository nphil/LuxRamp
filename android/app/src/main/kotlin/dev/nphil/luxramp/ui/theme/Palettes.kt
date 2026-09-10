package dev.nphil.luxramp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/*
 * The theme catalog: twenty hand-tuned palettes, each with a dark and a light
 * variant, sitting beside Material You rather than replacing it.
 *
 * A palette is six seed colours per variant. Everything else is DERIVED from
 * those seeds by one builder: the full Material 3 scheme with its five
 * surface-container steps, the container pairs, the outlines. That is what
 * makes twenty themes honest instead of twenty accent colours: the background,
 * every card, every navigation surface and every outline is mixed from the
 * theme's own hues, so switching themes visibly re-tints the entire UI, and a
 * new theme can never forget to style a surface.
 *
 * The catalog is the same one HomeLabber ships, by hex and by name, so the
 * apps on this tablet look like they came from the same place.
 */

@Immutable
data class PaletteSpec(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    /** Two glow hues for the ambient backdrop wash. */
    val glowA: Color,
    val glowB: Color
)

@Immutable
data class AppPalette(
    val id: String,
    val label: String,
    val dark: PaletteSpec,
    val light: PaletteSpec
)

private fun Color.mix(other: Color, t: Float): Color = lerp(this, other, t)

/**
 * Dark variant: near-black tinted background, luminous accents.
 *
 * Surfaces ladder from the background toward white so cards separate by
 * elevation the way stock M3 does, but through the theme's own tint, because
 * the background itself carries the hue.
 */
internal fun PaletteSpec.toDarkScheme(): ColorScheme {
    val bg = background
    val onBg = Color.White.mix(primary, 0.06f)
    val onVariant = Color.White.mix(bg, 0.32f)
    return darkColorScheme(
        primary = primary,
        onPrimary = primary.mix(Color.Black, 0.78f),
        primaryContainer = primary.mix(bg, 0.62f),
        onPrimaryContainer = primary.mix(Color.White, 0.72f),
        secondary = secondary,
        onSecondary = secondary.mix(Color.Black, 0.78f),
        secondaryContainer = secondary.mix(bg, 0.66f),
        onSecondaryContainer = secondary.mix(Color.White, 0.72f),
        tertiary = tertiary,
        onTertiary = tertiary.mix(Color.Black, 0.78f),
        tertiaryContainer = tertiary.mix(bg, 0.66f),
        onTertiaryContainer = tertiary.mix(Color.White, 0.72f),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = bg,
        onBackground = onBg,
        surface = bg,
        onSurface = onBg,
        surfaceVariant = bg.mix(Color.White, 0.10f),
        onSurfaceVariant = onVariant,
        surfaceContainerLowest = bg.mix(Color.Black, 0.35f),
        surfaceContainerLow = bg.mix(Color.White, 0.035f),
        surfaceContainer = bg.mix(Color.White, 0.055f),
        surfaceContainerHigh = bg.mix(Color.White, 0.08f),
        surfaceContainerHighest = bg.mix(Color.White, 0.11f),
        outline = Color.White.mix(bg, 0.62f),
        outlineVariant = Color.White.mix(bg, 0.84f),
        inverseSurface = onBg,
        inverseOnSurface = bg.mix(Color.White, 0.05f),
        inversePrimary = primary.mix(Color.Black, 0.45f),
        scrim = Color.Black
    )
}

/**
 * Light variant: pastel-tinted near-white background, ink-dark accents.
 * The container ladder runs from white down through the tinted background so
 * light themes are visibly coloured too, not white-with-an-accent.
 */
internal fun PaletteSpec.toLightScheme(): ColorScheme {
    val bg = background
    val onBg = Color.Black.mix(primary, 0.12f)
    val onVariant = Color.Black.mix(bg, 0.42f)
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = primary.mix(Color.White, 0.85f),
        onPrimaryContainer = primary.mix(Color.Black, 0.45f),
        secondary = secondary,
        onSecondary = Color.White,
        secondaryContainer = secondary.mix(Color.White, 0.86f),
        onSecondaryContainer = secondary.mix(Color.Black, 0.45f),
        tertiary = tertiary,
        onTertiary = Color.White,
        tertiaryContainer = tertiary.mix(Color.White, 0.86f),
        onTertiaryContainer = tertiary.mix(Color.Black, 0.45f),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = bg,
        onBackground = onBg,
        surface = bg,
        onSurface = onBg,
        surfaceVariant = bg.mix(primary, 0.08f).mix(Color.Black, 0.04f),
        onSurfaceVariant = onVariant,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color.White.mix(bg, 0.45f),
        surfaceContainer = bg.mix(Color.Black, 0.015f),
        surfaceContainerHigh = bg.mix(Color.Black, 0.035f).mix(primary, 0.02f),
        surfaceContainerHighest = bg.mix(Color.Black, 0.055f).mix(primary, 0.03f),
        outline = Color.Black.mix(bg, 0.55f),
        outlineVariant = Color.Black.mix(bg, 0.82f),
        inverseSurface = onBg,
        inverseOnSurface = bg,
        inversePrimary = primary.mix(Color.White, 0.6f),
        scrim = Color.Black
    )
}

private fun spec(
    primary: Long, secondary: Long, tertiary: Long,
    background: Long, glowA: Long, glowB: Long
) = PaletteSpec(
    Color(primary), Color(secondary), Color(tertiary),
    Color(background), Color(glowA), Color(glowB)
)

/**
 * The catalog. Order is a hue walk, violets → reds → blues → teals → greens
 * → warms → neutrals, so the picker grid reads as a spectrum.
 */
val AppPalettes: List<AppPalette> = listOf(
    AppPalette(
        "nebula", "Nebula",
        dark = spec(0xFF9B8CFF, 0xFFC4A0FF, 0xFFF0A8E4, 0xFF0B0A12, 0xFF7C3AED, 0xFFA855F7),
        light = spec(0xFF5B4BD6, 0xFF7C3AED, 0xFF9A34D6, 0xFFF7F5FC, 0xFFB08AF5, 0xFFD6A5F5)
    ),
    AppPalette(
        "iris", "Iris",
        dark = spec(0xFF8FA8FF, 0xFFB0C0FF, 0xFF7BE0D6, 0xFF090A14, 0xFF4A5FD6, 0xFF8FA8FF),
        light = spec(0xFF3A4CC0, 0xFF2A6BA8, 0xFF0E8B7E, 0xFFF4F6FD, 0xFFA5B4F5, 0xFFC0CCF8)
    ),
    AppPalette(
        "synthwave", "Synthwave",
        dark = spec(0xFFFF5CB8, 0xFF4FE0F0, 0xFFB98AFF, 0xFF0C0714, 0xFFB02DC4, 0xFFFF5CB8),
        light = spec(0xFFA8188A, 0xFF0C6DA8, 0xFF6A2FD0, 0xFFFAF4FB, 0xFFE59AD9, 0xFFC9A5F0)
    ),
    AppPalette(
        "rose", "Rosé",
        dark = spec(0xFFFF8FAB, 0xFFFFB39B, 0xFFE8A0FF, 0xFF100A0D, 0xFFD6567E, 0xFFFFA3B8),
        light = spec(0xFFB02452, 0xFFB5502E, 0xFF9A2FA8, 0xFFFCF5F7, 0xFFF5AFC4, 0xFFF7C3B8)
    ),
    AppPalette(
        "flamingo", "Flamingo",
        dark = spec(0xFFFF8A7A, 0xFFFFB36B, 0xFFFF9CC8, 0xFF120808, 0xFFE05A48, 0xFFFF9C8A),
        light = spec(0xFFC03D2A, 0xFFB5651D, 0xFFB02468, 0xFFFCF6F4, 0xFFF5A898, 0xFFF7C3A5)
    ),
    AppPalette(
        "crimson", "Crimson",
        dark = spec(0xFFFF7585, 0xFFFF9C8A, 0xFFE86BA8, 0xFF0F0608, 0xFFC42438, 0xFFFF5C68),
        light = spec(0xFFAD1F3C, 0xFFA8422A, 0xFF8A2FA0, 0xFFFBF4F5, 0xFFF098A3, 0xFFF5AFA5)
    ),
    AppPalette(
        "sunset", "Sunset",
        dark = spec(0xFFFF8A6B, 0xFFFF6FA5, 0xFFA98AFF, 0xFF100810, 0xFFE85A8A, 0xFF8A5CFF),
        light = spec(0xFFB5432A, 0xFFB02468, 0xFF6A3FD0, 0xFFFCF5F4, 0xFFF5A0B8, 0xFFC9AFF5)
    ),
    AppPalette(
        "midnight", "Midnight",
        dark = spec(0xFF5CA8FF, 0xFF7BC4FF, 0xFF9FB8FF, 0xFF05070F, 0xFF2450D6, 0xFF4D9DFF),
        light = spec(0xFF1D3FC0, 0xFF0C5CA8, 0xFF4A44C8, 0xFFF3F6FC, 0xFF93AFF0, 0xFFA5C6F5)
    ),
    AppPalette(
        "signal", "Signal",
        dark = spec(0xFF63C8FF, 0xFF7FE3E0, 0xFFA8C8FF, 0xFF070B10, 0xFF1B87C4, 0xFF63C8FF),
        light = spec(0xFF0C6DA8, 0xFF0E8B86, 0xFF3A6FC4, 0xFFF4F8FB, 0xFF8FC9EC, 0xFFA8DCE8)
    ),
    AppPalette(
        "storm", "Storm",
        dark = spec(0xFF7BD0FF, 0xFFA8B8C8, 0xFFD0E8FF, 0xFF070A0D, 0xFF3A6A8A, 0xFF7BD0FF),
        light = spec(0xFF0F5C8C, 0xFF4A5A68, 0xFF2A7A9A, 0xFFF3F7FA, 0xFF9CC8E8, 0xFFC0D8E8)
    ),
    AppPalette(
        "frost", "Frost",
        dark = spec(0xFFA8D2E8, 0xFFC8E0F0, 0xFF8FB8D6, 0xFF080B0E, 0xFF5E8AA8, 0xFFBCD9E8),
        light = spec(0xFF18628F, 0xFF3E5A78, 0xFF0E8B86, 0xFFF5F9FC, 0xFFB8D9EE, 0xFFCDE4F2)
    ),
    AppPalette(
        "lagoon", "Lagoon",
        dark = spec(0xFF4FE0D0, 0xFF7BE8B8, 0xFF66C8F0, 0xFF051010, 0xFF18A8A0, 0xFF5CE8D8),
        light = spec(0xFF0B6E68, 0xFF0C7A52, 0xFF0C6DA8, 0xFFF1F9F8, 0xFF8ADBD2, 0xFFA5E5D0)
    ),
    AppPalette(
        "aurora", "Aurora",
        dark = spec(0xFF49E0B0, 0xFF8F7BFF, 0xFF6FD9E8, 0xFF060B10, 0xFF28BD90, 0xFF8F7BFF),
        light = spec(0xFF0B7A62, 0xFF4A3FC4, 0xFF0C6DA8, 0xFFF2F9F7, 0xFF8FE3C8, 0xFFA5D9F0)
    ),
    AppPalette(
        "moss", "Moss",
        dark = spec(0xFF9CC48E, 0xFFC4D69A, 0xFF7AB89C, 0xFF090D08, 0xFF5A8A4E, 0xFFA8C89A),
        light = spec(0xFF3E6B2E, 0xFF2E6B52, 0xFF6B6B1E, 0xFFF5F8F2, 0xFFAECFA0, 0xFFC6DDB4)
    ),
    AppPalette(
        "terminal", "Terminal",
        dark = spec(0xFF5CE68A, 0xFF9BE86B, 0xFFE8D36B, 0xFF060A07, 0xFF1E9E5A, 0xFF5CE68A),
        light = spec(0xFF0E7A42, 0xFF3F8F1E, 0xFF8A7A11, 0xFFF4F8F5, 0xFF7ED9A4, 0xFFA8E58C)
    ),
    AppPalette(
        "solar", "Solar",
        dark = spec(0xFFFFD25C, 0xFFFFB84A, 0xFFF0E48A, 0xFF0E0B04, 0xFFD6A21F, 0xFFFFDE6B),
        light = spec(0xFF8F6A00, 0xFFA85A0A, 0xFF6F7A0E, 0xFFFDF9EF, 0xFFF0CE7A, 0xFFF5DE9A)
    ),
    AppPalette(
        "ember", "Ember",
        dark = spec(0xFFFFA24A, 0xFFFF7A5C, 0xFFFFD07A, 0xFF0F0906, 0xFFC4551F, 0xFFFFA24A),
        light = spec(0xFFB35A0E, 0xFFC0432B, 0xFF9A6A12, 0xFFFCF7F3, 0xFFF0B98A, 0xFFF5A88F)
    ),
    AppPalette(
        "mocha", "Mocha",
        dark = spec(0xFFD9B08C, 0xFFC49A6E, 0xFFE8CBA8, 0xFF0D0906, 0xFF8A6242, 0xFFD6AE8A),
        light = spec(0xFF6E4A28, 0xFF8A5A1E, 0xFF5E5240, 0xFFFAF6F1, 0xFFD9BC9E, 0xFFE5CDB4)
    ),
    AppPalette(
        "slate", "Slate",
        dark = spec(0xFFD6D9E0, 0xFFAEB3BE, 0xFF878D9A, 0xFF0A0B0D, 0xFF555C69, 0xFF9AA1AE),
        light = spec(0xFF353A44, 0xFF565C68, 0xFF787E8A, 0xFFF6F7F9, 0xFFC5C9D1, 0xFFD6D9E0)
    ),
    AppPalette(
        "mono", "Mono",
        dark = spec(0xFFEDEDED, 0xFFB8B8B8, 0xFF8F8F8F, 0xFF000000, 0xFF4A4A4A, 0xFF8A8A8A),
        light = spec(0xFF111111, 0xFF3D3D3D, 0xFF6E6E6E, 0xFFFFFFFF, 0xFFD9D9D9, 0xFFE8E8E8)
    )
)

const val DEFAULT_PALETTE_ID = "nebula"

fun paletteById(id: String): AppPalette =
    AppPalettes.firstOrNull { it.id == id } ?: AppPalettes.first()

/**
 * The ambient backdrop wash for a variant: a vertical gradient of the theme's
 * glow hues over its background. Subtle by design, enough for the theme to
 * own the whole screen, not enough to fight content. One linear gradient is a
 * single GPU fill; it costs nothing at 120 Hz.
 */
fun backdropColors(spec: PaletteSpec, dark: Boolean): List<Color> {
    val bg = spec.background
    return if (dark) listOf(
        bg.mix(spec.glowA, 0.16f),
        bg.mix(spec.glowB, 0.05f),
        bg
    ) else listOf(
        bg.mix(spec.glowA, 0.28f),
        bg.mix(spec.glowB, 0.12f),
        bg
    )
}
