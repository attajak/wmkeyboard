package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Key glyphs neither Material icon set has, drawn by hand in the Material
 * Symbols outlined style. The shift arrows used to live here too; Symbols has
 * them now (`SymbolIcons.Shift` and friends).
 */
object KeyboardIcons {

    private const val VIEWPORT = 24f

    /**
     * ⌦ — backspace pointing the other way, for the forward-delete key. The
     * material set has no forward-delete glyph, and mirroring its backspace
     * with an RTL modifier was not an option: the icon is auto-mirrored, so it
     * would flip back the moment the layout direction did.
     */
    val ForwardDelete: ImageVector by lazy {
        ImageVector.Builder(
            name = "ForwardDelete",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            // Body: a rounded slab tapering to a point on the leading edge.
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.2f, 5.2f)
                horizontalLineTo(15.4f)
                lineTo(21.2f, 12f)
                lineTo(15.4f, 18.8f)
                horizontalLineTo(3.2f)
                close()
            }
            // The × inside, centred in the square part of the slab.
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6.8f, 9.2f)
                lineTo(12.4f, 14.8f)
                moveTo(12.4f, 9.2f)
                lineTo(6.8f, 14.8f)
            }
        }.build()
    }

    /**
     * Incognito glyph (hat + glasses), for the toolbar indicator — the
     * material set has no incognito icon and the 🕶 emoji ignored theming.
     */
    val Incognito: ImageVector by lazy {
        ImageVector.Builder(
            name = "Incognito",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            // Hat crown.
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.1f, 9.4f)
                lineTo(9.1f, 4.6f)
                quadTo(9.3f, 3.8f, 10.1f, 3.8f)
                horizontalLineTo(13.9f)
                quadTo(14.7f, 3.8f, 14.9f, 4.6f)
                lineTo(15.9f, 9.4f)
                close()
            }
            // Brim.
            path(fill = SolidColor(Color.Black)) {
                moveTo(3.6f, 10.4f)
                horizontalLineTo(20.4f)
                verticalLineTo(11.9f)
                horizontalLineTo(3.6f)
                close()
            }
            // Glasses: two lenses joined by a short bridge.
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(10.6f, 16.9f)
                arcTo(2.55f, 2.55f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.59f, 16.85f)
                moveTo(18.7f, 16.9f)
                arcTo(2.55f, 2.55f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18.69f, 16.85f)
                moveTo(10.8f, 16.3f)
                quadTo(12f, 15.6f, 13.2f, 16.3f)
            }
        }.build()
    }
}
