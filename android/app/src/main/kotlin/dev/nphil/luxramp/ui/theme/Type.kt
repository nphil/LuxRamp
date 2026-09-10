package dev.nphil.luxramp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material 3's type scale with the same two departures HomeLabber makes on this
 * tablet: display sizes dialled back from billboard scale, and every style
 * pinned to a [LineHeightStyle] so rows of numbers align on their optical
 * baseline instead of drifting a pixel per row as digits change.
 */
private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun TextStyle.tuned() = copy(lineHeightStyle = Trim)

private val Default = Typography()

val LuxRampTypography = Typography(
    displayLarge = Default.displayLarge.copy(fontSize = 45.sp, lineHeight = 52.sp).tuned(),
    displayMedium = Default.displayMedium.copy(fontSize = 36.sp, lineHeight = 44.sp).tuned(),
    displaySmall = Default.displaySmall.copy(fontSize = 32.sp, lineHeight = 40.sp).tuned(),
    headlineLarge = Default.headlineLarge.copy(fontWeight = FontWeight.SemiBold).tuned(),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold).tuned(),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold).tuned(),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold).tuned(),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold).tuned(),
    titleSmall = Default.titleSmall.tuned(),
    bodyLarge = Default.bodyLarge.tuned(),
    bodyMedium = Default.bodyMedium.tuned(),
    bodySmall = Default.bodySmall.tuned(),
    labelLarge = Default.labelLarge.copy(letterSpacing = 0.2.sp).tuned(),
    labelMedium = Default.labelMedium.tuned(),
    labelSmall = Default.labelSmall.tuned(),
)

/**
 * Live readouts: lux, percentages, milliseconds.
 *
 * These update several times a second, and a proportional font would make the
 * whole row twitch as the digit widths change. Tabular monospace holds still.
 */
val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    lineHeightStyle = Trim,
)

/** The big number on the master card, in the same non-twitching family. */
val MonoDisplayStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 44.sp,
    lineHeight = 48.sp,
    fontWeight = FontWeight.Medium,
    lineHeightStyle = Trim,
)

/** Rounder than stock M3: this app is cards on a tinted backdrop, and the extra radius separates them without a border. */
val LuxRampShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)
