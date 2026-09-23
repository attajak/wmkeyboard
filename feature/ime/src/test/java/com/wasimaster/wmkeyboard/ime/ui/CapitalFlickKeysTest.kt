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
    }
}
