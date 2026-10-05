package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which alternate a held finger is choosing.
 *
 * The popup is a window of its own, so this is the one piece of arithmetic that
 * has to reconcile two coordinate spaces, and it is the piece that decides
 * whether letting go types the right character. It needs no composition: the
 * geometry arrives as plain rects from the layout passes on either side.
 *
 * The fixture is a key 40 wide and 50 tall at (100, 400), with a popup of three
 * 40-wide entries sitting directly above it.
 */
class AlternatesHoldTest {

    private val reach = 24f

    private fun hold(): AlternatesHold = AlternatesHold().apply {
        // Opened first: opening clears the measurements, and the popup reports
        // them again as it lays itself out.
        open()
        cell = Rect(100f, 400f, 140f, 450f)
        popupOffset = IntOffset(60, 330)
        gridOffset = Offset(4f, 4f)
        rects = listOf(
            Rect(0f, 0f, 40f, 40f),
            Rect(40f, 0f, 80f, 40f),
            Rect(80f, 0f, 120f, 40f),
        )
    }

    /** The popup opened this frame and has not been measured: the first entry. */
    @Test
    fun `an unmeasured popup keeps the pre-selection`() {
        val hold = AlternatesHold().apply { cell = Rect(100f, 400f, 140f, 450f) }
        assertEquals(0, hold.indexAt(Offset(20f, 25f), reach))
    }

    /** A finger inside an entry chooses it, whatever is nearby. */
    @Test
    fun `a finger on an entry chooses it`() {
        // Entry 1 spans x 104..144 in the window; the key's own left edge is 100.
        assertEquals(1, hold().indexAt(Offset(20f, -60f), reach))
    }

    /**
     * The finger that opened the popup is still on the key, well below every
     * entry. It has to be choosing the one it pre-selected, or a plain hold and
     * release would type nothing at all.
     */
    @Test
    fun `a finger resting on the key still chooses the nearest entry`() {
        assertEquals(0, hold().indexAt(Offset(2f, 25f), reach))
    }

    /** Sliding sideways under the popup walks the entries. */
    @Test
    fun `sliding under the popup follows the columns`() {
        assertEquals(2, hold().indexAt(Offset(90f, 25f), reach))
    }

    /**
     * A finger that has not gone anywhere keeps the pre-selection, whatever is
     * above it. The popup is centred over the key, so the entry the finger
     * happens to sit under is the middle one: without this, how still your hand
     * was would decide which character a plain hold typed.
     */
    @Test
    fun `the pre-selection survives a jitter`() {
        val hold = hold()
        hold.moveTo(Offset(20f, 25f), reach, steerPx = 12f)
        assertEquals(0, hold.selected.intValue)
        // Past the steering distance it follows the finger, and keeps following
        // it back again — to the entry it started on, wherever that is drawn.
        hold.moveTo(Offset(90f, 25f), reach, steerPx = 12f)
        assertEquals(2, hold.selected.intValue)
        hold.moveTo(Offset(22f, 25f), reach, steerPx = 12f)
        assertEquals(0, hold.selected.intValue)
    }

    /**
     * Issue #532: the finger starts over the middle entry of a popup that was
     * not able to put its first entry over the key. Matched where it stood,
     * the first slide jumped straight to that middle entry. It steers from
     * where it started instead, so a slide one entry wide moves one entry.
     */
    @Test
    fun `a slide moves one entry at a time from the first`() {
        val hold = hold()
        // Entry 1 (104..144 in the window) is over the finger at x 120.
        hold.moveTo(Offset(20f, 25f), reach, steerPx = 12f)
        // Past the steering distance but less than half an entry along.
        hold.moveTo(Offset(34f, 25f), reach, steerPx = 12f)
        assertEquals(0, hold.selected.intValue)
        // One entry's width along.
        hold.moveTo(Offset(60f, 25f), reach, steerPx = 12f)
        assertEquals(1, hold.selected.intValue)
    }

    /** The entry the finger is aimed at draws biggest, and the far one at rest. */
    @Test
    fun `the aimed-at entry is magnified`() {
        val hold = hold()
        assertEquals(1f, hold.magnification(1), 0f)
        hold.moveTo(Offset(20f, 25f), reach, steerPx = 12f)
        hold.moveTo(Offset(60f, 25f), reach, steerPx = 12f)
        assertEquals(true, hold.magnification(1) > hold.magnification(0))
        assertEquals(1f, hold.magnification(2), 0.01f)
    }

    /** Room on the right: the popup opens rightward from the key. */
    @Test
    fun `a popup with room opens rightward from the key`() {
        assertEquals(80 to false, alternatesAnchoredX(keyX = 100, lead = 20, width = 200, windowWidth = 1000, margin = 8))
    }

    /** No room on the right: the rows run leftward, the first entry still over the key. */
    @Test
    fun `a popup by the right edge runs leftward`() {
        assertEquals(720 to true, alternatesAnchoredX(keyX = 900, lead = 20, width = 200, windowWidth = 1000, margin = 8))
    }

    /** Far enough away is a deliberate move off, and commits nothing. */
    @Test
    fun `sliding away from the popup chooses nothing`() {
        assertEquals(-1, hold().indexAt(Offset(20f, 200f), reach))
        assertEquals(-1, hold().indexAt(Offset(-200f, 0f), reach))
    }
}
