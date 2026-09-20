package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Two languages configured, each the other's secondary: the apostrophe has to
 * come back for both, not only for whichever is primary right now (#240).
 *
 * The reporter runs Italian and English that way. With the repair keyed on
 * the primary alone, "thats" went unrepaired the moment Italian was in front
 * and "lalbero" the moment English was.
 */
class MixedLanguageApostropheTest {

    private val english = listOf(
        "that" to 10_203_742, "thats" to 3_866, "that's" to 2_116,
        "dont" to 9_523, "don't" to 4_911, "art" to 300_000, "dart" to 4_000,
    )

    private val italian = listOf(
        "albero" to 13_407, "acqua" to 58_651, "uomo" to 216_269,
        "era" to 657_610, "cera" to 1_793, "ora" to 559_590,
    )

    /** [primary] in front, the other language riding as its secondary. */
    private fun mixed(primary: String): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        if (primary == "en") {
            e.customDictionary = PackedTrie.of(english)
            e.secondaryDictionaries = listOf(SecondaryDictionary("it", PackedTrie.of(italian)))
        } else {
            e.customDictionary = PackedTrie.of(italian)
            e.secondaryDictionaries = listOf(SecondaryDictionary("en", PackedTrie.of(english)))
        }
        e.primaryLanguageId = primary
        return e
    }

    @Test fun theEnglishContractionSurvivesItalianBeingInFront() {
        assertEquals("that's", mixed("it").elide("thats"))
        assertEquals("don't", mixed("it").elide("dont"))
    }

    @Test fun theItalianElisionSurvivesEnglishBeingInFront() {
        assertEquals("l'albero", mixed("en").elide("lalbero"))
        assertEquals("d'acqua", mixed("en").elide("dacqua"))
    }

    @Test fun eachStillWorksWhenItIsTheOneInFront() {
        assertEquals("that's", mixed("en").elide("thats"))
        assertEquals("l'albero", mixed("it").elide("lalbero"))
    }

    @Test fun theStripAgreesWithTheCommitInEitherDirection() {
        assertEquals("that's", mixed("it").suggest("thats", previousWord = null).first())
        assertEquals("l'albero", mixed("en").suggest("lalbero", previousWord = null).first())
    }

    @Test fun oneLanguagesGrammarDoesNotSplitAnotherLanguagesWord() {
        // The trap the mix creates: Italian `d'` is happy to elide before a
        // vowel, and the mix holds an English "art" for it to point at, so
        // "dart" reads as `d'art` unless the word after the prefix is looked
        // up in the language whose grammar admitted the split.
        assertNull(mixed("en").elide("dart"))
        assertNull(mixed("it").elide("dart"))
    }

    @Test fun aMixWithNoSecondaryIsUnchanged() {
        val e = SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.customDictionary = PackedTrie.of(italian)
        e.primaryLanguageId = "it"
        assertEquals("c'era", e.elide("cera"))
        assertNull(e.elide("thats"))
    }
}
