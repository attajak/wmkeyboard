package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.gesture.GlideCoverage
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An imported list must not vote on whether the language's grid can glide
 * while the language has a list of its own (#288). Arabic with an English list
 * imported under it, or with a compiled dictionary an older version had copied
 * in unread, lost glide altogether: the import's words are ones no Arabic grid
 * can draw, and pooled with a list the grid spells they halved the coverage.
 */
class GlideCoverageImportedTest {

    private val qwerty: Set<Int> = ('a'..'z').map { it.code }.toSet()

    private val arabicGrid: Set<Int> = "ابتثجحخدذرزسشصضطظعغفقكلمنهويىة".map { it.code }.toSet()

    private val arabic = listOf(
        "الله" to 1000, "على" to 900, "هذا" to 800, "التي" to 700, "كان" to 600,
    )

    private val english = listOf(
        "the" to 1000, "and" to 900, "that" to 800, "have" to 700, "with" to 600,
    )

    private fun engine(bundled: WordSource = PackedTrie.EMPTY) =
        SuggestionEngine(bundled, BengaliPhoneticIndex(emptyList()), UserLexicon(null))

    private fun arabicWith(imported: List<Pair<String, Int>>): SuggestionEngine = engine().apply {
        englishSources = false
        primaryLanguageId = "ar"
        customDictionary = CompositeWordSource.ofLanguage(PackedTrie.of(arabic), PackedTrie.of(imported))
    }

    @Test fun anImportTheGridCannotSpellDoesNotSwitchGlideOff() {
        val e = arabicWith(english)
        // What the two lists pool to, which is what used to be asked.
        assertEquals(0.5f, GlideCoverage.measure(e.customDictionary.walkers(), arabicGrid))
        assertEquals(1f, e.glideCoverage(arabicGrid))
        assertTrue(e.glideCoverage(arabicGrid) >= GlideCoverage.THRESHOLD)
    }

    @Test fun anImportTheGridSpellsDoesNotSwitchGlideOn() {
        // The other half of the same rule: a Latin grid that cannot draw a
        // word of Arabic is not made glidable by a few imported Latin words.
        assertEquals(0f, arabicWith(english).glideCoverage(qwerty))
    }

    @Test fun aSparseLayoutStillFails() {
        val sparse = "الهعى".map { it.code }.toSet()
        val coverage = arabicWith(emptyList()).glideCoverage(sparse)
        assertEquals(0.4f, coverage)
        assertTrue(coverage < GlideCoverage.THRESHOLD)
    }

    @Test fun withNoListOfItsOwnTheImportsAnswer() {
        // Never downloaded, or set to its imported lists alone (#28): the
        // imports are all that is known of the language.
        val e = engine().apply {
            englishSources = false
            primaryLanguageId = "ar"
            customDictionary = CompositeWordSource.ofLanguage(null, PackedTrie.of(arabic))
        }
        assertTrue(e.hasLanguageWords())
        assertEquals(1f, e.glideCoverage(arabicGrid))
        assertEquals(0f, e.glideCoverage(qwerty))
    }

    @Test fun anImportDoesNotOutvoteTheBundledList() {
        // English: the list of its own is the bundled one, and the slot holds
        // imports alone.
        val e = engine(PackedTrie.of(english)).apply {
            primaryLanguageId = "en"
            customDictionary = CompositeWordSource.ofLanguage(null, PackedTrie.of(arabic))
        }
        assertEquals(1f, e.glideCoverage(qwerty))
    }

    @Test fun anImportIsStillSearched() {
        val slot = arabicWith(english).customDictionary
        assertTrue(slot.contains("the"))
        assertTrue(slot.contains("الله"))
        assertEquals(2, slot.walkers().size)
        assertEquals(1, slot.ownWalkers().size)
    }
}
