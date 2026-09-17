package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The television grid, over the shipped corpus rather than one hand-made board:
 * the point of a transform is that a Russian or a Greek layout gets the same
 * rectangle a QWERTY one does, with its own alphabet in its own order.
 */
class TelevisionGridTest {

    private fun letters(spec: LayoutSpec) = spec.compile(LayoutLayer.LETTERS)

    private fun spell(row: List<Key>) = row.map { it.label }

    private fun width(row: List<Key>) = row.sumOf { it.width.toDouble() }.toFloat()

    @Test
    fun `qwerty comes out as the grid gboard's tv board uses`() {
        val grid = letters(BuiltInLayouts.QWERTY).gridForTelevision()
        assertEquals(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            spell(grid.rows[0]),
        )
        // Nine letters and the layout's own full stop, which its bottom row was
        // carrying: ten keys, no gap.
        assertEquals(
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "."),
            spell(grid.rows[1]),
        )
        assertEquals(
            listOf("⇧", "z", "x", "c", "v", "b", "n", "m", ",", "⌫"),
            spell(grid.rows[2]),
        )
    }

    @Test
    fun `every row is exactly the grid wide`() {
        for (spec in BuiltInLayouts.all) {
            val before = letters(spec)
            val columns = televisionGridColumns(before) ?: continue
            for ((index, row) in before.gridForTelevision().rows.withIndex()) {
                assertEquals("${spec.id} row $index", columns.toFloat(), width(row), 0.001f)
            }
        }
    }

    @Test
    fun `every letter key is one column`() {
        val grid = letters(BuiltInLayouts.QWERTY).gridForTelevision()
        assertTrue(grid.rows.dropLast(1).flatten().all { it.width == 1f })
    }

    @Test
    fun `shift and backspace flank the last letter row`() {
        val row = letters(BuiltInLayouts.QWERTY).gridForTelevision().rows[2]
        assertEquals(KeyAction.Shift, row.first().action)
        assertEquals(KeyAction.Delete, row.last().action)
    }

    @Test
    fun `the bottom row carries the caret arrows a remote has no other way to move`() {
        val bottom = letters(BuiltInLayouts.QWERTY).gridForTelevision().rows.last()
        val sent = bottom.mapNotNull { (it.action as? KeyAction.SendKey)?.keyCode }
        assertEquals("left and right, in that order", listOf(21, 22), sent)
        assertTrue("the layer switch", bottom.any { it.action == KeyAction.Symbols })
        assertTrue("space", bottom.any { it.action == KeyAction.Space })
        assertTrue("enter", bottom.any { it.action == KeyAction.Enter })
        assertTrue(
            "the spacebar takes what is left",
            bottom.first { it.action == KeyAction.Space }.width >= 2f,
        )
    }

    @Test
    fun `no letter is lost, repeated or reordered`() {
        for (spec in BuiltInLayouts.all) {
            val before = letters(spec)
            if (televisionGridColumns(before) == null) continue
            // Letters only: the punctuation a layout carries is allowed to move
            // (filling the grid is what the spare keys are for), and Dvorak puts
            // an apostrophe and a comma in its top row, so comparing every text
            // key would compare a filler against a letter.
            assertEquals(
                "${spec.id} dropped or reordered its alphabet",
                alphabet(before),
                alphabet(before.gridForTelevision()),
            )
        }
    }

    /** The single-letter keys, in the order the grid draws them. */
    private fun alphabet(layout: KeyboardLayout) = layout.rows.flatten()
        .filter { it.action == KeyAction.Text && it.label.singleOrNull()?.isLetter() == true }
        .map { it.label }

    @Test
    fun `a russian board keeps its own alphabet, split evenly`() {
        val before = letters(BuiltInLayouts.RUSSIAN)
        val after = before.gridForTelevision()
        // Its top row is eleven keys, wider than the grid: six and five rather
        // than ten and a stub of one, each padded out to the full ten.
        assertEquals(11, before.rows[0].size)
        assertEquals(
            before.rows[0].take(6).map { it.label },
            spell(after.rows[0]).take(6),
        )
        assertTrue(after.rows.all { width(it) == 10f })
    }

    @Test
    fun `long-press alternates survive the rebuild`() {
        val grid = letters(BuiltInLayouts.QWERTY).gridForTelevision()
        val e = grid.rows.flatten().first { it.label == "e" }
        assertTrue("accents are the remote's only route to them", "é" in e.longPress)
    }

    @Test
    fun `an ambiguous board is left exactly as authored`() {
        for (spec in listOf(BuiltInLayouts.T9, BuiltInLayouts.COMPACT)) {
            val before = letters(spec)
            assertNull("${spec.id} must decline", televisionGridColumns(before))
            assertSame("${spec.id} must not be rewritten", before, before.gridForTelevision())
        }
    }

    @Test
    fun `a pad too small to be a keyboard declines`() {
        val pad = KeyboardLayout(name = "pad", rows = listOf((1..6).map { Key("$it") }))
        assertNull(televisionGridColumns(pad))
    }

    @Test
    fun `a grid with tall keys declines rather than flattening them`() {
        val tall = KeyboardLayout(
            name = "tall",
            rows = listOf(
                (1..20).map { Key("k$it") },
                listOf(Key("⏎", action = KeyAction.Enter, rowSpan = 2)),
            ),
        )
        assertNull(televisionGridColumns(tall))
    }

    @Test
    fun `a board with no punctuation to spare is filled from the defaults`() {
        val bare = KeyboardLayout(
            name = "bare",
            rows = listOf(
                (1..10).map { Key("k$it") },
                (1..7).map { Key("j$it") },
                listOf(Key(" ", action = KeyAction.Space, width = 4f)),
            ),
        )
        val grid = bare.gridForTelevision()
        assertEquals(10, grid.rows[1].size)
        assertEquals(listOf(".", ",", "?"), spell(grid.rows[1]).takeLast(3))
    }

    /**
     * The corpus, as a change detector: which shipped boards decline. A layout
     * moving in or out of this list changes what a television user sees, and
     * should be a deliberate edit rather than a surprise.
     */
    @Test
    fun `exactly the authored-geometry boards decline`() {
        val declined = BuiltInLayouts.all
            .filter { televisionGridColumns(letters(it)) == null }
            .map { it.id }
            .sorted()
        assertEquals(listOf(BuiltInLayouts.COMPACT_ID, BuiltInLayouts.T9_ID).sorted(), declined)
    }
}
