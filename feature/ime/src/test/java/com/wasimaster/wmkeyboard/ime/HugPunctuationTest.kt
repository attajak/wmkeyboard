package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HugPunctuationTest {

    private val marks = ".,?!;:"

    @Test
    fun `a mark after a digit belongs to the number`() {
        assertTrue(markContinuesNumber("5"))
        assertTrue(markContinuesNumber("costs 3"))
        assertTrue(markContinuesNumber("৫"))
        assertFalse(markContinuesNumber("hello"))
        assertFalse(markContinuesNumber("5 "))
        assertFalse(markContinuesNumber(""))
    }

    @Test
    fun `a space between a word and a mark is taken back`() {
        assertEquals(1, straySpacesBefore("Hey ", '.', marks))
        assertEquals(1, straySpacesBefore("yes ", ',', marks))
        assertEquals(2, straySpacesBefore("Hey  ", '!', marks))
    }

    @Test
    fun `a mark outside the list leaves the space alone`() {
        assertEquals(0, straySpacesBefore("Hey ", ')', marks))
        assertEquals(0, straySpacesBefore("Hey ", '.', ",;"))
    }

    @Test
    fun `no space means nothing to take`() {
        assertEquals(0, straySpacesBefore("Hey", '.', marks))
        assertEquals(0, straySpacesBefore("", '.', marks))
    }

    @Test
    fun `a line break or a run that fills the read is not a slip`() {
        assertEquals(0, straySpacesBefore("Hey\n ", '.', marks))
        assertEquals(0, straySpacesBefore("   ", '.', marks))
        assertEquals(0, straySpacesBefore(" ", '.', marks))
    }

    @Test
    fun `only a word or something that closes one may come before`() {
        assertEquals(0, straySpacesBefore("... ", '.', marks))
        assertEquals(0, straySpacesBefore("hi, ", ',', marks))
        assertEquals(1, straySpacesBefore("(hi) ", '.', marks))
        assertEquals(1, straySpacesBefore("\"hi\" ", ',', marks))
        assertEquals(1, straySpacesBefore("42 ", '.', marks))
    }
}
