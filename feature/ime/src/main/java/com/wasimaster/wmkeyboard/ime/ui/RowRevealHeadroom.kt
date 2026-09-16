package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout

/**
 * Room the docked frame holds back for bar rows that are growing in or
 * shrinking away.
 *
 * The frame is the IME window's height. A row expanding into the stack or
 * collapsing out of it changed the frame with it, so every frame of the move
 * was a window relayout and a new content inset, and every new inset re-laid
 * out the host app. That was the stutter in the selection macro bar's entrance
 * and in the chevron's tools row both ways.
 *
 * So while a row moves, the frame adds back whatever the row is short of its
 * full height, as empty space above the board. The window changes size once
 * per move — on an entrance's first frame, and after an exit's last — and the
 * row grows or shrinks inside a frame that holds still: the resize tool's trick
 * ([resizeHeadroom]), sized frame by frame instead of latched. Nothing is drawn
 * in the held space, so the top of the board still travels with the row.
 *
 * A row may go further and keep its height held for as long as it is out
 * (`reserveWhenOutPx`), which costs the window that band all the time and buys
 * a toggle that resizes nothing at all. Worth it for the chevron's tools row:
 * on some devices the single remaining resize dropped the keyboard's surface
 * for two frames and served one stale, top-aligned buffer after it, which read
 * as the keyboard blinking (issue #217).
 *
 * Every row keeps its own [RowReveal] and the frame holds their sum, so rows
 * moving at the same time are all held. A selection landing while the tools
 * row folds away steps the window up by the arriving row on the first frame and
 * back down once the leaving row is gone: two resizes where none were needed,
 * which a sum cannot tell apart, but never one per frame.
 */
internal class RowRevealHeadroom {
    private val rows = mutableStateListOf<RowReveal>()

    /** What the frame holds back: every row's shortfall and every band, added up. */
    val pendingPx: Int
        get() = rows.sumOf { it.shortfallPx + it.reservedPx }

    /**
     * The part of [pendingPx] that is window rather than keyboard: the bands
     * held open for rows that are out.
     *
     * The service keeps this out of the app's insets, so the app is laid out
     * to the board and a tap in the band is the app's — the key preview band's
     * bargain ([keyPreviewHeadroomPx]), struck for the same reason.
     */
    val reservedPx: Int
        get() = rows.sumOf { it.reservedPx }

    fun track(row: RowReveal) {
        rows += row
    }

    fun untrack(row: RowReveal) {
        rows -= row
    }
}

/**
 * One row's share of [RowRevealHeadroom].
 *
 * [shortfallPx] and [reservedPx] are state because the frame reads them, and a
 * row cut from the stack mid-move has to reach a frame that nothing else
 * re-measures. [fullPx] is a plain field: the reveal writes it while measuring
 * its content, and the row reads it back in that same measure a moment later.
 */
internal class RowReveal {
    /**
     * The content's height as last measured while the row was visible.
     *
     * Kept, not re-read, on the way out. Content that empties once an exit is
     * already running does not stop the shrink: AnimatedVisibility carries the
     * size down from wherever it was, whatever the content measures now, so the
     * hold has to remember the height to cover the rest of it. Content that
     * empties in the same update that hides the row is another matter — the row
     * collapses on the next frame, and the zero-height release lets go at once.
     * That is the macro bar, whose content goes with its offer.
     */
    var fullPx = 0

    /** How far the row is from [fullPx] right now; 0 once it has finished leaving. */
    var shortfallPx by mutableIntStateOf(0)

    /**
     * The band held for this row while it is out, for a row that asked for one
     * ([RevealingBarRow]'s `reserveWhenOutPx`); 0 whenever the row is in the
     * stack, moving or settled.
     *
     * A row's band and its shortfall are written in the same measure, and the
     * two add up to the row's full height whatever it is doing, so the frame a
     * reserving row sits in is the same height open, closed and mid-move.
     */
    var reservedPx by mutableIntStateOf(0)
}

/**
 * Provided by the docked frame. Null in floating mode, where the window already
 * spans the screen and a row growing inside the panel resizes nothing.
 */
internal val LocalRowRevealHeadroom = staticCompositionLocalOf<RowRevealHeadroom?> { null }

/**
 * The frame's half: as tall as what it holds plus whatever the moving rows are
 * short of, with the keyboard at the bottom and the held space empty.
 *
 * A measure-scope read, like [resizeHeadroom]'s: a change re-measures this
 * node and recomposes nothing.
 */
internal fun Modifier.rowRevealHeadroom(headroom: RowRevealHeadroom): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height + headroom.pendingPx).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
}

/**
 * The row's half: [AnimatedVisibility], reporting its shortfall to the frame's
 * [RowRevealHeadroom], or a plain one when no frame is listening.
 *
 * The full height is read inside the reveal and the shown height outside it.
 * A row that is leaving and has reached zero is done, and its hold goes on that
 * frame: the window shrinks with the exit's last frame rather than whenever the
 * reveal gets round to dropping content that nothing will measure again.
 *
 * [reserveWhenOutPx] keeps the row's height held even once it is gone, so the
 * frame never changes at all — the one resize each way becomes none. Only worth
 * asking for where the row is the user's own toggle and the height is known
 * before it is ever measured; the frame reports the band to the service, which
 * keeps it out of the app's insets, so the app is laid out to the board exactly
 * as it is today and the window simply stops resizing under it. The row still
 * has to be composed while out — a row taken out of the stack takes its band
 * with it, which is right: a full-bleed panel is a real resize either way.
 */
@Composable
internal fun ColumnScope.RevealingBarRow(
    visible: Boolean,
    enter: EnterTransition,
    exit: ExitTransition,
    reserveWhenOutPx: Int = 0,
    content: @Composable () -> Unit,
) {
    val headroom = LocalRowRevealHeadroom.current
    if (headroom == null) {
        AnimatedVisibility(visible = visible, enter = enter, exit = exit) { content() }
        return
    }
    val reveal = remember { RowReveal() }
    // Tracked for as long as the row is in the stack. A row cut mid-move (a
    // full-bleed panel, the placement switched) takes its shortfall with it.
    DisposableEffect(headroom, reveal) {
        headroom.track(reveal)
        onDispose { headroom.untrack(reveal) }
    }
    // The reveal is wrapped rather than measured through its own modifier,
    // because an [AnimatedVisibility] that has never been visible puts no node
    // in the tree at all: a modifier on it would not run until the first time
    // the row opened, and a band that only appears after the first open is no
    // band at all. This wrapper is always here, and reads the row's height as
    // zero when there is nothing inside it to measure.
    Layout(
        content = {
            AnimatedVisibility(visible = visible, enter = enter, exit = exit) {
                Box(
                    modifier = Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        if (visible) reveal.fullPx = placeable.height
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                ) {
                    content()
                }
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.firstOrNull()?.measure(constraints)
        val shown = placeable?.height ?: 0
        val out = !visible && shown == 0
        // Pinned while the row is out, so the first frame of an entrance is
        // short of the band rather than of a height nothing has measured yet:
        // a row opened for the first time in a session would otherwise drop
        // the band, then grow back into it, which is the resize this is here
        // to avoid.
        if (out && reserveWhenOutPx > 0) reveal.fullPx = reserveWhenOutPx
        reveal.shortfallPx = if (out) 0 else (reveal.fullPx - shown).coerceAtLeast(0)
        reveal.reservedPx = if (out) reserveWhenOutPx else 0
        layout(placeable?.width ?: constraints.minWidth, shown) { placeable?.place(0, 0) }
    }
}
