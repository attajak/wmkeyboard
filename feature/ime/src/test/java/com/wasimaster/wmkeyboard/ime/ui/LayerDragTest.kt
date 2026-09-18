package com.wasimaster.wmkeyboard.ime.ui

import android.view.KeyEvent
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyRole
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer drag of issue #108: dragging off `?123` or `ABC` shows that layer
 * while the finger is down and types the key it lifts on. The gesture itself
 * needs a device; what it decides — which layer to show, which keys start it,
 * which keys a lift may commit, and the capital its popup can offer — does not.
 */
class LayerDragTest {

    private val symbols = Key("?123", action = KeyAction.Symbols)
    private val letters = Key("ABC", action = KeyAction.Letters)

    // ---- layerDragMode ------------------------------------------------------

    @Test
    fun `dragging off the symbols key shows the symbols layer`() {
        assertEquals(LayoutMode.SYMBOLS, layerDragMode(symbols, LayoutMode.LETTERS))
    }

    /** The mapping a tap takes, so the drag never lands somewhere a tap could not. */
    @Test
    fun `the symbols key steps to the second page from the first`() {
        assertEquals(LayoutMode.SYMBOLS_SHIFTED, layerDragMode(symbols, LayoutMode.SYMBOLS))
        assertEquals(LayoutMode.SYMBOLS, layerDragMode(symbols, LayoutMode.SYMBOLS_SHIFTED))
    }

    @Test
    fun `the symbols key leaves the fn and secondary layers for the symbols`() {
        assertEquals(LayoutMode.SYMBOLS, layerDragMode(symbols, LayoutMode.FN))
        assertEquals(LayoutMode.SYMBOLS, layerDragMode(symbols, LayoutMode.SECONDARY))
    }

    @Test
    fun `dragging off an ABC key shows the letters`() {
        assertEquals(LayoutMode.LETTERS, layerDragMode(letters, LayoutMode.SECONDARY))
        assertEquals(LayoutMode.LETTERS, layerDragMode(letters, LayoutMode.SYMBOLS))
    }

    /** Nothing to look through to, so the stroke is left to whatever else wants it. */
    @Test
    fun `an ABC key on the letters starts nothing`() {
        assertNull(layerDragMode(letters, LayoutMode.LETTERS))
    }

    @Test
    fun `every other key starts nothing`() {
        assertNull(layerDragMode(Key("a"), LayoutMode.LETTERS))
        assertNull(layerDragMode(Key("", action = KeyAction.Shift), LayoutMode.LETTERS))
        assertNull(layerDragMode(Key("", action = KeyAction.Emoji), LayoutMode.LETTERS))
        assertNull(layerDragMode(Key("L", action = KeyAction.Layout("l1")), LayoutMode.LETTERS))
        assertNull(layerDragMode(null, LayoutMode.LETTERS))
    }

    // ---- panelLayerDragMode (issue #210) ------------------------------------

    /** A panel's `?123` takes the same step a tap on it would. */
    @Test
    fun `a panel's symbols key shows the layer a tap would reach`() {
        assertEquals(LayoutMode.SYMBOLS, panelLayerDragMode(symbols, LayoutMode.LETTERS))
        assertEquals(LayoutMode.SYMBOLS_SHIFTED, panelLayerDragMode(symbols, LayoutMode.SYMBOLS))
    }

    /** A panel is not a layer of the grid, so the letters are always somewhere to look. */
    @Test
    fun `a panel's ABC key shows the letters even over the letters`() {
        assertEquals(LayoutMode.LETTERS, panelLayerDragMode(letters, LayoutMode.LETTERS))
        assertEquals(LayoutMode.LETTERS, panelLayerDragMode(letters, LayoutMode.SYMBOLS))
    }

    @Test
    fun `every other panel key starts nothing`() {
        assertNull(panelLayerDragMode(Key("a"), LayoutMode.LETTERS))
        assertNull(panelLayerDragMode(Key("", action = KeyAction.Shift), LayoutMode.LETTERS))
        assertNull(panelLayerDragMode(null, LayoutMode.LETTERS))
    }

    // ---- startsLayerDrag and ownsDrag ---------------------------------------

    @Test
    fun `the mode keys start a layer drag and nothing else does`() {
        assertTrue(symbols.startsLayerDrag())
        assertTrue(letters.startsLayerDrag())
        assertFalse(Key("a").startsLayerDrag())
        assertFalse(Key("", action = KeyAction.Shift).startsLayerDrag())
        assertFalse(null.startsLayerDrag())
    }

    /**
     * The one question glide typing, handwriting and the octopus ask at the
     * down: chords (#67) and layer drags (#108) both own their stroke, and a
     * letter owns none of it.
     */
    @Test
    fun `the grid owns a drag off a modifier or a mode key`() {
        assertTrue(symbols.ownsDrag())
        assertTrue(letters.ownsDrag())
        assertTrue(Key("", action = KeyAction.Shift).ownsDrag())
        assertTrue(Key("Ctrl", action = KeyAction.Mod(ModifierKey.CTRL)).ownsDrag())
        assertFalse(Key("a").ownsDrag())
        assertFalse(null.ownsDrag())
    }

