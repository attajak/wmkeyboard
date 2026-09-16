package com.wasimaster.wmkeyboard.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class WordDeleteTest {

    private fun len(text: String) = WordDelete.lengthBefore(text)

    @Test
    fun `word directly before the cursor`() {
        assertEquals("world".length, len("hello world"))
    }

    @Test
    fun `trailing space goes with the word before it`() {
        assertEquals("world ".length, len("hello world "))
        assertEquals("world   ".length, len("hello world   "))
        // And the step after that takes the rest.
        assertEquals("hello".length, len("hello"))
    }

    @Test
    fun `punctuation is its own step`() {
        assertEquals("...".length, len("wait..."))
        assertEquals("wait".length, len("wait"))
        // Contractions split rather than swallowing the line.
        assertEquals("t".length, len("don't"))
    }

    @Test
    fun `newlines count as whitespace`() {
        assertEquals("line\n".length, len("first line\n"))
    }

    @Test
    fun `nothing to delete`() {
        assertEquals(0, len(""))
        // Leading whitespace only: take it and stop.
        assertEquals(3, len("   "))
    }

    // ---- forward, for the forward delete key (issue #226) ----

    private fun after(text: String) = WordDelete.lengthAfter(text)

    @Test
    fun `word directly after the cursor`() {
        assertEquals("hello".length, after("hello world"))
    }

    @Test
    fun `leading space goes with the word after it`() {
        assertEquals(" world".length, after(" world"))
        assertEquals("   world".length, after("   world"))
        assertEquals("world".length, after("world"))
    }

    @Test
    fun `punctuation after the cursor is its own step`() {
        assertEquals("...".length, after("...wait"))
        assertEquals("wait".length, after("wait"))
        // Contractions split the same way going forward.
        assertEquals("don".length, after("don't"))
    }

    @Test
    fun `newlines count as whitespace going forward`() {
        assertEquals("\nlast".length, after("\nlast line"))
    }

    @Test
    fun `nothing to delete forward`() {
        assertEquals(0, after(""))
        assertEquals(3, after("   "))
    }

    @Test
    fun `the two directions are mirrors`() {
        // The same field read from either side: a cursor at the very start
        // takes " hello" going forward, and a cursor at the very end takes
        // "world " going back. Both carry the whitespace they touch.
        assertEquals(" hello".length, after(" hello world "))
        assertEquals("world ".length, len(" hello world "))
    }
}
