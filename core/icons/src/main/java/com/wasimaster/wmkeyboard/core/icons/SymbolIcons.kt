package com.wasimaster.wmkeyboard.core.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Glyphs from Google's Material Symbols that the bundled material-icons set
 * never got. `material-icons-extended` is the older Material Icons family and
 * is frozen; Symbols kept growing, and it is the only place a real sticker
 * glyph lives (the old set's nearest was a sticky note with text lines on it).
 *
 * Path data is copied verbatim from the Symbols "Rounded" style at weight 400,
 * fill 0, optical size 24 — the same defaults fonts.google.com serves. Symbols
 * draw in a 960-unit box whose origin sits at the *bottom* left (viewBox
 * `0 -960 960 960`), so each path rides in a group shifted down by 960.
 *
 * Built in black like `KeyboardIcons`, so `Icon(tint = …)` recolours them.
 * Material Symbols is Apache 2.0, the same licence as the icons already bundled.
 */
object SymbolIcons {

    /** `sticker`: a square face with its corner peeling up — Gboard's sticker tab. */
    val Sticker: ImageVector by lazy {
        symbol(
            "Sticker",
            """M460-360q64 0 113.5-39.5T637-500q2-6-3-10.5t-11-2.5l-282 79q-8 2-9.5 9.5T335-412q25 24 57 38t68 14ZM294-510l106-30q4-28-14-49t-46-21q-25 0-42.5 17.5T280-550q0 11 4 21t10 19Zm240-70 106-30q5-28-13.5-49T580-680q-25 0-42.5 17.5T520-620q0 11 4 21t10 19ZM200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v407q0 16-6 30.5T817-297L663-143q-11 11-25.5 17t-30.5 6H200Zm400-80v-80q0-33 23.5-56.5T680-360h80v-400H200v560h400Zm0 0Zm-400 0v-560 560Z""",
        )
    }

    /** `sticker_add`: the sticker with a plus, for making or importing a pack. */
    val StickerAdd: ImageVector by lazy {
        symbol(
            "StickerAdd",
            """m294-510 106-30q4-28-14-49t-46-21q-25 0-42.5 17.5T280-550q0 11 4 21t10 19Zm240-70 106-30q5-28-13.5-49T580-680q-25 0-42.5 17.5T520-620q0 11 4 21t10 19Zm-54 100ZM200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h360q17 0 28.5 11.5T600-800q0 17-11.5 28.5T560-760H200v560h400v-80q0-33 23.5-56.5T680-360h80v-200q0-17 11.5-28.5T800-600q17 0 28.5 11.5T840-560v207q0 16-6 30.5T817-297L663-143q-11 11-25.5 17t-30.5 6H200Zm400-80ZM460-360q64 0 113.5-39.5T637-500q2-6-3-10.5t-11-2.5l-282 79q-8 2-9.5 9.5T335-412q25 24 57 38t68 14Zm300-400h-40q-17 0-28.5-11.5T680-800q0-17 11.5-28.5T720-840h40v-40q0-17 11.5-28.5T800-920q17 0 28.5 11.5T840-880v40h40q17 0 28.5 11.5T920-800q0 17-11.5 28.5T880-760h-40v40q0 17-11.5 28.5T800-680q-17 0-28.5-11.5T760-720v-40Z""",
        )
    }

    /**
     * `gif_box`, redrawn: the old set's version has a heavy frame round thin
     * square-ended letters; this one rounds the letters to match the frame.
     */
    val GifBox: ImageVector by lazy {
        symbol(
            "GifBox",
            """M491.5-368.5Q500-377 500-390v-180q0-13-8.5-21.5T470-600q-13 0-21.5 8.5T440-570v180q0 13 8.5 21.5T470-360q13 0 21.5-8.5ZM280-360h80q17 0 28.5-11.5T400-400v-50q0-13-8.5-21.5T370-480q-13 0-21.5 8.5T340-450v30h-40v-120h70q13 0 21.5-8.5T400-570q0-13-8.5-21.5T370-600h-90q-17 0-28.5 11.5T240-560v160q0 17 11.5 28.5T280-360Zm331.5-8.5Q620-377 620-390v-50h50q13 0 21.5-8.5T700-470q0-13-8.5-21.5T670-500h-50v-40h90q13 0 21.5-8.5T740-570q0-13-8.5-21.5T710-600H590q-13 0-21.5 8.5T560-570v180q0 13 8.5 21.5T590-360q13 0 21.5-8.5ZM200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H200Zm0-80h560v-560H200v560Zm0 0v-560 560Z""",
        )
    }

    /** `animated_images`: a stack of frames with a play mark — moving media in general. */
    val AnimatedImages: ImageVector by lazy {
        symbol(
            "AnimatedImages",
            """m542-461 128-86q18-12 18-33t-18-33l-128-86q-20-14-41-2t-21 36v170q0 24 21 36t41-2ZM360-280q-33 0-56.5-23.5T280-360v-440q0-33 23.5-56.5T360-880h440q33 0 56.5 23.5T880-800v440q0 33-23.5 56.5T800-280H360Zm0-80h440v-440H360v440Zm220-220ZM218-164Zm10 79q-33 4-59.5-16T138-154L85-591q-4-33 17-59t54-31h2q17-3 30.5 9t13.5 30q0 15-10.5 26T166-602h-1l54 438 474-58q17-2 30 8t15 27q2 17-8 29.5T703-143L228-85Z""",
        )
    }

    private const val SYMBOL_BOX = 960f

    private fun symbol(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = SYMBOL_BOX,
            viewportHeight = SYMBOL_BOX,
        )
            .addGroup(translationY = SYMBOL_BOX)
            .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .clearGroup()
            .build()
}
