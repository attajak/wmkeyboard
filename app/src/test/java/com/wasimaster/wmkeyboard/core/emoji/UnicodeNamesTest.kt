package com.wasimaster.wmkeyboard.core.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Any character by the words of its Unicode name (#385). */
class UnicodeNamesTest {

    @Test
    fun `finds a character by its whole name, shortest name first`() {
        val dash = UnicodeNames.search("em dash")
        assertEquals("—", dash.first().emoji)
        assertEquals("Em dash", dash.first().name)
        assertEquals("©", UnicodeNames.search("copyright sign").first().emoji)
    }

    @Test
    fun `every query word must match, whole or as a start`() {
        val lambda = UnicodeNames.search("greek small lambda").map { it.emoji }
        assertTrue("λ" in lambda)
        assertTrue(UnicodeNames.search("greek small lambdq").isEmpty())
        // Too short to mean anything.
        assertTrue(UnicodeNames.search("a").isEmpty())
    }

    @Test
    fun `results carry the unicode category and respect the limit`() {
        val letters = UnicodeNames.search("latin small letter", limit = 5)
        assertEquals(5, letters.size)
        assertTrue(letters.all { it.category == UnicodeNames.CATEGORY })
    }
}
