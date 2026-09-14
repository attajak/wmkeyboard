package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GlideCoverage
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.keySpelling

/**
 * A real shipped layout's letter grid, laid out the way the renderer lays it
 * out, for the swipe corpus to draw on.
 *
 * The harness used to carry its own hand-written QWERTY table, which was fine
 * while glide was English-only and is not fine now: the interesting layouts are
 * the ones nobody would copy by hand — Probhat, where most keys are Bengali
 * vowel signs and every consonant hides an aspirated twin behind shift, and
 * ЙЦУКЕН, whose twelve-column rows are a different shape of problem from
 * QWERTY's ten. Deriving the geometry from [LayoutSpec] means the corpus draws
 * on the grid the user actually sees, and a layout edit shows up here.
 *
 * Coordinates are in key widths. Rows narrower than the widest are centred, the
 * same rule `KeyRows` follows, so the stagger a shift or backspace key creates
 * is real rather than assumed.
 *
 * Keyed by code point, like the grid it models: a letter outside the BMP is one
 * key, not two halves. The `Char` conveniences are for the Latin-only tests.
 */
class GlideGrid private constructor(
    val name: String,
    /** Every character the grid can produce, at its key's centre. */
    val keys: List<KeyCenter>,
    /** The shift key's cell, in key widths, or null when the letters layer has none. */
    val shift: Cell? = null,
) {

    /** A key's rectangle, in key widths; rows are one key width tall. */
    class Cell(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    private val byCodePoint: Map<Int, KeyCenter> = keys.associateBy { it.codePoint }

    /** Code points this grid can spell. */
    val alphabet: Set<Int> get() = byCodePoint.keys

    /** Where [codePoint] sits, in key widths, or null when the grid cannot produce it. */
    fun centerOf(codePoint: Int): KeyCenter? = byCodePoint[Character.toLowerCase(codePoint)]

    /** Where [ch] sits, in key widths, or null when the grid cannot produce it. */
    fun centerOf(ch: Char): KeyCenter? = centerOf(ch.code)

    /** Whether every letter of [word] is on this grid. */
    fun canSpell(word: String): Boolean = GlideCoverage.spells(word, alphabet)

    /** The code point whose key centre sits closest to a point, in key widths. */
    fun nearestKey(x: Float, y: Float): Int {
        var best = keys.first()
        var bestDistance = Float.MAX_VALUE
        for (key in keys) {
            val d = (key.x - x) * (key.x - x) + (key.y - y) * (key.y - y)
            if (d < bestDistance) {
                bestDistance = d
                best = key
            }
        }
        return best.codePoint
    }

    /** The grid in the pixel space of a keyboard with keys [keyWidth] wide. */
    fun keyCenters(keyWidth: Float): List<KeyCenter> =
        keys.map { KeyCenter(it.codePoint, it.x * keyWidth, it.y * keyWidth) }

    companion object {

        fun of(spec: LayoutSpec): GlideGrid {
            val layer = spec.compile(LayoutLayer.LETTERS)
            val rowWidths = layer.rows.map { row -> row.sumOf { it.width.toDouble() }.toFloat() }
            val widest = rowWidths.maxOrNull() ?: 0f
            val centres = HashMap<Int, Pair<Float, Float>>()
            var shift: Cell? = null
            for ((index, row) in layer.rows.withIndex()) {
                var x = (widest - rowWidths[index]) / 2f
                for (key in row) {
                    val centre = x + key.width / 2f
                    if (key.action == KeyAction.Shift && shift == null) {
                        shift = Cell(x, index - 0.5f, x + key.width, index + 0.5f)
                    }
                    x += key.width
                    // Anchored by the first character the key writes, matching
                    // what the renderer reports — a Bengali nukta key writes
                    // two characters and would vanish from a "exactly one" test.
                    val anchor = keySpelling(key.label)?.first() ?: continue
                    centres.putIfAbsent(anchor, centre to index.toFloat())
                }
            }
            // Through LayoutSet so the corpus and the decoder agree on which
            // characters a key can produce, down to the order they are claimed
            // in. Two grids that disagree about that would make every number
            // here a measurement of the disagreement.
            val set = LayoutSet(layer, layer, layer)
            return GlideGrid(spec.name, set.glideKeys { centres[it] }, shift)
        }
    }
}
