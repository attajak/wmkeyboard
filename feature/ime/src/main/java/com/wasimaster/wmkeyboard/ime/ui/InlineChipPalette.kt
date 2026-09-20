package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb

/**
 * The three colours an inline-suggestion chip is drawn with, as ARGB ints
 * because the other process wants framework colours, not Compose ones.
 *
 * A password manager renders its own chips; all the keyboard can do is name
 * the colours in the presentation spec and hope the renderer honours them.
 * These are the strip's own chip colours, so a saved login looks like the word
 * suggestions it sits among instead of a system-default pill dropped onto a
 * dark board (#250).
 */
data class InlineChipPalette(
    /** Fill behind the chip; [KbTheme.chip]. */
    val background: Int,
    /** The credential itself; [KbTheme.chipText]. */
    val title: Int,
    /** The manager's name under it; [KbTheme.secondaryText]. */
    val subtitle: Int,
)

/**
 * How the composition hands the resolved chip colours back to the service.
 *
 * The same shape as [SystemNavBarPainter] and for the same reason: the theme
 * is resolved in Compose — dynamic colour, the auto-theme slot, a photo
 * overlay and the accessibility pass all feed it — while the thing that needs
 * the answer is [InputMethodService.onCreateInlineSuggestionsRequest], which
 * runs on the service with no composition in reach.
 *
 * The default does nothing, which is what the settings app's theme previews
 * want: they draw a keyboard that is not the one typing.
 */
fun interface InlineChipPaletteReporter {
    /** @param palette the colours in force, or null when the board is gone. */
    fun report(palette: InlineChipPalette?)
}

/** The reporter the docked keyboard drives; see [InlineChipPaletteReporter]. */
val LocalInlineChipPaletteReporter = staticCompositionLocalOf { InlineChipPaletteReporter { } }

/**
 * Keeps the service's idea of the chip colours in step with [kb] for as long
 * as the keyboard is on screen.
 *
 * Reported rather than read on demand because of *when* the request is built:
 * autofill asks during onStartInput, before the board has drawn for this
 * field. The colours therefore come from the last time the keyboard was up,
 * which is the same theme unless the user changed it with the keyboard hidden
 * — and the first request of all, before the board has ever drawn, simply
 * carries no colours and gets the renderer's own defaults.
 */
@Composable
fun InlineChipPaletteReport(kb: KbTheme) {
    val reporter = LocalInlineChipPaletteReporter.current
    val palette = InlineChipPalette(
        background = kb.chip.toArgb(),
        title = kb.chipText.toArgb(),
        subtitle = kb.secondaryText.toArgb(),
    )
    // Two effects rather than one keyed DisposableEffect, as with the
    // navigation bar: a theme change would otherwise clear the colours and set
    // them again, and a request landing in between would go out bare.
    DisposableEffect(reporter) {
        onDispose { reporter.report(null) }
    }
    LaunchedEffect(reporter, palette) {
        reporter.report(palette)
    }
}
