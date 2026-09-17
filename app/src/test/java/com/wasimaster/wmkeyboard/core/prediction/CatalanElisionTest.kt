package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Catalan elision typed without its apostrophe (#240).
 *
 * Counts are the real ones from the 184k `ca_full` list. It was tokenised at
 * the apostrophe, French-style — `l` and `d` are its seventh and eighth
 * commonest tokens — but unlike French it left almost no fused spellings
 * behind: `lhome`, `lhora` and `mha` appear once each.
 */
class CatalanElisionTest {

    private fun catalan(): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.customDictionary = PackedTrie.of(
            listOf(
                "home" to 3_981, "hora" to 6_761, "aigua" to 4_898, "escola" to 5_623,
                "any" to 17_378, "acord" to 6_310, "amic" to 1_660, "obra" to 3_467,
                "illa" to 2_138, "art" to 2_000, "ull" to 1_500,
                "ha" to 104_713, "han" to 33_113, "he" to 12_303, "hem" to 16_982,
                "hi" to 56_234, "ho" to 36_308, "està" to 21_380,
                "un" to 213_796, "una" to 177_828, "altre" to 20_000,
                // Words that split but are words.
                "ona" to 219, "sona" to 501, "dona" to 6_026, "mona" to 229,
                "ens" to 33_113, "dens" to 72, "era" to 23_442, "serà" to 7_586,
                "és" to 141_254, "ses" to 234, "mes" to 5_754, "es" to 109_648,
                "net" to 501, "et" to 9_000, "lloc" to 13_490, "davant" to 7_244,
                "alt" to 3_000, "dalt" to 1_072,
                // The fused spellings the corpus caught, all of them rare.
                "lhome" to 1, "lhora" to 1, "lany" to 2, "lescola" to 2,
                "sha" to 29, "shan" to 5, "mha" to 1, "tha" to 1, "nhi" to 1,
                "dun" to 25, "duna" to 25,
            ),
        )
        e.primaryLanguageId = "ca"
        return e
    }

    @Test fun theArticleAndThePrepositionElideBeforeAnyWord() {
        val e = catalan()
        for ((typed, elided) in listOf(
            "lhome" to "l'home",
            "lhora" to "l'hora",
            "laigua" to "l'aigua",
            "lescola" to "l'escola",
            "lany" to "l'any",
            "lamic" to "l'amic",
            "lobra" to "l'obra",
            "dacord" to "d'acord",
            "dart" to "d'art",
            "dull" to "d'ull",
            "dun" to "d'un",
            "duna" to "d'una",
        )) {
            assertEquals(typed, elided, e.elide(typed))
            assertEquals(typed, elided, e.suggest(typed, previousWord = null).first())
        }
    }

    @Test fun theWeakPronounsTakeHaverAndTheAdverbialPronouns() {
        val e = catalan()
        for ((typed, elided) in listOf(
            "sha" to "s'ha", "shan" to "s'han", "mha" to "m'ha", "tha" to "t'ha",
            "lha" to "l'ha", "nhi" to "n'hi", "shi" to "s'hi", "mho" to "m'ho",
            "them" to "t'hem", "sesta" to "s'està",
        )) {
            assertEquals(typed, elided, e.elide(typed))
        }
    }

    @Test fun aCatalanWordThatSplitsIsNotAnElision() {
        // Every one of these was found by sweeping the real list. `sona`,
        // `dona` and `mona` are why the weak pronouns get a function-word
        // table instead of a verb test; `serà`, `ses` and `mes` are why
        // *ser* is not in that table at all.
        val e = catalan()
        for (word in listOf(
            "sona", "dona", "mona", "serà", "ses", "mes", "net", "lloc", "davant",
        )) {
            assertNull(word, e.elide(word))
            assertEquals(word, word, e.suggest(word, previousWord = null).first())
        }
    }

    @Test fun aNamedWordIsRefusedWhateverTheCountsSay() {
        // *dens* means dense. Nothing in the grammar tells it from `d'ens`,
        // and *ens* outnumbers it 460 to one, so it is named outright.
        val e = catalan()
        assertNull(e.elide("dens"))
        assertEquals(emptyList<Elisions.Split>(), requireNotNull(Elisions.rulesFor("ca")).splits("dens"))
        // The grammar itself would have admitted it — this is the list, not a rule.
        assertTrue(requireNotNull(Elisions.rulesFor("ca")).admits("d", "ens"))
    }

    @Test fun aWordFarCommonerThanItsRestIsOnlyOffered() {
        // `dalt` against *alt* is 1 to 3: a real word, so `d'alt` waits on
        // the strip rather than replacing it.
        val e = catalan()
        assertNull(e.elide("dalt"))
        assertTrue("d'alt" in e.suggest("dalt", previousWord = null))
    }

    @Test fun theTypedCaseIsKept() {
        assertEquals("L'home", catalan().elide("Lhome"))
    }
}
