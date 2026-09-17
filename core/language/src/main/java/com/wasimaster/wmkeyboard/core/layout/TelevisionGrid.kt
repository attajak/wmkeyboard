package com.wasimaster.wmkeyboard.core.layout

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Evens a key grid out into the rectangle a television remote can cross.
 *
 * ## Why a transform rather than a TV layout
 *
 * The same argument [expandForTablet] makes, and it is why that one is next
 * door: the app ships well over a thousand layouts, and authoring a television
 * grid for each was never on the table. A single hand-made "TV grid" would be
 * one more English board, no use to anyone typing Russian, Greek, Hindi or
 * Bangla on a set-top box. So this runs at resolve time over whichever grid the
 * user is already on, and every layout gets it for free.
 *
 * ## What it changes, and what it deliberately does not
 *
 * A D-pad costs *distance*: every key is one press from its neighbour whatever
 * size it is drawn at. What makes a phone grid slow to cross is not its letter
 * order — that order is the one thing its user already knows — but its
 * unevenness: keys of five different widths, rows of ten, nine and seven, and a
 * spacebar that spans five columns, so "one press right" travels a different
 * distance on every row and the ring wanders off the column it was on.
 *
 * So the letters keep their rows and their order. Each letter key is drawn one
 * column wide, so every row lines up with the one above it, and a row too long
 * for the grid is split into equal halves rather than spilling two keys onto a
 * line of their own. Shift and backspace stay exactly where their layout put
 * them — flanking the last letter row, which is where every phone keyboard
 * since the first one has drawn them — and the row that carries the spacebar is
 * left as authored, because a wide spacebar costs a remote nothing: it is still
 * one press.
 *
 * ## Eligibility
 *
 * [televisionGridColumns] returns null for a grid this cannot honestly reflow:
 * an ambiguous board (T9 and Compact QWERTY are *already* big-key grids, built
 * to be aimed at), a layout whose keys claim more than one row, and the pads too
 * small or too large to be a keyboard's letters — kana flicks, braille, morse,
 * the Chinese shape pads. Declining leaves the authored grid alone, which is
 * never wrong, only uneven. `TelevisionGridTest` pins the corpus.
 */

/** Widest a reflowed row gets before it is split into equal parts. */
const val TvGridColumns = 10

/** Below this many keys a grid is a pad, not a keyboard. */
private const val MinKeys = 12

/** …and above this it is a chart. Both bail out rather than reflow. */
private const val MaxKeys = 72

/**
 * How many columns wide the reflowed grid would be, or null when it declines to
 * touch this one. Callers ask this first, the way they ask [tabletGridWidth], so
 * the decision and the rewrite cannot disagree.
 */
fun televisionGridColumns(layout: KeyboardLayout): Int? {
    val keys = layout.rows.flatten()
    if (keys.any { it.rowSpan > 1 }) return null
    // An ambiguous key stands for several letters and is drawn big on purpose;
    // re-cutting that grid would be undoing the feature.
    if (keys.any { !it.letters.isNullOrEmpty() }) return null
    if (keys.size < MinKeys || keys.size > MaxKeys) return null
    val rows = layout.rows.filterNot { it.isBoardRow() }
    if (rows.isEmpty()) return null
    return rows.maxOf { row -> splitEvenly(row.size).maxOrNull() ?: 0 }.takeIf { it > 0 }
}

/**
 * The evened-out grid. Returns the receiver unchanged when
 * [televisionGridColumns] declines, so a caller that forgot to ask still gets
 * the authored grid rather than a mangled one.
 */
fun KeyboardLayout.gridForTelevision(): KeyboardLayout {
    televisionGridColumns(this) ?: return this
    val reflowed = rows.flatMap { row ->
        // The spacebar's row is the board's own — the layer switch, the globe,
        // space, Enter — and its widths are what make it read as one. A remote
        // crosses a five-column spacebar in a single press, so there is nothing
        // to gain by cutting it up.
        if (row.isBoardRow()) return@flatMap listOf(row)
        val parts = splitEvenly(row.size)
        var taken = 0
        parts.map { size -> row.subList(taken, taken + size).map { it.oneColumn() }.also { taken += size } }
    }
    // rowHeights is positionally aligned with the rows it came with, and a split
    // row means these are no longer those rows: a per-row multiplier would land
    // on whichever half happened to take its index.
    val heights = if (reflowed.size == rows.size) rowHeights else null
    return copy(rows = reflowed, rowHeights = heights)
}

/**
 * How to cut a row of [size] keys into rows no wider than [TvGridColumns]:
 * equal parts, the remainder spread over the first of them.
 *
 * Equal rather than "fill ten, then whatever is left" so a twelve-key Cyrillic
 * row becomes two rows of six instead of a row of ten and a stub of two — which
 * would leave the ring falling off the end of one row into empty space.
 */
private fun splitEvenly(size: Int): List<Int> {
    if (size <= TvGridColumns) return listOf(size)
    val parts = ceil(size / TvGridColumns.toFloat()).roundToInt()
    val base = size / parts
    val extra = size % parts
    return List(parts) { index -> base + if (index < extra) 1 else 0 }
}

/** Whether this row is the board's own: the one the spacebar sits on. */
private fun List<Key>.isBoardRow(): Boolean = any { it.action == KeyAction.Space }

/** The same key, one column wide. */
private fun Key.oneColumn(): Key = if (width == 1f) this else copy(width = 1f)