    /**
     * The possessive key keeps glide and ink off its drags (#169), but only the
     * key the setting names and only while it names one: a comma beside `s` on
     * an unusual layout started a glide and the swipe decoded as a word.
     */
    @Test
    fun `only the chosen possessive key starts its swipe`() {
        val comma = Key(",", role = KeyRole.Comma)
        assertTrue(comma.startsPossessiveSwipe(','))
        assertTrue(Key("\u2019", output = "'").startsPossessiveSwipe('\''))
        assertFalse(comma.startsPossessiveSwipe('.'))
        assertFalse(comma.startsPossessiveSwipe(null))
        assertFalse(Key("s").startsPossessiveSwipe(','))
        assertFalse(Key(",", action = KeyAction.Shift).startsPossessiveSwipe(','))
        assertFalse(null.startsPossessiveSwipe(','))
    }

    /**
     * The delete keys keep glide, ink and the octopus off their drags (#243),
     * each only while its own swipe is on: backspace beside the bottom letter
     * row started a glide that typed a word on top of the swipe's deletion.
     */
    @Test
    fun `a delete key starts its swipe only while that swipe is on`() {
        val backspace = Key("", action = KeyAction.Delete)
        val forward = Key("", action = KeyAction.ForwardDelete)
        assertTrue(backspace.startsDeleteSwipe(backspace = true, forward = false))
        assertFalse(backspace.startsDeleteSwipe(backspace = false, forward = true))
        assertTrue(forward.startsDeleteSwipe(backspace = false, forward = true))
        assertFalse(forward.startsDeleteSwipe(backspace = true, forward = false))
        assertFalse(Key("m").startsDeleteSwipe(backspace = true, forward = true))
        assertFalse(null.startsDeleteSwipe(backspace = true, forward = true))
    }

    // ---- commitsFromLayerDrag -----------------------------------------------

    @Test
    fun `a lift types the keys that write something`() {
        assertTrue(Key("@").commitsFromLayerDrag())
        assertTrue(Key(" ", action = KeyAction.Space).commitsFromLayerDrag())
        assertTrue(Key("", action = KeyAction.Enter).commitsFromLayerDrag())
        assertTrue(Key("", action = KeyAction.Delete).commitsFromLayerDrag())
        assertTrue(
            Key("⇥", action = KeyAction.SendKey(KeyEvent.KEYCODE_TAB)).commitsFromLayerDrag(),
        )
    }

    /**
     * The keys that would move the board instead. The `ABC` key matters most:
     * it is the one sitting where the drag began, so a finger that slides back
     * to where it started types nothing — which is how every other key on this
     * keyboard cancels.
     */
    @Test
    fun `a lift on a key that only moves the board types nothing`() {
        assertFalse(letters.commitsFromLayerDrag())
        assertFalse(symbols.commitsFromLayerDrag())
        assertFalse(Key("", action = KeyAction.Shift).commitsFromLayerDrag())
        assertFalse(Key("", action = KeyAction.CapsLock).commitsFromLayerDrag())
        assertFalse(Key("", action = KeyAction.Emoji).commitsFromLayerDrag())
        assertFalse(Key("", action = KeyAction.Numpad).commitsFromLayerDrag())
        assertFalse(Key("🌐", action = KeyAction.LanguageSwitch).commitsFromLayerDrag())
        assertFalse(Key("L", action = KeyAction.Layout("l1")).commitsFromLayerDrag())
        assertFalse(Key("", action = KeyAction.Tool(ToolbarTool.EMOJI)).commitsFromLayerDrag())
        assertFalse(Key("Alt", action = KeyAction.Mod(ModifierKey.ALT)).commitsFromLayerDrag())
    }

    // ---- shiftedAlternate ---------------------------------------------------

    @Test
    fun `a letter offers its capital`() {
        assertEquals("A", shiftedAlternate(Key("a")))
    }

    /** The layout's own shifted form wins: the scripts where it is another character. */
    @Test
    fun `the shift label wins over uppercasing`() {
        assertEquals("খ", shiftedAlternate(Key("ক", shiftLabel = "খ")))
        assertEquals("\"", shiftedAlternate(Key("'", shiftLabel = "\"")))
    }

    @Test
    fun `a key with no shifted form of its own offers nothing`() {
        assertNull(shiftedAlternate(Key("1")))
        assertNull(shiftedAlternate(Key(".")))
        // A multi-character output has no capital worth the name.
        assertNull(shiftedAlternate(Key(".com", output = ".com")))
        // Already a capital: uppercasing it lands on itself.
        assertNull(shiftedAlternate(Key("A")))
    }

    /** A layout that already lists the capital keeps it where it put it. */
    @Test
    fun `a capital the key already offers is not added twice`() {
        assertNull(shiftedAlternate(Key("a", longPress = listOf("A", "à"))))
    }

    /** Keyed on what the key types, not on what it draws. */
    @Test
    fun `the output rather than the label decides`() {
        assertEquals("B", shiftedAlternate(Key("β", output = "b")))
    }
}
