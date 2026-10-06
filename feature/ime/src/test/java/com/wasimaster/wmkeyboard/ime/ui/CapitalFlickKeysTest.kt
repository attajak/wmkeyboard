package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a flick up off a key types: its shifted form, or nothing. */
class CapitalFlickKeysTest {

    @Test
    fun `a letter types its capital`() {
        assertEquals("Q", Key(label = "q").capitalFlickText())
        assertEquals("É", Key(label = "é").capitalFlickText())
    }

    @Test
    fun `a key's own shifted character wins`() {
        assertEquals("!", Key(label = "1", shiftLabel = "!").capitalFlickText())
    }

    @Test
    fun `the output is what is cased, not the label`() {
        assertEquals("Ñ", Key(label = "n~", output = "ñ").capitalFlickText())
    }

    @Test
    fun `a key whose shift changes nothing takes no flick`() {
        assertNull(Key(label = "1").capitalFlickText())
        assertNull(Key(label = "ক").capitalFlickText())
        assertNull(Key(label = "Q").capitalFlickText())
    }

    @Test
    fun `keys with drags of their own keep them`() {
        assertNull(Key(label = "space", action = KeyAction.Space).capitalFlickText())
        assertNull(Key(label = "⇧", action = KeyAction.Shift).capitalFlickText())
        assertNull(Key(label = "?123", action = KeyAction.Symbols).capitalFlickText())
        assertNull(Key(label = "か", flick = mapOf(FlickDirection.UP to "く")).capitalFlickText())
        assertNull(Key(label = "a", flick = mapOf(FlickDirection.DOWN_RIGHT to "v")).capitalFlickText())
    }

    @Test
    fun `a swipe out to an arm and back types that arm's capital`() {
        // Issue #410: the arm's own shift form first, else its capital; an arm
        // whose shift changes nothing has no return form, so the return stays
        // the kana pad's cancel.
        val key = Key(
            label = "o",
            flick = mapOf(FlickDirection.UP_LEFT to "q", FlickDirection.DOWN to "1", FlickDirection.UP to "ß"),
            flickShift = mapOf(FlickDirection.UP to "ẞ"),
        )
        assertEquals("Q", key.flickShiftedText(FlickDirection.UP_LEFT))
        assertEquals("ẞ", key.flickShiftedText(FlickDirection.UP))
        assertNull(key.flickShiftedText(FlickDirection.DOWN))
        assertNull(key.flickShiftedText(FlickDirection.LEFT))
        assertNull(Key(label = "あ", flick = mapOf(FlickDirection.LEFT to "い")).flickShiftedText(FlickDirection.LEFT))
    }
}
