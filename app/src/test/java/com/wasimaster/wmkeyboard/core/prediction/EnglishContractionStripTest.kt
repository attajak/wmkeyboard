package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The English contraction reaches the suggestion strip, not only the commit
 * (#240).
 *
 * The report was "thats is not corrected", and the step that showed it was
 * "that's is not showing among the available suggestions". Both spellings
 * were in the list, the space bar *did* commit the fix, and the strip went on
 * showing `thats` the whole time — so the only evidence the user had said the
 * feature did not work. A strip that disagrees with the space bar is the bug.
 */
class EnglishContractionStripTest {

    /**
     * Counts from the downloadable English list, which is tokenised at the
     * apostrophe: it holds `'s` as its sixth commonest token, `thats` at
     * 3,866 and `that's` at 2,116 — the fused misspelling ahead of the real
     * spelling. That ordering is why the reading needs its own margin rather
     * than a frequency ranking.
     */
    private fun english(): SuggestionEngine {
        val e = SuggestionEngine(
            PackedTrie.of(
                listOf(
                    "thats" to 3_866, "that's" to 2_116, "that" to 10_203_742,
                    "theres" to 991, "there's" to 4_600, "there" to 3_148_528,
                    "dont" to 9_523, "don't" to 4_911, "done" to 500_000,
                    "its" to 900_000, "it's" to 2_697,
                    "im" to 2_000, "i'm" to 4_386_306,
                    "were" to 800_000, "we're" to 300_000,
                ),
            ),
            BengaliPhoneticIndex(emptyList()),
            UserLexicon(null),
        )
        e.primaryLanguageId = "en"
        return e
    }

    @Test fun theContractionLeadsTheStripAndIsTheWordCommitted() {
        val e = english()
        for ((typed, fixed) in listOf(
            "thats" to "that's",
            "theres" to "there's",
            "dont" to "don't",
            "im" to "I'm",
        )) {
            assertEquals(typed, fixed, e.suggest(typed, previousWord = null).first())
            assertEquals(typed, fixed, e.elide(typed))
        }
    }

    @Test fun theSpellingItRepairsStaysOnTheStripBehindIt() {
        // A rewrite the user cannot undo in one tap is worse than no rewrite.
        val e = english()
        assertTrue("thats" in e.suggest("thats", previousWord = null))
    }

    @Test fun anAmbiguousFormIsLeftAlone() {
        // `its`, `were` and the rest are words in their own right, so the
        // table refuses them and so does the strip. Only a glide drawn
        // through the apostrophe key says otherwise; see [Apostrophes].
        val e = english()
        for (word in listOf("its", "were")) {
            assertNull(word, e.elide(word))
            assertEquals(word, word, e.suggest(word, previousWord = null).first())
        }
    }

    @Test fun theTypedCaseIsKept() {
        val e = english()
        assertEquals("That's", e.elide("Thats"))
        assertEquals("DON'T", e.elide("DONT"))
        // The lone "i" reaches its capital nowhere else, and the correctly
        // spelled "i'm" reaches it only here (#128).
        assertEquals("I", e.elide("i"))
        assertEquals("I'm", e.elide("i'm"))
        assertNull(e.elide("I'm"))
    }

    @Test fun theSettingTakesTheReadingOffTheStripAsWellAsTheCommit() {
        // "Fix missing apostrophes" off has to mean off everywhere, or the
        // strip would keep promising a repair the space bar no longer makes.
        val e = english()
        e.apostropheFixes = false
        assertNull(e.elide("thats"))
        assertEquals("thats", e.suggest("thats", previousWord = null).first())
    }

    @Test fun anotherLanguageDoesNotReadTheEnglishTable() {
        val e = english()
        e.primaryLanguageId = "de"
        assertNull(e.elide("thats"))
    }
}
