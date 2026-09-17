package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The five smaller Romance languages added with Catalan for #240: Occitan,
 * Romansh, Corsican, Sardinian and Piedmontese.
 *
 * Every count below is the real one from that language's own list in the data
 * repository, and every "must not" case is a word the calibration sweep
 * caught an earlier draft of the table rewriting.
 */
class RomanceElisionTest {

    private fun engine(lang: String, words: List<Pair<String, Int>>): SuggestionEngine {
        val e = SuggestionEngine(PackedTrie.EMPTY, BengaliPhoneticIndex(emptyList()), UserLexicon(null))
        e.customDictionary = PackedTrie.of(words)
        e.primaryLanguageId = lang
        return e
    }

    private fun SuggestionEngine.elides(vararg pairs: Pair<String, String>) {
        for ((typed, elided) in pairs) assertEquals(typed, elided, elide(typed))
    }

    private fun SuggestionEngine.leavesAlone(vararg words: String) {
        for (word in words) assertNull(word, elide(word))
    }

    @Test fun occitan() {
        val e = engine(
            "oc",
            listOf(
                "a" to 30_307, "es" to 19_420, "un" to 17_639, "una" to 15_326,
                "an" to 1_862, "ostal" to 156, "na" to 117, "aiga" to 100,
                "amor" to 79, "entorn" to 36, "ma" to 63, "ta" to 35,
                "ses" to 21, "tes" to 9,
            ),
        )
        e.elides(
            "lostal" to "l'ostal",
            "daiga" to "d'aiga",
            "dentorn" to "d'entorn",
            "dun" to "d'un",
            "duna" to "d'una",
            "ques" to "qu'es",
            "lan" to "l'an",
            "pramor" to "pr'amor",
        )
        // *ma* and *ta* are "my" and "your". An earlier table read them as
        // `m'a` and `t'a`, which is why `m'` and `t'` are not prefixes here.
        e.leavesAlone("ma", "ta", "na", "ses", "tes")
    }

    @Test fun romansh() {
        val e = engine(
            "rm",
            listOf(
                "è" to 6_591, "ha" to 5_538, "in" to 3_932, "ina" to 3_752,
                "era" to 1_335, "onn" to 239, "imperi" to 26, "e" to 9_000,
            ),
        )
        e.elides(
            "lonn" to "l'onn",
            "limperi" to "l'imperi",
            "dina" to "d'ina",
            "din" to "d'in",
            // The lists answer the conjunction "e" unless told otherwise.
            "che" to "ch'è",
            "nha" to "n'ha",
        )
    }

    @Test fun corsican() {
        val e = engine(
            "co",
            listOf(
                "è" to 3_293, "un" to 1_833, "eddu" to 70, "acqua" to 15,
                "se" to 10, "comu" to 9, "isula" to 9, "cumu" to 7,
                "sa" to 4, "annata" to 3, "su" to 1,
            ),
        )
        e.elides(
            "lisula" to "l'isula",
            "lannata" to "l'annata",
            "dacqua" to "d'acqua",
            "cheddu" to "ch'eddu",
            "che" to "ch'è",
        )
        // *se*, *sa*, *su*, *comu* and *cumu* are Corsican words. A draft
        // that gave `s'`, `com'` and `cum'` the short function words read
        // every one of them as an elision.
        e.leavesAlone("se", "sa", "su", "comu", "cumu")
    }

    @Test fun sardinian() {
        val e = engine(
            "sc",
            listOf(
                "est" to 2_932, "unu" to 1_754, "at" to 867, "istadu" to 437,
                "àtera" to 15, "isula" to 1,
            ),
        )
        // `s'` is the article here, not a pronoun, and carries the
        // language's commonest elision.
        e.elides(
            "sistadu" to "s'istadu",
            "sisula" to "s'isula",
            "unatera" to "un'àtera",
            "lat" to "l'at",
        )
    }

    @Test fun piedmontese() {
        val e = engine(
            "pms",
            listOf(
                "a" to 34_415, "la" to 8_857, "le" to 2_361, "un" to 2_342,
                "é" to 340, "ann" to 87, "ha" to 9, "ìsola" to 7, "union" to 2,
            ),
        )
        e.elides(
            "lisola" to "l'ìsola",
            "lann" to "l'ann",
            "lunion" to "l'union",
            "lé" to "l'é",
            "lha" to "l'ha",
        )
        // *la* is the article itself; its rest is one letter and no table
        // lists it. *le* is a word, and `l'é` only waits on its strip.
        e.leavesAlone("la", "le")
    }

    @Test fun aLanguageWithNoRulesReachesNoElision() {
        // German, Polish, Turkish: the apostrophe means something else, or
        // nothing, and no table is better than a guessed one.
        for (lang in listOf("de", "pl", "tr", "nl", "cy")) {
            assertNull(lang, Elisions.rulesFor(lang))
        }
    }
}
