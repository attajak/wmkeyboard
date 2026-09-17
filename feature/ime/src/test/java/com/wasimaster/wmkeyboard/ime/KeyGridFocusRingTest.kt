package com.wasimaster.wmkeyboard.ime

import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.ime.ui.KeyGridFocus
import com.wasimaster.wmkeyboard.ime.ui.KeyRects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring as a remote drives it: seeding, typing, and the held centre button
 * that opens a key's alternates — which is the only route a D-pad has to an
 * accented character.
 */
class KeyGridFocusRingTest {

    private val accented = Key("e", longPress = listOf("é", "è", "ê"))
    private val plain = Key("k")
    private val backspace = Key("⌫", action = KeyAction.Delete)

    private val typed = mutableListOf<String>()

    /** A one-row board: an accented letter, a plain letter, backspace. */
    private fun focus(): KeyGridFocus {
        val rects = KeyRects()
        val generation = Any()
        rects.record(generation, accented, Rect(0f, 0f, 100f, 60f))
        rects.record(generation, plain, Rect(100f, 0f, 200f, 60f))
        rects.record(generation, backspace, Rect(200f, 0f, 300f, 60f))
        return KeyGridFocus().apply { publish(rects) { key -> typed += key.label } }
    }

    @Test
    fun `the first arrow key puts the ring up without typing`() {
        val focus = focus()
        assertTrue(focus.move(0, -1))
        assertTrue(focus.showing)
        assertEquals(emptyList<String>(), typed)
    }

    @Test
    fun `the centre button types the ringed key`() {
        val focus = focus()
        focus.move(0, 1)
        focus.move(1, 0)
        assertTrue(focus.press())
        assertEquals(1, typed.size)
    }

    @Test
    fun `with no ring up there is nothing to press`() {
        assertFalse(focus().press())
        assertEquals(emptyList<String>(), typed)
    }

    @Test
    fun `a press types on the release, the way a finger does`() {
        val focus = focus()
        focus.move(0, 1)
        assertTrue(focus.armPress())
        assertEquals("nothing on the way down", emptyList<String>(), typed)
        assertTrue(focus.releasePress())
        assertEquals(1, typed.size)
    }

    @Test
    fun `a hold that opens alternates types nothing when it is released`() {
        val focus = focus()
        focus.move(0, 1)
        while (focus.cell.value?.left != 0f) focus.move(-1, 0)
        focus.armPress()
        assertTrue(focus.holdPress())
        assertTrue(focus.alternatesOpen)
        assertTrue(focus.releasePress())
        assertEquals("the hold was the point, not the letter", emptyList<String>(), typed)
    }

    @Test
    fun `a hold on a repeating key repeats, and the release adds nothing`() {
        val focus = focus()
        focus.move(0, 1)
        while (focus.cell.value?.left != 200f) focus.move(1, 0)
        focus.armPress()
        focus.holdPress()
        focus.repeatPress()
        focus.repeatPress()
        assertEquals(3, typed.size)
        focus.releasePress()
        assertEquals(3, typed.size)
    }

    @Test
    fun `a release with no press behind it does nothing`() {
        assertFalse(focus().releasePress())
    }

    @Test
    fun `holding the centre button opens the ringed key's alternates`() {
        val focus = focus()
        focus.move(0, 1)
        while (focus.cell.value?.left != 0f) focus.move(-1, 0)
        assertTrue(focus.openAlternates())
        assertTrue(focus.alternatesOpen)
        // Pre-selected, so the first commit needs no arrow key first.
        assertEquals(0, focus.hold.selected.intValue)
    }

    @Test
    fun `a key with no alternates opens nothing`() {
        val focus = focus()
        focus.move(0, 1)
        while (focus.cell.value?.left != 100f) focus.move(1, 0)
        assertFalse(focus.openAlternates())
        assertFalse(focus.alternatesOpen)
    }

    @Test
    fun `a repeating key says so, so the hold repeats instead`() {
        val focus = focus()
        focus.move(0, 1)
        while (focus.cell.value?.left != 200f) focus.move(1, 0)
        assertTrue(focus.repeatsOnHold())
        assertFalse(focus.openAlternates())
    }

    @Test
    fun `arrows walk the popup's own entries`() {
        val focus = openedPopup()
        assertTrue(focus.moveAlternates(1, 0))
        assertEquals(1, focus.hold.selected.intValue)
        assertTrue(focus.moveAlternates(1, 0))
        assertEquals(2, focus.hold.selected.intValue)
        assertTrue(focus.moveAlternates(-1, 0))
        assertEquals(1, focus.hold.selected.intValue)
    }

    @Test
    fun `down out of the bottom row cancels, the way sliding back to the key does`() {
        val focus = openedPopup()
        assertTrue(focus.moveAlternates(0, 1))
        assertFalse(focus.alternatesOpen)
        assertEquals(emptyList<String>(), typed)
    }

    @Test
    fun `the popup's row wraps, like the board's rows do`() {
        val focus = openedPopup()
        assertTrue(focus.moveAlternates(-1, 0))
        assertEquals("left from the first entry reaches the last", 2, focus.hold.selected.intValue)
        assertTrue(focus.alternatesOpen)
    }

    @Test
    fun `an arrow with nowhere to go inside the popup is still swallowed`() {
        val focus = focus()
        focus.move(0, 1)
        while (focus.cell.value?.left != 0f) focus.move(-1, 0)
        focus.openAlternates()
        // One entry, laid out: there is nowhere to go, and the app behind must
        // not see the key either.
        focus.hold.rects = listOf(Rect(0f, 0f, 40f, 40f))
        assertTrue(focus.moveAlternates(1, 0))
        assertTrue(focus.alternatesOpen)
        assertEquals(0, focus.hold.selected.intValue)
    }

    @Test
    fun `the centre button commits the highlighted entry`() {
        val focus = openedPopup()
        val committed = mutableListOf<Int>()
        focus.hold.onCommit = { committed += it }
        focus.moveAlternates(1, 0)
        assertTrue(focus.commitAlternates())
        assertEquals(listOf(1), committed)
        assertFalse(focus.alternatesOpen)
    }

    @Test
    fun `taking the ring down closes the popup with it`() {
        val focus = openedPopup()
        focus.clear()
        assertFalse(focus.alternatesOpen)
        assertNull(focus.cell.value)
    }

    /** The ring on the accented key, popup open and placed as three entries in a row. */
    private fun openedPopup(): KeyGridFocus {
        val focus = focus()
        focus.move(0, 1)
        while (focus.cell.value?.left != 0f) focus.move(-1, 0)
        focus.openAlternates()
        // What AlternatesGrid publishes once it has been laid out.
        focus.hold.rects = listOf(
            Rect(0f, 0f, 40f, 40f),
            Rect(40f, 0f, 80f, 40f),
            Rect(80f, 0f, 120f, 40f),
        )
        return focus
    }
}
