package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An editor emptying the field under a live composition ends it (#192). */
class CompositionCannotPrecedeCaretTest {

    @Test
    fun `a clear button landing the caret at zero ends a six-letter composition`() {
        // "Matrix" composed at 0..6, the app's x calls setText("").
        assertTrue(compositionCannotPrecedeCaret(oldSelStart = 6, newSelStart = 0, newSelEnd = 0, candidatesStart = -1, composedLength = 6))
    }

    @Test
    fun `a reported composing region is the other rule's business`() {
        assertFalse(compositionCannotPrecedeCaret(6, 0, 0, candidatesStart = 0, composedLength = 6))
    }

    @Test
    fun `a stale echo behind fast typing moves forward and is left alone`() {
        // The echo for "a" arrives once the buffer is already "ab".
        assertFalse(compositionCannotPrecedeCaret(oldSelStart = 0, newSelStart = 1, newSelEnd = 1, candidatesStart = -1, composedLength = 2))
    }

    @Test
    fun `a backspace echo never lands short of the shorter buffer`() {
        // "abc" -> "ab" -> "a" typed fast; the first echo arrives with the buffer at "a".
        assertFalse(compositionCannotPrecedeCaret(oldSelStart = 3, newSelStart = 2, newSelEnd = 2, candidatesStart = -1, composedLength = 1))
        // The last one, at rest.
        assertFalse(compositionCannotPrecedeCaret(oldSelStart = 2, newSelStart = 1, newSelEnd = 1, candidatesStart = -1, composedLength = 1))
    }

    @Test
    fun `a range selection and a caret past the text are not this case`() {
        assertFalse(compositionCannotPrecedeCaret(6, 0, 6, candidatesStart = -1, composedLength = 6))
        assertFalse(compositionCannotPrecedeCaret(6, 9, 9, candidatesStart = -1, composedLength = 6))
    }

    @Test
    fun `a composition later in the field is ended by the same clear`() {
        // "Hello " then "Matrix" composed at 6..12; the field is emptied.
        assertTrue(compositionCannotPrecedeCaret(oldSelStart = 12, newSelStart = 0, newSelEnd = 0, candidatesStart = -1, composedLength = 6))
        // ...and by a shorter replacement that still leaves the caret short of the word.
        assertTrue(compositionCannotPrecedeCaret(oldSelStart = 12, newSelStart = 4, newSelEnd = 4, candidatesStart = -1, composedLength = 6))
    }
}
