package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SecondaryDictionary
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #481: a list's capitals are folded out of its keys and kept beside it. */
class DictionaryCapitalsTest {

    private fun fold(vararg words: String): Pair<Array<String?>, PackedTrie?> {
        val array = arrayOfNulls<String>(words.size)
        words.forEachIndexed { i, word -> array[i] = word }
        return array to DictionaryCapitals.fold(array, words.size).capitals
    }

    @Test fun capitalizedWordsAreKeyedInLowerCase() {
        val (words, _) = fold("der", "Zeit", "Haus", "USA")
        assertArrayEquals(arrayOf("der", "zeit", "haus", "usa"), words)
    }

    @Test fun theCapitalComesBackFromTheKey() {
        val (_, capitals) = fold("der", "Zeit", "USA", "iPhone", "McDonald", "Österreich")
        capitals!!
        assertEquals("Zeit", DictionaryCapitals.spelling(capitals, "zeit"))
        assertEquals("USA", DictionaryCapitals.spelling(capitals, "usa"))
        assertEquals("iPhone", DictionaryCapitals.spelling(capitals, "iphone"))
        assertEquals("McDonald", DictionaryCapitals.spelling(capitals, "mcdonald"))
        assertEquals("Österreich", DictionaryCapitals.spelling(capitals, "österreich"))
        assertNull(DictionaryCapitals.spelling(capitals, "der"))
    }

    @Test fun aWordListedBothWaysKeepsNoCapital() {
        val (_, capitals) = fold("Essen", "essen", "may", "May", "Berlin")
        capitals!!
        assertNull(DictionaryCapitals.spelling(capitals, "essen"))
        assertNull(DictionaryCapitals.spelling(capitals, "may"))
        assertEquals("Berlin", DictionaryCapitals.spelling(capitals, "berlin"))
    }

    @Test fun aLowerCaseListHasNoCapitals() {
        assertNull(fold("ich", "sie", "das").second)
    }

    private fun engine(): SuggestionEngine {
        val (words, capitals) = fold("der", "Haus", "Zeit", "essen", "Essen", "haben")
        val list = PackedTrie.of(words.map { it!! to 100 })
        return SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            englishSources = false
            primaryLanguageId = "de"
            customDictionary = list
            dictionaryCapitals = mapOf("de" to capitals!!)
        }
    }

    @Test fun aNounTypedInLowerCaseCommitsWithItsCapital() {
        val engine = engine()
        assertEquals("Haus", engine.shouldAutocorrect("haus"))
        assertNull(engine.shouldAutocorrect("haben"))
        assertNull(engine.shouldAutocorrect("essen"))
        // Shifted by hand, or by the start of a sentence: already as wanted.
        assertNull(engine.shouldAutocorrect("Haus"))
    }

    @Test fun theSwitchTurnsItOff() {
        val engine = engine().apply { dictionaryCapitalsEnabled = false }
        assertNull(engine.shouldAutocorrect("haus"))
    }

    @Test fun aSecondLanguageDoesNotCapitalizeThePrimarysWords() {
        val (_, german) = fold("Gift", "Haus")
        val english = PackedTrie.of(listOf("gift" to 100, "house" to 100))
        val engine = SuggestionEngine(english, BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply {
            dictionaryCapitals = mapOf("de" to german!!)
            secondaryDictionaries = listOf(
                SecondaryDictionary("de", PackedTrie.of(listOf("gift" to 100, "haus" to 100))),
            )
        }
        assertNull(engine.shouldAutocorrect("gift"))
        assertEquals("Haus", engine.shouldAutocorrect("haus"))
    }
}
