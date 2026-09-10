package dev.nphil.luxramp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * LuxRamp's icon set, drawn by hand as Compose vectors.
 *
 * `androidx.compose.material.icons` is not on this module's classpath, so there is
 * no `Icons.Default` and no `materialIcon {}` builder to lean on. Every glyph here
 * is a 24x24 stroke path built with `ImageVector.Builder` directly, geometric and
 * 2dp-equivalent stroke weight to read cleanly at the small sizes the mini window
 * and status pills use. Each vector is built once and cached in a `by lazy`
 * backing field: constructing an ImageVector is not free and these are referenced
 * on every recomposition of the screens that use them.
 */
object LuxIcons {

    /** Radians per degree, for the glyphs whose geometry is easier to state in degrees. */
    private const val DEGREES = PI / 180.0

    /** Builds a fresh 24x24 vector and lets `block` add one or more sub-paths to it. */
    private fun icon(name: String, block: Builder.() -> Unit): ImageVector =
        Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    /** House style for outline glyphs: no fill, round caps and joins, 2f stroke. */
    private fun Builder.strokePath(block: PathBuilder.() -> Unit) {
        addPath(
            pathData = PathData(block),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }

    /** Solid fill, used only where an outline would read as noise at 16dp. */
    private fun Builder.fillPath(block: PathBuilder.() -> Unit) {
        addPath(pathData = PathData(block), fill = SolidColor(Color.Black))
    }

    /** Convenience for the common case of a single stroked sub-path. */
    private fun strokeIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        icon(name) { strokePath(block) }

    /** Rounded rectangle as a path, vectors have no rect primitive. */
    private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        moveTo(x + r, y)
        lineTo(x + w - r, y)
        arcToRelative(r, r, 0f, false, true, r, r)
        lineTo(x + w, y + h - r)
        arcToRelative(r, r, 0f, false, true, -r, r)
        lineTo(x + r, y + h)
        arcToRelative(r, r, 0f, false, true, -r, -r)
        lineTo(x, y + r)
        arcToRelative(r, r, 0f, false, true, r, -r)
        close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcToRelative(r, r, 0f, true, false, r * 2, 0f)
        arcToRelative(r, r, 0f, true, false, -r * 2, 0f)
        close()
    }

