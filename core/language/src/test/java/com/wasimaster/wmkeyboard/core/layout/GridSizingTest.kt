package com.wasimaster.wmkeyboard.core.layout

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizing arithmetic the layout editor writes with: the hundredth a size is
 * rounded to, and the scale that lands a row of mixed-width keys exactly on the
 * grid.
 *
 * The interesting case throughout is a row whose keys are *not* all the same
 * width. Quarter steps could size a row of identical keys and nothing else, and
 * "the row will not add up" is what sent people to the raw JSON.
 */
class GridSizingTest {

    private fun row(vararg widths: Float) = widths.map { Key("k", width = it) }

    private fun List<Key>.total() = sumOf { it.width.toDouble() }.toFloat()

    // ---- rounding ----

    @Test
    fun `a size lands on a hundredth`() {
        assertEquals(1.43f, roundGridUnit(10f / 7f), 1e-6f)
        assertEquals(1f, roundGridUnit(1.004f), 1e-6f)
        assertEquals(1.01f, roundGridUnit(1.005f), 1e-6f)
    }

    @Test
    fun `a size already on a hundredth is left alone`() {
        for (value in listOf(0.5f, 1f, 1.25f, 1.43f, 4f, 12f)) {
            assertEquals("$value", value, roundGridUnit(value), 1e-6f)
        }
    }

    @Test
    fun `a value no key could hold passes through rather than saturating`() {
        // Int saturation would turn 1e30 into 21474836.48 and hide the problem
        // from `repair`, which is the one thing allowed to clamp a stored width.
        assertEquals(1e30f, roundGridUnit(1e30f), 0f)
        assertTrue(roundGridUnit(Float.NaN).isNaN())
        assertEquals(Float.POSITIVE_INFINITY, roundGridUnit(Float.POSITIVE_INFINITY), 0f)
    }

    // ---- fitting a row ----

    @Test
    fun `seven equal keys become the width that fills a ten-wide grid`() {
        val fitted = fitRowToGrid(row(1f, 1f, 1f, 1f, 1f, 1f, 1f), 10f)

        // 10 / 7 = 1.4285…, which no quarter step can express.
        assertEquals(1.43f, fitted[0].width, 1e-6f)
        assertEquals(10f, fitted.total(), 1e-4f)
    }

    @Test
    fun `a row of mixed widths keeps its proportions`() {
        // Nine wide against a ten-wide grid, which is the state an edit leaves a
        // bottom row in and the state no quarter step gets it out of.
        val fitted = fitRowToGrid(row(1.5f, 1f, 1f, 3f, 1f, 1.5f), 10f)

        assertEquals("the row now fills the grid", 10f, fitted.total(), 1e-4f)
        // Every key kept its share: the space key is still three times a letter.
        assertEquals(3f, fitted[3].width / fitted[1].width, 0.02f)
        assertEquals(1.5f, fitted[0].width / fitted[1].width, 0.02f)
    }

    @Test
    fun `no key is more than a hundredth off its exact share`() {
        val widths = floatArrayOf(1.5f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.5f, 4f)
        val fitted = fitRowToGrid(row(*widths), 10f)
        val scale = 10f / widths.sum()

        fitted.forEachIndexed { index, key ->
            assertEquals("key $index", widths[index] * scale, key.width, GridUnitStep)
        }
    }

    @Test
    fun `an over-wide row is brought back down`() {
        val fitted = fitRowToGrid(row(2f, 2f, 2f, 2f, 2f, 2f), 10f)

        assertEquals(10f, fitted.total(), 1e-4f)
        assertTrue("no key grew", fitted.all { it.width < 2f })
    }

    @Test
    fun `rounding leftovers do not leave the row short`() {
        // Rounded key by key, a long row loses up to half a hundredth per key,
        // lands short, and leaves the "this row is not as wide as the grid"
        // warning on screen after the button that clears it was pressed.
        // MaxKeysPerRow is the worst case the editor allows.
        for (count in 2..MaxKeysPerRow) {
            val fitted = fitRowToGrid(row(*FloatArray(count) { 1f }), 10f)
            assertEquals("$count keys", 10f, fitted.total(), GridUnitStep / 2f)
        }
    }

    @Test
    fun `a row that already fits keeps every width it had`() {
        val original = row(1.5f, 1f, 1f, 4f, 1f, 1.5f)
        val fitted = fitRowToGrid(original, 10f)

        assertEquals(original.map { it.width }, fitted.map { it.width })
        assertEquals(
            "and fitting it again does nothing",
            fitted.map { it.width },
            fitRowToGrid(fitted, 10f).map { it.width },
        )
    }

