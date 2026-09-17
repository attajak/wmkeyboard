package com.wasimaster.wmkeyboard.ime

import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.ime.ui.nextKeyCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where one press of the D-pad lands, on the grid a television remote is
 * actually pointing at.
 *
 * The cases here are the ones the geometry has to get right for the ring to
 * feel like a keyboard rather than a puzzle: a staggered row (QWERTY's `a` row
 * sits half a key right of its `q` row), a key several columns wide (the
 * spacebar), and the two edges — which behave differently on purpose.
 */
class KeyGridFocusTest {

    /** Three rows of a phone-shaped board: 10 keys, 9 offset by half, then a spacebar row. */
    private val keyWidth = 100f
    private val keyHeight = 60f

    private fun row(count: Int, y: Int, offset: Float = 0f): List<Rect> =
        (0 until count).map {
            Rect(
                offset + it * keyWidth,
                y * keyHeight,
                offset + (it + 1) * keyWidth,
                (y + 1) * keyHeight,
            )
        }

    private val topRow = row(10, 0)
    private val homeRow = row(9, 1, offset = keyWidth / 2)
    private val space = Rect(200f, 2 * keyHeight, 700f, 3 * keyHeight)
    private val bottomRow = listOf(
        Rect(0f, 2 * keyHeight, 200f, 3 * keyHeight),
        space,
        Rect(700f, 2 * keyHeight, 1000f, 3 * keyHeight),
    )
    private val grid = topRow + homeRow + bottomRow

    @Test
    fun `right moves to the next key in the row`() {
        assertEquals(topRow[4], nextKeyCell(grid, topRow[3], 1, 0))
    }

    @Test
    fun `left moves back`() {
        assertEquals(topRow[2], nextKeyCell(grid, topRow[3], -1, 0))
    }

    @Test
    fun `down from a staggered row lands on a neighbour, left one first`() {
        // QWERTY's home row is offset by half a key, so `e` sits exactly over
        // the seam between `s` and `d`: same overlap, same centre distance, a
        // genuine tie. It is broken by the order the grid reported its cells —
        // left to right — so the ring goes to the left of the two, every time,
        // on every board. Deterministic is the requirement; which one it is is
        // not.
        assertEquals(homeRow[1], nextKeyCell(grid, topRow[2], 0, 1))
    }

    @Test
    fun `down prefers the key nearest below when the rows line up`() {
        // Same two rows without the stagger: no tie left to break.
        val aligned = topRow + row(10, 1)
        assertEquals(aligned[12], nextKeyCell(aligned, topRow[2], 0, 1))
    }

    @Test
    fun `down onto a wide key lands on it rather than skipping past`() {
        assertEquals(space, nextKeyCell(grid, homeRow[3], 0, 1))
    }

    @Test
    fun `up off the top row goes nowhere, so the app gets the key`() {
        assertNull(nextKeyCell(grid, topRow[0], 0, -1))
    }

    @Test
    fun `down off the bottom row goes nowhere`() {
        assertNull(nextKeyCell(grid, space, 0, 1))
    }

    @Test
    fun `right off the end of a row wraps to its start`() {
        assertEquals(topRow[0], nextKeyCell(grid, topRow[9], 1, 0))
    }

    @Test
    fun `left off the start of a row wraps to its end`() {
        assertEquals(topRow[9], nextKeyCell(grid, topRow[0], -1, 0))
    }

    @Test
    fun `wrapping stays inside the row the ring is on`() {
        assertEquals(bottomRow[2], nextKeyCell(grid, bottomRow[0], -1, 0))
    }

    @Test
    fun `a straight-ahead key beats a nearer one off to the side`() {
        // A tall Enter key beside the home row: from `l`, down must reach the
        // key under it rather than the one whose centre happens to be closer.
        val enter = Rect(900f, keyHeight, 1000f, 3 * keyHeight)
        val cells = grid + enter
        assertEquals(space, nextKeyCell(cells, homeRow[4], 0, 1))
    }

    @Test
    fun `an empty grid moves nowhere`() {
        assertNull(nextKeyCell(emptyList(), topRow[0], 1, 0))
    }
}
