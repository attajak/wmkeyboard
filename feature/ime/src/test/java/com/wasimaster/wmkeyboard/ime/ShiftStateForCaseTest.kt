package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The case the keys take when shift re-cases a selection (#525).
 *
 * The cycle the shift key walks is lower → Title → UPPER, so each step has to
 * land on the shift state that would have typed it. What matters as much is
 * where the mapping declines: text with no case of its own must leave the keys
 * exactly where the user left them.
 */
class ShiftStateForCaseTest {

    @Test
    fun `each step of the cycle maps to the shift that would have typed it`() {
        assertEquals(ShiftState.OFF, shiftStateForCase("hello"))
        assertEquals(ShiftState.ON, shiftStateForCase("Hello"))
        assertEquals(ShiftState.CAPS_LOCK, shiftStateForCase("HELLO"))
    }

    @Test
    fun `several words follow the same three steps`() {
        assertEquals(ShiftState.OFF, shiftStateForCase("hello there world"))
        assertEquals(ShiftState.ON, shiftStateForCase("Hello There World"))
        assertEquals(ShiftState.CAPS_LOCK, shiftStateForCase("HELLO THERE WORLD"))
    }

    @Test
    fun `a single letter is the one-shot shift, never the lock`() {
        // Its own cycle is a → A → a, so the lock would have nowhere to go.
        assertEquals(ShiftState.OFF, shiftStateForCase("a"))
        assertEquals(ShiftState.ON, shiftStateForCase("A"))
    }

    @Test
    fun `digits and punctuation ride along with the letters`() {
        assertEquals(ShiftState.OFF, shiftStateForCase("abc123"))
        assertEquals(ShiftState.CAPS_LOCK, shiftStateForCase("ABC-123"))
    }

    @Test
    fun `text with no case of its own leaves the keys alone`() {
        assertNull("Bengali", shiftStateForCase("আমি"))
        assertNull("a hex colour", shiftStateForCase("#336699"))
        assertNull("digits", shiftStateForCase("2026"))
        assertNull("nothing selected", shiftStateForCase(""))
    }

    @Test
    fun `a mixed form declines, because the cycle normalizes it rather than stepping`() {
        assertNull(shiftStateForCase("hELLo"))
        assertNull(shiftStateForCase("hello World"))
    }
}
