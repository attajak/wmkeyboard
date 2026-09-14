package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.prediction.OctopusWord
import com.wasimaster.wmkeyboard.core.settings.OctopusPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where each floating word lands (discussion #102). The geometry is pure so the
 * awkward cases — the top row hanging off the board, a word too wide for its
 * key, two words reaching for the same space — can be checked without a
 * keyboard, a font or a frame.
 */
class OctopusPlacementTest {

    private val band = 20f

    /** Three quarters of the band clears the key; the rest overlaps it. */
    private val straddle = 0.75f
    private val gapV = 2f
    /** A key's width either side, as the overlay gives it. */
    private val overhang = 100f
    private val board = Size(300f, 200f)

    /** Row 1 across the top, row 2 under it; three 100-wide keys each. */
    private val cells = mapOf(
        'q'.code to Rect(0f, 0f, 100f, 100f),
        'w'.code to Rect(100f, 0f, 200f, 100f),
        'e'.code to Rect(200f, 0f, 300f, 100f),
        'a'.code to Rect(0f, 100f, 100f, 200f),
        's'.code to Rect(100f, 100f, 200f, 200f),
        'd'.code to Rect(200f, 100f, 300f, 200f),
    )

    private fun word(key: Char, text: String, rank: Int = 0, typed: Int = 0, tier: Int = 0) =
        OctopusWord(key.code, text, typed, OctopusKind.COMPLETION, rank, tier)

    // ---- a stacked key (#136) ----

    @Test
    fun `a second-tier word floats one band further from its key`() {
        val stacked = slots(word('s', "hello"), word('s', "help", rank = 1, tier = 1))
        assertEquals(2, stacked.size)
        val (near, far) = stacked
        assertEquals(near.area.top - band, far.area.top, 0f)
        assertEquals("both centred on the key", near.area.center.x, far.area.center.x, 0f)
    }

    @Test
    fun `inside the key the stack grows downward, away from the letter`() {
        val stacked = slots(
            word('s', "hello"), word('s', "help", rank = 1, tier = 1),
            placement = OctopusPlacement.IN_KEY,
        )
        val (near, far) = stacked
        assertEquals(near.area.top + band, far.area.top, 0f)
    }

    private fun slots(
        vararg words: OctopusWord,
        placement: OctopusPlacement = OctopusPlacement.FLOAT,
        minScale: Float = 0.72f,
        bounds: Map<Int, Rect> = cells,
        /** Ten pixels a character, so a word's width is readable in the test. */
        widthOf: (String) -> Float = { it.length * 10f },
    ) = octopusSlots(
        words = words.toList(),
        bounds = bounds,
        placement = placement,
        bandHeightPx = band,
        straddle = straddle,
        gapVPx = gapV,
        maxOverhangPx = overhang,
        boardSize = board,
        minScale = minScale,
        widthOf = widthOf,
    )

    // ---- the three placements ----

    @Test
    fun `floating sits in the band above the key`() {
        val slot = slots(word('s', "hello")).single()
        assertEquals(100f - band * straddle, slot.area.top, 0f)
        assertTrue("and straddles its key rather than clearing it", slot.area.bottom > 100f)
        assertEquals("centred on its key", 150f, slot.area.center.x, 0f)
    }

    @Test
    fun `a reserved lane is the same band, because the lane moved the key`() {
        // STRIP does not move the words; it moves the cells, and the renderer
        // reports the moved ones. The arithmetic here must stay identical or
        // the two would disagree about where the lane is.
        assertEquals(
            slots(word('s', "hello"), placement = OctopusPlacement.FLOAT).single().area,
            slots(word('s', "hello"), placement = OctopusPlacement.STRIP).single().area,
        )
    }

    @Test
    fun `inside the key draws under the key's top edge`() {
        val slot = slots(word('s', "hello"), placement = OctopusPlacement.IN_KEY).single()
        assertEquals(100f + gapV, slot.area.top, 0f)
        assertTrue("wholly inside its key", slot.area.bottom <= 200f)
    }

    // ---- the top row ----

    /**
     * Issue #171: a band hanging over the strip is a band the strip gets the
     * tap for, so the top row's word is held inside the board and sits a
     * little lower than the rows below it.
     */
    @Test
    fun `a top-row word is held inside the board and is tapped on the whole of it`() {
        val slot = slots(word('w', "hello")).single()
        assertEquals("pulled down to the board's own edge", 0f, slot.area.top, 0f)
        assertEquals("and the band keeps its height", band, slot.area.height, 0f)
        assertEquals("so the whole word can be tapped", slot.area, slot.hit)
    }

    @Test
    fun `a lower row's word still straddles its key`() {
        val slot = slots(word('s', "hello")).single()
        assertEquals("unmoved by the top row's clamp", 100f - band * straddle, slot.area.top, 0f)
        assertEquals(slot.area, slot.hit)
    }

    // ---- fitting ----

    @Test
    fun `a word may lean well past its own key`() {
        // The reference boards let a long word run over its neighbours; a word
        // held to one key's width is a word that vanishes at eight letters.
        val slot = slots(word('s', "everything!")).single()
        assertEquals("full size, it fits inside the overhang", 1f, slot.scale, 0f)
        assertTrue("and reaches past its own key", slot.area.width > 100f)
    }

    @Test
    fun `a long word shrinks rather than disappearing`() {
        // The bug this pins: a word wider than its key plus the overhang was
        // simply dropped, so "Downloaded" over a narrow key drew nothing at
        // all — the board looked empty exactly when the strip had most to say.
        // 350 wide against a 100 key plus a key's overhang either side: 300.
        val long = slots(word('s', "d".repeat(35))).single()
        assertTrue("shrunk", long.scale < 1f)
        assertTrue("but still readable", long.scale >= 0.72f)
        assertEquals("and fitted to the space it was given", 300f, long.area.width, 0.5f)
    }

    @Test
    fun `a word that will not fit even at its smallest is dropped`() {
        // Past the floor it would be unreadable, and a wall of unreadable
        // words says less than a bare board.
        assertTrue(slots(word('s', "a".repeat(60))).isEmpty())
    }

    @Test
    fun `a word on an edge key is pulled inside the board`() {
        val left = slots(word('a', "everything!")).single()
        assertEquals(0f, left.area.left, 0f)
        assertEquals("and keeps its full width", 110f, left.area.width, 0.01f)
        val right = slots(word('d', "everything!")).single()
        assertEquals(board.width, right.area.right, 0f)
    }

    // ---- collisions ----

    @Test
    fun `two words that would cover each other keep the better one`() {
        // Wide enough that the two land almost on top of one another.
        val kept = slots(
            word('s', "s".repeat(30), rank = 1),
            word('a', "a".repeat(30), rank = 0),
        )
        assertEquals(1, kept.size)
        assertEquals('a'.code, kept.single().word.keyCodePoint)
    }

    @Test
    fun `words that merely touch both stay`() {
        // A little overlap is what the reference boards look like. Dropping on
        // any contact at all is what left the board emptier than the strip.
        val kept = slots(word('a', "aaaaaaaaaaa"), word('d', "ddddddddddd"))
        assertEquals(2, kept.size)
    }

    @Test
    fun `words that do not touch both stay`() {
        val kept = slots(word('a', "and"), word('d', "did"))
        assertEquals(2, kept.size)
    }

    @Test
    fun `words on different rows do not collide`() {
        val kept = slots(word('w', "everything!"), word('s', "everything!"))
        assertEquals(2, kept.size)
    }

    // ---- absent geometry ----

    @Test
    fun `a key that has not reported its bounds yields no slot`() {
        assertTrue(slots(word('z', "zebra")).isEmpty())
    }

    @Test
    fun `a word that measures to nothing yields no slot`() {
        assertTrue(slots(word('s', "hello"), widthOf = { 0f }).isEmpty())
    }

    @Test
    fun `no bounds at all yields nothing`() {
        assertTrue(slots(word('s', "hello"), bounds = emptyMap()).isEmpty())
    }

    // ---- the hit-test table ----

    @Test
    fun `the rect table answers by point and by key`() {
        val rects = OctopusRects()
        rects.publish(slots(word('a', "and"), word('d', "did")))
        assertEquals("and", rects.wordAt(Offset(50f, 90f))?.word)
        assertEquals("did", rects.wordFor('d'.code)?.word)
        assertNull("the gap between two words picks nothing", rects.wordAt(Offset(150f, 90f)))
        assertNull(rects.wordFor('s'.code))
    }

    @Test
    fun `a table from a previous layout answers nothing`() {
        // The trap KeyRects documents: a stale table hit-tests the finger
        // against a board that is no longer on the screen.
        val rects = OctopusRects()
        val measuredAgainst = Any()
        val afterTheLayoutChanged = Any()
        rects.publish(slots(word('a', "and")), gridToken = measuredAgainst)
        assertNull(rects.wordAt(Offset(50f, 90f), gridToken = afterTheLayoutChanged))
        assertNull(rects.wordFor('a'.code, gridToken = afterTheLayoutChanged))
        assertEquals("and", rects.wordAt(Offset(50f, 90f), gridToken = measuredAgainst)?.word)
    }
}
