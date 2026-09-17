package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The television reflow, over the shipped corpus rather than one hand-made
 * board: the point of a transform is that a Russian or a Greek layout gets the
 * same treatment a QWERTY one does, with its own alphabet in its own order.
 */
class TelevisionGridTest {

    private fun letters(spec: LayoutSpec) = spec.compile(LayoutLayer.LETTERS)

    private fun spell(row: List<Key>) = row.map { it.label }

    @Test
    fun `qwerty keeps its rows, one column per key`() {
        val grid = letters(BuiltInLayouts.QWERTY).gridForTelevision()
        assertEquals(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            spell(grid.rows[0]),
        )
        assertEquals(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"), spell(grid.rows[1]))
        assertTrue("every letter key is one column", grid.rows.take(2).flatten().all { it.width == 1f })
    }

    /**
     * The row the user actually complained about when this transform first
     * chunked keys blindly: `z` belongs with `x c v b n m`, and shift and
     * backspace belong on either side of them — which is where its layout
     * already had them.
     */
    @Test
    fun `shift and backspace stay where they flank the last letter row`() {
        val row = letters(BuiltInLayouts.QWERTY).gridForTelevision().rows[2]
        assertEquals(KeyAction.Shift, row.first().action)
        assertEquals(KeyAction.Delete, row.last().action)
        assertEquals(
            listOf("z", "x", "c", "v", "b", "n", "m"),
            spell(row.drop(1).dropLast(1)),
        )
        assertTrue("the flanks are one column too", row.all { it.width == 1f })
    }

    @Test
    fun `the spacebar's row is left exactly as authored`() {
        val before = letters(BuiltInLayouts.QWERTY)
        val authored = before.rows.first { row -> row.any { it.action == KeyAction.Space } }
        val after = letters(BuiltInLayouts.QWERTY).gridForTelevision().rows.last()
        assertEquals(spell(authored), spell(after))
        assertEquals(
            "a wide spacebar costs a remote nothing",
            authored.first { it.action == KeyAction.Space }.width,
            after.first { it.action == KeyAction.Space }.width,
            0.001f,
        )
    }

    @Test
    fun `nothing is lost, gained or reordered`() {
        for (spec in BuiltInLayouts.all) {
            val before = letters(spec)
            val after = before.gridForTelevision()
            assertEquals(
                "${spec.id} changed its keys",
                before.rows.flatten().map { it.label },
                after.rows.flatten().map { it.label },
            )
        }
    }

    @Test
    fun `a russian board keeps its own alphabet and its own rows`() {
        val before = letters(BuiltInLayouts.RUSSIAN)
        val after = before.gridForTelevision()
        assertEquals(before.rows.flatten().map { it.label }, after.rows.flatten().map { it.label })
        // Its top row is eleven keys, wider than the grid: it comes out as six
        // and five rather than ten and a stub of one.
        assertEquals(11, before.rows[0].size)
        assertEquals(listOf(6, 5), after.rows.take(2).map { it.size })
    }

    @Test
    fun `long-press alternates survive the reflow`() {
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
    fun `an over-long row splits into equal parts`() {
        val wide = KeyboardLayout(name = "wide", rows = listOf((1..23).map { Key("k$it") }))
        assertEquals(8, televisionGridColumns(wide))
        assertEquals(listOf(8, 8, 7), wide.gridForTelevision().rows.map { it.size })
    }

    @Test
    fun `a row that already fits is not cut at all`() {
        val fits = KeyboardLayout(
            name = "fits",
            rows = listOf((1..10).map { Key("k$it") }, (1..9).map { Key("j$it") }),
        )
        assertEquals(10, televisionGridColumns(fits))
        assertEquals(listOf(10, 9), fits.gridForTelevision().rows.map { it.size })
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

    @Test
    fun `every shipped board that reflows comes out inside the grid`() {
        for (spec in BuiltInLayouts.all) {
            val before = letters(spec)
            val columns = televisionGridColumns(before) ?: continue
            assertNotNull(columns)
            val rows = before.gridForTelevision().rows.filterNot { row ->
                row.any { it.action == KeyAction.Space }
            }
            assertTrue(
                "${spec.id} has a row wider than its grid",
                rows.all { it.size <= columns },
            )
        }
    }
}