    @Test
    fun `nothing a fit produces is narrower than a hundredth`() {
        // A key that rounds to zero is a blocking validation finding, so the
        // scale has to floor rather than let one disappear.
        val fitted = fitRowToGrid(row(20f, 0.02f), 1f)

        assertTrue(fitted.all { it.width >= GridUnitStep })
    }

    @Test
    fun `a row a scale cannot help is handed back untouched`() {
        val empty = emptyList<Key>()
        assertSame(empty, fitRowToGrid(empty, 10f))

        val zeroWidth = row(0f, 0f)
        assertSame(zeroWidth, fitRowToGrid(zeroWidth, 10f))
        assertSame(zeroWidth, fitRowToGrid(zeroWidth, 0f))

        val normal = row(1f, 1f)
        assertSame(normal, fitRowToGrid(normal, Float.NaN))
    }

    @Test
    fun `a fitted row is what the grid measures the others against`() {
        // The end-to-end promise: after a fit, `gridWeightOf` puts this row in
        // the same bucket as the rows it was fitted to, so the mismatch warning
        // the editor draws at a 0.01 tolerance goes away.
        val rows = listOf(row(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f), row(1f, 1f, 1f))
        val gridWeight = gridWeightOf(rows)
        val fitted = fitRowToGrid(rows[1], gridWeight)

        assertTrue(abs(fitted.total() - gridWeight) <= 0.01f)
    }

    // ---- filling the space row ----

    /** `?123 , 🌐 space . ⏎`, the shape every shipped bottom row has. */
    private fun spaceRow(space: Float) = listOf(
        Key("?123", width = 1.5f),
        Key(",", width = 1f),
        Key("🌐", width = 1f),
        Key(" ", action = KeyAction.Space, width = space),
        Key(".", width = 1f),
        Key("⏎", width = 1.5f),
    )

    @Test
    fun `the spacebar takes the slack a wide grid leaves the bottom row`() {
        // The Persian shape: twelve-wide letters over a bottom row written from
        // the ten-wide template, which used to draw a column of empty space at
        // each end of it.
        val rows = listOf(
            row(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            row(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            spaceRow(4f),
        )
        val filled = fillSpaceRows(rows, gridWeightOf(rows))

        assertEquals(12f, filled[2].total(), 1e-4f)
        assertEquals("only the spacebar grew", 6f, filled[2][3].width, 1e-4f)
        assertEquals(1.5f, filled[2][0].width, 1e-4f)
        assertEquals(1.5f, filled[2][5].width, 1e-4f)
    }

    @Test
    fun `a row of letters is still centred rather than stretched`() {
        // QWERTY's nine-key home row has no spacebar to give the slack to, so it
        // keeps the standard key width and the side pads.
        val rows = listOf(
            row(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            row(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
        )

        assertSame(rows, fillSpaceRows(rows, gridWeightOf(rows)))
    }

    @Test
    fun `a bottom row that already fills the grid is left alone`() {
        val rows = listOf(
            row(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            spaceRow(4f),
        )

        assertSame(rows, fillSpaceRows(rows, gridWeightOf(rows)))
    }

    @Test
    fun `a row with two spacebars keeps the centring it always had`() {
        // No single key obviously owns the slack, and centring is never wrong.
        val wide = row(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
        val two = listOf(
            Key("?123", width = 1.5f),
            Key(" ", action = KeyAction.Space, width = 3f),
            Key(" ", action = KeyAction.Space, width = 3f),
        )
        val rows = listOf(wide, wide, two)

        assertSame(rows, fillSpaceRows(rows, gridWeightOf(rows)))
    }

    @Test
    fun `an over-wide row is not narrowed`() {
        val rows = listOf(
            row(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            row(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f),
            spaceRow(6f),
        )

        assertSame(rows, fillSpaceRows(rows, gridWeightOf(rows)))
    }

    // ---- row height ----

    @Test
    fun `a row height multiplier scales the key height`() {
        assertEquals(48, rowScaledKeyHeight(48, null))
        assertEquals(48, rowScaledKeyHeight(48, 1f))
        assertEquals(72, rowScaledKeyHeight(48, 1.5f))
        assertEquals(69, rowScaledKeyHeight(48, 1.43f))
    }

    @Test
    fun `a row height multiplier is clamped to what the renderer honours`() {
        assertEquals(rowScaledKeyHeight(48, MaxRowHeightScale), rowScaledKeyHeight(48, 9f))
        assertEquals(rowScaledKeyHeight(48, MinRowHeightScale), rowScaledKeyHeight(48, 0.01f))
        assertTrue("and never rounds a row away", rowScaledKeyHeight(1, MinRowHeightScale) >= 1)
    }
}
