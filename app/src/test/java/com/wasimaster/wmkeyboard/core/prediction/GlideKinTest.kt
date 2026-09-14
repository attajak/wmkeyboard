package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the strip offers beside a word a lift finished early (issue #168).
 *
 * Lifting on a guess is a choice, like a tap on a suggestion, so the strip
 * stops offering the stroke's other readings and offers the taken word's
 * neighbours instead: the words that share the drawn letters, nearest in
 * spelling to the word itself first.
 */
class GlideKinTest {

    private fun engine(vararg entries: Pair<String, Int>): SuggestionEngine {
        val list = Trie().apply { entries.forEach { (w, f) -> insert(w, f) } }
        return SuggestionEngine(list, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
            .apply { englishSources = true }
    }

    @Test
    fun theWordsNearestInSpellingComeFirstAndTheWordItselfIsLeftOut() {
        val engine = engine(
            "dictionary" to 2_037,
            "dictate" to 196,
            "dictator" to 193,
            "dictation" to 150,
            "dictionaries" to 120,
            "dictionary's" to 40,
            "dice" to 900,
        )
        val kin = engine.glideKin("dict", "dictionary", 4)
        // The possessive shares ten letters, the plural nine, the rest four:
        // nearness to the taken word outranks frequency, and among the rest
        // frequency decides.
        assertEquals(listOf("dictionary's", "dictionaries", "dictate", "dictator"), kin)
    }

    @Test
    fun theShorterWordAGuessRanPastIsOneTapAway() {
        val engine = engine(
            "functionality" to 300,
            "function" to 785,
            "functions" to 400,
            "functional" to 175,
            "functioning" to 120,
        )
        val kin = engine.glideKin("func", "functionality", 3)
        assertEquals("functional", kin.first())
        assertTrue("the taken word is not its own neighbour: $kin", "functionality" !in kin)
    }

    @Test
    fun nothingDrawnOffersNothing() {
        val engine = engine("dictionary" to 2_037)
        assertTrue(engine.glideKin("", "dictionary", 3).isEmpty())
        assertTrue(engine.glideKin("dict", "dictionary", 0).isEmpty())
    }
}
