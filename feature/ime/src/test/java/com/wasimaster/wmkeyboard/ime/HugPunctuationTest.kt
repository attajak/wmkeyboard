package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HugPunctuationTest {

    private val marks = ".,?!;:\u0964\u060C\u061B\u061F\u06D4"

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
    fun `a full stop inside an email address belongs to the address`() {
        assertTrue(markContinuesAddress("test@pm", '.'))
        assertTrue(markContinuesAddress("write to jo.doe@mail", '.'))
        assertTrue(markContinuesAddress("jo@mail.co", '.'))
    }

    @Test
    fun `a mention, prose around an address, or another mark keeps its space`() {
        assertFalse(markContinuesAddress("thanks @john", '.'))
        assertFalse(markContinuesAddress("test@", '.'))
        assertFalse(markContinuesAddress("a@b.com and then", '.'))
        assertFalse(markContinuesAddress("mail a@b.com", ','))
        assertFalse(markContinuesAddress("hello", '.'))
        assertFalse(markContinuesAddress("", '.'))
    }

    @Test
    fun `the Arabic marks hug their words like every other mark`() {
        assertEquals(1, straySpacesBefore("كيف ", '؟', marks))
        assertEquals(1, straySpacesBefore("نعم ", '،', marks))
        assertEquals(1, straySpacesBefore("هذا ", '؛', marks))
        assertEquals(1, straySpacesBefore("ختم ", '۔', marks))
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
