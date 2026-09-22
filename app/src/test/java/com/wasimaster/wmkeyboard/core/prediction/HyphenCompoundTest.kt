package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A compound typed without its hyphen is offered with it, and a compound typed
 * with it is neither cut in half nor "corrected".
 *
 * The Russian list counts the fused `чтото` 89 times against `что-то`'s
 * 162,833, so the fused spelling was a known word, the strip never asked for
 * corrections, and `что-то` was never on offer — where an English list that
 * does not hold `wellpaid` offered `well-paid` through the ordinary walk.
 */
class HyphenCompoundTest {

    private fun engine(vararg words: Pair<String, Int>): SuggestionEngine =
        SuggestionEngine(PackedTrie.of(words.toList()), BengaliPhoneticIndex(emptyList()), UserLexicon(null))

    private val russian = arrayOf(
        "что" to 3_552_532, "то" to 385_315, "что-то" to 162_833, "чтото" to 89, "чтобы" to 900_000,
    )

    @Test fun aFusedStandInIsOfferedItsCompound() {
        val strip = engine(*russian).suggest("чтото", previousWord = null)
        assertTrue(strip.toString(), "что-то" in strip)
    }

    @Test fun aFusedSpellingNoListHoldsIsOfferedItsCompound() {
        val strip = engine("well" to 2_159_909, "paid" to 400_000, "well-paid" to 192).suggest("wellpaid", previousWord = null)
        assertTrue(strip.toString(), "well-paid" in strip)
    }

    @Test fun aCommonFusedWordIsNotOfferedARareCompound() {
        val strip = engine("online" to 500_000, "on" to 9_000_000, "line" to 300_000, "on-line" to 900)
            .suggest("online", previousWord = null)
        assertFalse(strip.toString(), "on-line" in strip)
    }

    @Test fun aCompoundBeingTypedCompletesWhole() {
        val strip = engine("well" to 2_159_909, "paid" to 400_000, "well-paid" to 192, "pain" to 90_000)
            .suggest("well-pai", previousWord = null)
        assertEquals("well-paid", strip.first())
    }

    @Test fun aCompoundOfWordsIsNotCorrected() {
        val e = engine("hello" to 500_000, "world" to 400_000, "hell" to 90_000)
        assertNull(e.shouldAutocorrect("hello-world"))
    }
}
