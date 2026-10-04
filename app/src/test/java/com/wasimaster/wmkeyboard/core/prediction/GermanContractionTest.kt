package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * German's *'s* for *es* typed without its apostrophe (#518): "Wie gehts" is
 * "Wie geht's". The German list holds the fused spelling thirty times as often
 * as the right one, so only the table can ask for it.
 */
class GermanContractionTest {

    private val german = listOf(
        "gehts" to 4_057, "geht" to 290_919, "geht's" to 145,
        "siehts" to 220, "sieht" to 75_968,
        "wie" to 928_920, "wies" to 531, "nichts" to 308_672,
        "aufs" to 9_000,
    )

    private fun germanOnly(): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.of(emptyList()), BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.englishSources = false
        e.customDictionary = PackedTrie.of(german)
        e.primaryLanguageId = "de"
        return e
    }

    @Test fun theTableRepairsTheVerb() {
        assertEquals("geht's", Apostrophes.fix("gehts", "de"))
        assertEquals("Sieht's", Apostrophes.fix("Siehts", "de"))
        assertEquals("gibt's", Apostrophes.fix("gibts", "de-AT"))
        assertNull(Apostrophes.fix("gehts", "fr"))
    }

    @Test fun germanWordsAreLeftAlone() {
        for (word in listOf("wies", "nichts", "aufs", "ins", "fürs")) {
            assertNull(word, Apostrophes.fix(word, "de"))
        }
    }

    @Test fun englishContractionsStayEnglish() {
        assertNull(Apostrophes.fix("dont", "de"))
        assertNull(Apostrophes.offer("ill", "de"))
    }

    @Test fun commitAndStripAgree() {
        val e = germanOnly()
        assertEquals("geht's", e.elide("gehts"))
        assertEquals("Sieht's", e.elide("Siehts"))
        assertNull(e.elide("wies"))
        assertTrue("geht's" in e.suggest("gehts", previousWord = "wie"))
    }

    @Test fun offWithTheSetting() {
        val e = germanOnly()
        e.apostropheFixes = false
        assertNull(e.elide("gehts"))
    }
}
