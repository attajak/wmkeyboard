package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GlideCoverage
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A secondary language must not vote on whether the primary's grid can glide
 * (#272). English with "also suggest from Polish" switched glide off on the
 * English keyboard: the Polish list's common words are full of letters a
 * QWERTY grid has no keys for, and pooled with English they dragged the
 * coverage under the threshold.
 */
class GlideCoverageSecondaryTest {

    private val qwerty: Set<Int> = ('a'..'z').map { it.code }.toSet()

    private val english = listOf(
        "the" to 1000, "and" to 900, "that" to 800, "have" to 700, "with" to 600,
    )

    /** Every one of them holds a letter the English grid cannot draw. */
    private val polish = listOf(
        "być" to 1000, "który" to 900, "może" to 800, "już" to 700, "łatwo" to 600,
    )

    private fun engine() =
        SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null))

    @Test fun aSecondaryTheGridCannotSpellDoesNotSwitchGlideOff() {
        val e = engine()
        e.customDictionary = PackedTrie.of(english)
        e.secondaryDictionaries = listOf(SecondaryDictionary("pl", PackedTrie.of(polish)))
        e.primaryLanguageId = "en"
        assertEquals(1f, e.glideCoverage(qwerty))
        assertTrue(e.glideCoverage(qwerty) >= GlideCoverage.THRESHOLD)
    }

    @Test fun withoutAListOfItsOwnTheSecondariesStillAnswer() {
        // #219: a language whose list was never downloaded is judged on what
        // is loaded, so the missing-list chip can be offered.
        val e = engine()
        e.secondaryDictionaries = listOf(SecondaryDictionary("en", PackedTrie.of(english)))
        e.primaryLanguageId = "fr"
        assertEquals(1f, e.glideCoverage(qwerty))
    }
}
