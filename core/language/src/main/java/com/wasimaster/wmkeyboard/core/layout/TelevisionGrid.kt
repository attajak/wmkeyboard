package com.wasimaster.wmkeyboard.core.layout

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Squares a key grid off into the rectangle a television remote can cross.
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
 * ## What comes out
 *
 * Every row is exactly [televisionGridColumns] columns wide, so each key sits
 * squarely above and below its neighbours and "one press right" always travels
 * the same distance — which is the whole cost model of a D-pad. This is the
 * shape every television keyboard converges on, Gboard's TV board included.
 *
 * - The letters keep their own rows and their own order. That order is the one
 *   thing the user already knows, and a row too long for the grid is split into
 *   equal parts rather than spilling two keys onto a line of their own.
 * - Shift and backspace flank the last letter row, where every phone keyboard
 *   already draws them.
 * - A row short of the grid is filled from the layout's own spare punctuation —
 *   the comma and full stop its bottom row was carrying — and then from
 *   [FillerKeys], so the grid has no ragged edge and no gap the ring can fall
 *   into.
 * - The bottom row is rebuilt from the board's own keys, and carries the caret
 *   arrows. Those are not decoration: the arrow keys on a remote move the
 *   *ring*, so without a key for it there is no way to put the cursor back in
 *   the middle of a word.
 *
 * ## Eligibility
 *
 * [televisionGridColumns] returns null for a grid this cannot honestly rebuild:
 * an ambiguous board (T9 and Compact QWERTY are *already* big-key grids, built
 * to be aimed at), a layout whose keys claim more than one row, and the pads too
 * small or too large to be a keyboard's letters — kana flicks, braille, morse,
 * the Chinese shape pads. Declining leaves the authored grid alone, which is
 * never wrong, only uneven. `TelevisionGridTest` pins the corpus.
 */

/** Columns a television grid uses where the alphabet fits in them. */
const val TvGridColumns = 10

/** Below this many keys a grid is a pad, not a keyboard. */
private const val MinKeys = 12

/** …and above this it is a chart. Both bail out rather than rebuild. */
private const val MaxKeys = 72

/** Width of the flanking and switching keys, on the conventions layouts use. */
private const val WideKey = 1.5f

/** Narrowest the rebuilt spacebar may be, in columns. */
private const val MinSpace = 2f

// Written as numbers rather than KeyEvent.KEYCODE_*, the way KeyActions.kt and
// TabletExpansion.kt do it: this module is pure layout data with no android.view.
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22

/**
 * What a short row is filled with once the layout's own spare punctuation has
 * run out. Ordinary text keys, in the order a keyboard misses them.
 */
private val FillerKeys = listOf(
    Key(".", role = KeyRole.Period, longPress = listOf("…", "?", "!")),
    Key(",", role = KeyRole.Comma, longPress = listOf(";", ":")),
    Key("?", longPress = listOf("!")),
    Key("'", longPress = listOf("\"")),
    Key("-", longPress = listOf("_")),
    Key("/", longPress = listOf("\\")),
)

/**
 * How many columns wide the rebuilt grid is, or null when it declines to touch
 * this one. Callers ask this first, the way they ask [tabletGridWidth], so the
 * decision and the rewrite cannot disagree.
 */
fun televisionGridColumns(layout: KeyboardLayout): Int? {
    val keys = layout.rows.flatten()
    if (keys.any { it.rowSpan > 1 }) return null
    // An ambiguous key stands for several letters and is drawn big on purpose;
    // re-cutting that grid would be undoing the feature.
    if (keys.any { !it.letters.isNullOrEmpty() }) return null
    if (keys.size < MinKeys || keys.size > MaxKeys) return null
    val letterRows = layout.letterRows()
    if (letterRows.isEmpty()) return null
    val widest = letterRows.maxOf { row -> splitEvenly(row.size, TvGridColumns).max() }
    return max(TvGridColumns, widest)
}

/**
 * The squared-off grid. Returns the receiver unchanged when
 * [televisionGridColumns] declines, so a caller that forgot to ask still gets
 * the authored grid rather than a mangled one.
 */
