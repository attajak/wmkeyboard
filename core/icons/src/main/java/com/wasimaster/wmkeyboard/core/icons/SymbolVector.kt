package com.wasimaster.wmkeyboard.core.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols draw in a 960-unit box. */
private const val SYMBOL_BOX = 960f

/**
 * One Material Symbols glyph as a 24 dp [ImageVector], for the generated icons
 * in [SymbolIcons] and the `symbols` package (see `scripts/gen_symbol_icons.py`).
 *
 * Symbols put their origin at the *bottom* left (viewBox `0 -960 960 960`), so
 * the path rides in a group shifted down by the box height. Built in black like
 * `KeyboardIcons`, so `Icon(tint = …)` recolours it.
 */
internal fun symbolVector(name: String, pathData: String, autoMirror: Boolean): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = SYMBOL_BOX,
        viewportHeight = SYMBOL_BOX,
        autoMirror = autoMirror,
    )
        .addGroup(translationY = SYMBOL_BOX)
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .clearGroup()
        .build()
