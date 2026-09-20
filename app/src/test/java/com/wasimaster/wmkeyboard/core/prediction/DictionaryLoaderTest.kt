package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the two word-list formats.
 *
 * The combined fixtures are hand-written from the published format, not taken
 * from any dictionary repository: the lists are their authors' work and this
 * only needs the shape of a line.
 */
class DictionaryLoaderTest {

    private fun entries(text: String): List<Pair<String, Int>> =
        DictionaryLoader.loadEntries(text.byteInputStream())

    @Test
    fun `the plain format is word then frequency`() {
        assertEquals(listOf("the" to 10000, "be" to 9900), entries("the 10000\nbe 9900"))
    }

    @Test
    fun `a plain line with no frequency is still a word`() {
        assertEquals(listOf("hello" to 1), entries("hello"))
    }

    @Test
    fun `a comment and a blank line are skipped`() {
        assertEquals(listOf("a" to 2), entries("# a note\n\na 2\n"))
    }

    @Test
    fun `a combined list is read as words, not as lines`() {
        val text = """
            dictionary=main:en,locale=en,description=English,date=1414726273,version=54
             word=the,f=222,flags=,originalFreq=222
             word=to,f=215,flags=
        """.trimIndent()
        assertEquals(listOf("the", "to"), entries(text).map { it.first })
    }

    @Test
    fun `a combined list used to import as junk`() {
        // The regression this test exists for: the record line holds no space,
        // so the whole of `word=the,f=222,flags=` was taken as one word.
        val text = "dictionary=main:en,locale=en\n word=the,f=222,flags=\n"
        assertFalse(entries(text).any { it.first.contains('=') })
    }

    @Test
    fun `combined frequencies land on this app's scale`() {
        val text = "dictionary=main:en\n word=the,f=255\n word=rare,f=1\n"
        val byWord = entries(text).toMap()
        assertEquals(10000, byWord.getValue("the"))
        assertTrue(byWord.getValue("rare") in 1..100)
        // And the order survives the move, which is the whole point of scaling.
        assertTrue(byWord.getValue("the") > byWord.getValue("rare"))
    }

    @Test
    fun `bigram and shortcut records are not words`() {
        val text = """
            dictionary=main:en
             word=the,f=222
              bigram=same,f=8
              shortcut=teh,f=whitelist
        """.trimIndent()
        assertEquals(listOf("the"), entries(text).map { it.first })
    }

    @Test
    fun `an unreadable frequency keeps the word`() {
        val text = "dictionary=main:en\n word=pls,f=whitelist\n"
        assertEquals(listOf("pls" to 1), entries(text))
    }

    @Test
    fun `a plain list that happens to contain an equals sign is untouched`() {
        // The format is decided once, on the first meaningful line, so a word
        // someone really wrote is not mistaken for a combined record.
        assertEquals(listOf("word=x" to 5), entries("word=x 5"))
    }
}
