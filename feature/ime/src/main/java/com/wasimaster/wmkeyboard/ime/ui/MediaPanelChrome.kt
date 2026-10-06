package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.wasimaster.wmkeyboard.ime.KeyboardUiState

/**
 * The GIF and sticker panels' search box and chip rows, slid out of the way
 * while the results scroll down and brought back when they scroll up, when
 * the tool's setting asks for it (`GifSettings.hideHeaderOnScroll`).
 *
 * One per opening of the panel, held by [MediaPanelHost] and reached through
 * [LocalMediaChrome], because the two halves it hides live apart: the
 * full-bleed header with the search box belongs to the host, the chip rows to
 * the browser in its layout cell. The panel's height never changes for it;
 * whatever the header gives up goes to the results under it.
 *
 * [connection] watches the results' scroll: what the list actually moved,
 * never a drag on a list too short to move, so a handful of results cannot
 * lose their search box. A pull down at the top of the list, which moves
 * nothing, still brings it back. [slopPx] of travel in one direction before
 * either, so a tap that wobbles does neither.
 */
@Stable
internal class MediaChrome(private val slopPx: Float) {
    /** Whether the scroll has put the header away; see [collapsed] for whether it is. */
    var hidden by mutableStateOf(false)

    private var travel = 0f

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y + available.y.coerceAtLeast(0f)
            if (dy == 0f) return Offset.Zero
            if ((dy < 0f) != (travel < 0f)) travel = 0f
            travel += dy
            if (travel < -slopPx && !hidden) {
                hidden = true
            } else if (travel > slopPx && hidden) {
                hidden = false
            }
            return Offset.Zero
        }
    }

    /**
     * Whether the header is put away right now: only with the setting on, and
     * never while a search has the keys, when the box is the thing being typed in.
     * [hidden] is read last, so with the setting off a scroll recomposes nothing.
     */
    fun collapsed(state: KeyboardUiState): Boolean =
        state.settings.gif.hideHeaderOnScroll && !state.mediaSearchActive && hidden
}

/** The open GIF or sticker panel's [MediaChrome]; null anywhere else. */
internal val LocalMediaChrome = compositionLocalOf<MediaChrome?> { null }

/** How far the results scroll one way before the header follows, in dp. */
internal const val MediaChromeSlopDp = 24f

/**
 * [content] in a column that folds away while [collapsed], at once under
 * reduce motion.
 */
@Composable
internal fun ColumnScope.MediaHeaderReveal(
    collapsed: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val still = LocalKbTheme.current.reduceMotion
    AnimatedVisibility(
        visible = !collapsed,
        enter = if (still) EnterTransition.None else expandVertically(),
        exit = if (still) ExitTransition.None else shrinkVertically(),
    ) {
        Column(content = content)
    }
}