    private fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) {
        moveTo(x1, y1); lineTo(x2, y2)
    }

    /** Filled disc, eight short rays. */
    val Sun: ImageVector by lazy {
        icon("sun") {
            fillPath { circle(12f, 12f, 4f) }
            strokePath {
                line(12f, 3f, 12f, 5f)
                line(12f, 19f, 12f, 21f)
                line(3f, 12f, 5f, 12f)
                line(19f, 12f, 21f, 12f)
                line(5.6f, 5.6f, 7f, 7f)
                line(17f, 17f, 18.4f, 18.4f)
                line(5.6f, 18.4f, 7f, 17f)
                line(17f, 7f, 18.4f, 5.6f)
            }
        }
    }

    /** Same disc, only the four cardinal rays, used where Sun would be too busy (sunlight boost dimmed). */
    val SunDim: ImageVector by lazy {
        icon("sun_dim") {
            fillPath { circle(12f, 12f, 4f) }
            strokePath {
                line(12f, 4.5f, 12f, 6f)
                line(12f, 18f, 12f, 19.5f)
                line(4.5f, 12f, 6f, 12f)
                line(18f, 12f, 19.5f, 12f)
            }
        }
    }

    val Moon: ImageVector by lazy {
        strokeIcon("moon") {
            moveTo(21f, 12.8f)
            arcToRelative(9f, 9f, 0f, true, true, -9.8f, -9.8f)
            arcToRelative(7f, 7f, 0f, false, false, 9.8f, 9.8f)
            close()
        }
    }

    /** Shizuku's instant write path, drawn as a lightning bolt. */
    val Bolt: ImageVector by lazy {
        strokeIcon("bolt") {
            moveTo(13f, 2f)
            lineTo(3f, 14f)
            lineTo(12f, 14f)
            lineTo(11f, 22f)
            lineTo(21f, 10f)
            lineTo(12f, 10f)
            close()
        }
    }

    /**
     * A ring, a hub and eight radial teeth. An alternating-radius polygon is the
     * usual trick, but round joins turn it into a flower at any size the app
     * actually draws it at, and a flower is not a settings button.
     */
    val Gear: ImageVector by lazy {
        strokeIcon("gear") {
            circle(12f, 12f, 6.4f)
            circle(12f, 12f, 2.6f)
            var tooth = 0
            while (tooth < 8) {
                val angle = tooth * 45.0 * DEGREES
                val cos = cos(angle).toFloat()
                val sin = sin(angle).toFloat()
                line(12f + 6.4f * cos, 12f + 6.4f * sin, 12f + 8.9f * cos, 12f + 8.9f * sin)
                tooth++
            }
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("check") { moveTo(20f, 6f); lineTo(9f, 17f); lineTo(4f, 12f) }
    }

    val Close: ImageVector by lazy {
        strokeIcon("close") { line(18f, 6f, 6f, 18f); line(6f, 6f, 18f, 18f) }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("chevron_right") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("chevron_down") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
    }

    val ArrowBack: ImageVector by lazy {
        strokeIcon("arrow_back") {
            line(19f, 12f, 5f, 12f)
            moveTo(12f, 19f); lineTo(5f, 12f); lineTo(12f, 5f)
        }
    }

    /** Filled, an outline triangle reads as a hollow arrowhead at 16dp. */
    val Play: ImageVector by lazy {
        icon("play") { fillPath { moveTo(6f, 4f); lineTo(19f, 12f); lineTo(6f, 20f); close() } }
    }

    /** Filled, matches Play's weight so the auto-control toggle does not flicker in perceived size. */
    val Stop: ImageVector by lazy {
        icon("stop") { fillPath { roundRect(5f, 5f, 14f, 14f, 2f) } }
    }

    /** Two columns of three filled dots, the drag handle for the mini window. */
    val Grip: ImageVector by lazy {
        icon("grip") {
            fillPath {
                circle(9f, 6f, 1.5f)
                circle(9f, 12f, 1.5f)
                circle(9f, 18f, 1.5f)
                circle(15f, 6f, 1.5f)
                circle(15f, 12f, 1.5f)
                circle(15f, 18f, 1.5f)
            }
        }
    }

    val Palette: ImageVector by lazy {
        strokeIcon("palette") {
            moveTo(12f, 2f)
            curveTo(6.5f, 2f, 2f, 6.5f, 2f, 12f)
            reflectiveCurveToRelative(4.5f, 10f, 10f, 10f)
            curveToRelative(0.9f, 0f, 1.6f, -0.7f, 1.6f, -1.7f)
            curveToRelative(0f, -0.4f, -0.2f, -0.8f, -0.4f, -1.1f)
            curveToRelative(-0.3f, -0.3f, -0.4f, -0.7f, -0.4f, -1.1f)
            arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, -1.6f)
            horizontalLineTo(16f)
            curveToRelative(3f, 0f, 5.5f, -2.5f, 5.5f, -5.6f)
            curveTo(22f, 6f, 17.5f, 2f, 12f, 2f)
            close()
        }
    }

    val Bell: ImageVector by lazy {
        strokeIcon("bell") {
            moveTo(18f, 8f)
            arcToRelative(6f, 6f, 0f, false, false, -12f, 0f)
            curveToRelative(0f, 7f, -3f, 9f, -3f, 9f)
            horizontalLineToRelative(18f)
            reflectiveCurveToRelative(-3f, -2f, -3f, -9f)
            moveTo(13.73f, 21f)
            arcToRelative(2f, 2f, 0f, false, true, -3.46f, 0f)
        }
    }

    /** Horizontal body with a small terminal nub, no fill bars: charge level lives in the copy, not the glyph. */
    val Battery: ImageVector by lazy {
        strokeIcon("battery") {
            // Body stops at 19 so the terminal has room: at x = 22 the round cap
            // spills past the viewport and the nub disappears.
            roundRect(2f, 6f, 17f, 12f, 2f)
            line(21.5f, 10f, 21.5f, 14f)
        }
    }

    /** Three horizontal tracks with offset handles, one per tunable parameter. */
    val Sliders: ImageVector by lazy {
        icon("sliders") {
            strokePath {
                line(4f, 6f, 20f, 6f)
                line(4f, 12f, 20f, 12f)
                line(4f, 18f, 20f, 18f)
            }
            fillPath {
                circle(9f, 6f, 1.8f)
                circle(16f, 12f, 1.8f)
                circle(7f, 18f, 1.8f)
            }
        }
    }

    /** ECG style polyline with a filled dot marking the live edge, used for the telemetry card. */
    val Pulse: ImageVector by lazy {
        icon("pulse") {
            strokePath {
                moveTo(2f, 12f)
                lineTo(6f, 12f)
                lineTo(9f, 4f)
                lineTo(12f, 20f)
                lineTo(14f, 12f)
                lineTo(18f, 12f)
            }
            fillPath { circle(20f, 12f, 2f) }
        }
    }

    /** Rounded window with a title bar and an inset panel, the floating mini window. */
    val Window: ImageVector by lazy {
        strokeIcon("window") {
            roundRect(3f, 4f, 18f, 16f, 2f)
            line(3f, 9f, 21f, 9f)
            roundRect(13f, 12f, 6f, 6f, 1f)
        }
    }

    val Info: ImageVector by lazy {
        strokeIcon("info") {
            circle(12f, 12f, 9f)
            line(12f, 11f, 12f, 16f)
            line(12f, 8f, 12.01f, 8f)
        }
    }

    /** IEC power glyph, a broken ring with a vertical stem entering the gap. */
    val Power: ImageVector by lazy {
        strokeIcon("power") {
            moveTo(18.36f, 6.64f)
            arcToRelative(9f, 9f, 0f, true, true, -12.73f, 0f)
            moveTo(12f, 2f); lineTo(12f, 12f)
        }
    }

    val Warning: ImageVector by lazy {
        strokeIcon("warning") {
            moveTo(10.29f, 3.86f)
            lineTo(1.82f, 18f)
            arcToRelative(2f, 2f, 0f, false, false, 1.71f, 3f)
            horizontalLineToRelative(16.94f)
            arcToRelative(2f, 2f, 0f, false, false, 1.71f, -3f)
            lineTo(13.71f, 3.86f)
            arcToRelative(2f, 2f, 0f, false, false, -3.42f, 0f)
            close()
            line(12f, 9f, 12f, 13f)
            line(12f, 17f, 12.01f, 17f)
        }
    }

    /**
     * Two chevrons folding in. The gap between them is deliberately wide: tips that
     * nearly meet read as one diamond at 14dp, and a diamond says nothing, while an
     * X is the close button sitting right beside this one.
     */
    val Collapse: ImageVector by lazy {
        strokeIcon("collapse") {
            moveTo(8f, 6f); lineTo(12f, 10f); lineTo(16f, 6f)
            moveTo(8f, 18f); lineTo(12f, 14f); lineTo(16f, 18f)
        }
    }

    /** Two chevrons opening out, mirrored from [Collapse] so the pair reads as one control. */
    val Expand: ImageVector by lazy {
        strokeIcon("expand") {
            moveTo(8f, 10f); lineTo(12f, 6f); lineTo(16f, 10f)
            moveTo(8f, 14f); lineTo(12f, 18f); lineTo(16f, 14f)
        }
    }
}
