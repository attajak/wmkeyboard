package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `.combined` text list, words and everything hung off them. */
class AospCombinedTest {

    private fun read(text: String): AospDictionary.Result.Contents {
        val result = AospCombined.read(text.byteInputStream())
        assertTrue("expected contents, got $result", result is AospDictionary.Result.Contents)
        return result as AospDictionary.Result.Contents
    }

    @Test
    fun `words, pairs and shortcuts each land in their own place`() {
        val contents = read(
            """
            dictionary=main:fr,locale=fr,description=Français,version=54
             word=coeur,f=103,flags=,originalFreq=103
              shortcut=cœur,f=14
              bigram=de,f=160
             word=de,f=221
             word=omw,f=0,not_a_word=true
              shortcut=on my way,f=whitelist
            """.trimIndent(),
        )
        assertEquals(setOf("coeur", "de"), contents.words.toMap().keys)
        assertEquals(DictionaryLoader.scaleAospFrequency(221), contents.words.toMap()["de"])
        assertEquals(listOf(listOf("coeur") to "de"), contents.ngrams.map { it.context to it.word })
        assertEquals(AospScores.pairCount(160), contents.ngrams.single().count)
        assertEquals(
            listOf(
                AospDictionary.Shortcut("coeur", "cœur", whitelist = false),
                AospDictionary.Shortcut("omw", "on my way", whitelist = true),
            ),
            contents.shortcuts,
        )
        assertEquals("fr", contents.attributes["locale"])
    }

    @Test
    fun `an ngram record takes its context from the prev_word lines, nearest first`() {
        val contents = read(
            """
            dictionary=user_history:en
             word=here,f=-1,historicalInfo=1700000000:0:4
             ngram=here,f=-1,historicalInfo=1700000000:0:3
              prev_word[0]=am
              prev_word[1]=i
             ngram=here,f=-1,historicalInfo=1700000000:0:2
              prev_word[0]=am
             ngram=you,f=-1,historicalInfo=1700000000:0:1
              prev_word[0]=,beginning_of_sentence=true
            """.trimIndent(),
        )
        assertEquals(
            listOf(listOf("i", "am") to "here", listOf("am") to "here"),
            contents.ngrams.map { it.context to it.word },
        )
        assertEquals(AospScores.historicalPairCount(3), contents.ngrams[0].count)
        assertEquals(AospScores.historicalFrequency(4, 0), contents.words.toMap()["here"])
    }

    @Test
    fun `a plain list is not a combined one`() {
        assertEquals(AospDictionary.Result.NotADictionary, AospCombined.read("the 100\nof 90\n".byteInputStream()))
    }
}