fun KeyboardLayout.gridForTelevision(): KeyboardLayout {
    val columns = televisionGridColumns(this) ?: return this
    val all = rows.flatten()
    val shift = all.firstOrNull { it.action == KeyAction.Shift }
    val delete = all.firstOrNull { it.action == KeyAction.Delete }

    // Spare text keys the board row was carrying — the comma and full stop, on
    // most layouts — are the first thing a short row is filled with: they are
    // this layout's own punctuation, and they have to live somewhere.
    // Full stop first, comma next, whatever else after: that is the order a
    // keyboard misses them in, so the full stop lands at the end of the home
    // row where every television board puts it rather than wherever this
    // layout's bottom row happened to list it.
    val spare = rows.filter { it.isBoardRow() }.flatten()
        .filter { it.isText() }
        .sortedBy { key ->
            when (key.role) {
                KeyRole.Period -> 0
                KeyRole.Comma -> 1
                else -> 2
            }
        }
    val filler = ArrayDeque(spare + FillerKeys)

    // Letters first, in their own rows, with any over-long row split evenly and
    // the last row left room for the two keys that flank it.
    val flanks = listOfNotNull(shift, delete).size
    val letterRows = letterRows().map { row -> row.filter { it.isText() } }.filter { it.isNotEmpty() }
    val chunked = letterRows.flatMapIndexed { index, row ->
        val limit = if (index == letterRows.lastIndex) columns - flanks else columns
        splitEvenly(row.size, limit).let { sizes ->
            var taken = 0
            sizes.map { size -> row.subList(taken, taken + size).also { taken += size } }
        }
    }

    val built = chunked.mapIndexed { index, row ->
        val last = index == chunked.lastIndex
        val room = if (last) columns - flanks else columns
        val padded = row.map { it.oneColumn() } + List(max(0, room - row.size)) {
            filler.removeFirstOrNull()?.oneColumn() ?: Key(" ", action = KeyAction.Space)
        }
        buildList {
            if (last && shift != null) add(shift.copy(width = 1f))
            addAll(padded)
            if (last && delete != null) add(delete.copy(width = 1f))
        }
    }

    // rowHeights is positionally aligned with the rows it came with, and these
    // are not those rows.
    return copy(rows = built + listOf(bottomRow(columns)), rowHeights = null)
}

/**
 * The board's own row, rebuilt to fill the grid exactly: the layer switch, the
 * caret arrows, the spacebar, the globe and emoji keys the layout had, and
 * Enter. The spacebar takes whatever is left, which is what makes the row come
 * out at [columns] however many of the others this layout has.
 */
private fun KeyboardLayout.bottomRow(columns: Int): List<Key> {
    val all = rows.flatten()
    fun first(vararg actions: KeyAction) = all.firstOrNull { it.action in actions }

    val head = buildList {
        first(KeyAction.Symbols, KeyAction.Letters)?.let { add(it.copy(width = WideKey)) }
        // The remote's own arrows drive the ring, so these are the only way to
        // put the caret anywhere but the end of what has been typed.
        add(Key("◀", action = KeyAction.SendKey(KEYCODE_DPAD_LEFT)))
        add(Key("▶", action = KeyAction.SendKey(KEYCODE_DPAD_RIGHT)))
    }
    val tail = buildList {
        first(KeyAction.LanguageSwitch)?.let { add(it.copy(width = 1f)) }
        first(KeyAction.Emoji)?.let { add(it.copy(width = 1f)) }
        first(KeyAction.Enter)?.let { add(it.copy(width = WideKey)) }
    }
    val taken = (head + tail).sumOf { it.width.toDouble() }.toFloat()
    val space = first(KeyAction.Space) ?: Key(" ", action = KeyAction.Space)
    return head + space.copy(width = max(MinSpace, columns - taken)) + tail
}

/** The rows that carry the alphabet: everything but the one the spacebar is on. */
private fun KeyboardLayout.letterRows(): List<List<Key>> = rows.filterNot { it.isBoardRow() }

/**
 * How to cut a row of [size] keys into rows no wider than [limit]: equal parts,
 * the remainder spread over the first of them.
 *
 * Equal rather than "fill the row, then whatever is left" so an eleven-key
 * Cyrillic row becomes six and five instead of ten and a stub of one — which
 * would leave most of a row for the ring to cross with nothing in it.
 */
private fun splitEvenly(size: Int, limit: Int): List<Int> {
    if (limit < 1) return listOf(size)
    if (size <= limit) return listOf(size)
    val parts = ceil(size / limit.toFloat()).roundToInt()
    val base = size / parts
    val extra = size % parts
    return List(parts) { index -> base + if (index < extra) 1 else 0 }
}

/** Whether this row is the board's own: the one the spacebar sits on. */
private fun List<Key>.isBoardRow(): Boolean = any { it.action == KeyAction.Space }

/** Whether this key writes text rather than running the keyboard. */
private fun Key.isText(): Boolean = action == KeyAction.Text

/** The same key, one column wide. */
private fun Key.oneColumn(): Key = if (width == 1f) this else copy(width = 1f)
