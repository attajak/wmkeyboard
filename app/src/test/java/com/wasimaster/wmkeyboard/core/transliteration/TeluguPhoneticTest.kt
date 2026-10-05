package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

class TeluguPhoneticTest {

    private val engine = IndicPhonetic(TeluguPhonetic.PROFILE)

    private fun t(s: String) = engine.transliterate(s)

    @Test fun plainWords() {
        // Every vowel typed is written; a doubled consonant is a conjunct.
        assertEquals("\u0C05\u0C2E\u0C4D\u0C2E", t("amma"))
        assertEquals("\u0C2E\u0C40\u0C30\u0C41", t("meeru"))
        assertEquals("\u0C0E\u0C32", t("ela"))
        assertEquals(
            "\u0C2E\u0C40\u0C30\u0C41 \u0C0E\u0C32 \u0C09\u0C28\u0C4D\u0C28\u0C30\u0C41?",
            t("meeru ela unnaru?"),
        )
    }

    @Test fun chatSpellings() {
        // "th"/"dh" are the dentals, "thh"/"dhh" the aspirates, "chh" the geminate.
        assertEquals("\u0C24\u0C2E\u0C4D\u0C2E\u0C41\u0C21\u0C41", t("thammudu"))
        assertEquals("\u0C07\u0C26\u0C3F", t("idhi"))
        assertEquals("\u0C15\u0C25", t("kathha"))
        assertEquals("\u0C35\u0C1A\u0C4D\u0C1A\u0C3F\u0C02\u0C26\u0C3F", t("vachhindi"))
    }

    @Test fun ruAfterAConsonantIsVocalicR() {
        // Rule 0: Cru is the vowel, but not after a cluster or in a closing -rudu.
        assertEquals("\u0C15\u0C43\u0C36\u0C3F", t("krushi"))
        assertEquals("\u0C05\u0C2D\u0C3F\u0C35\u0C43\u0C26\u0C4D\u0C26\u0C3F", t("abhivruddhi"))
        assertEquals("\u0C1A\u0C02\u0C26\u0C4D\u0C30\u0C41\u0C21\u0C41", t("chandrudu"))
        assertEquals("\u0C2E\u0C3F\u0C24\u0C4D\u0C30\u0C41\u0C21\u0C41", t("mitrudu"))
    }

    @Test fun eAndOAreLongInAnOpenSyllable() {
        // Rule 1: long before one consonant and a vowel, at the end, or before an r/s
        // cluster; short otherwise.
        assertEquals("\u0C28\u0C47\u0C28\u0C41", t("nenu"))
        assertEquals("\u0C32\u0C4B", t("lo"))
        assertEquals("\u0C1A\u0C46\u0C2A\u0C4D\u0C2A\u0C41", t("cheppu"))
        assertEquals("\u0C15\u0C4A\u0C02\u0C1A\u0C46\u0C02", t("konchem"))
        assertEquals("\u0C1A\u0C47\u0C38\u0C4D\u0C24\u0C41\u0C28\u0C4D\u0C28", t("chesthunna"))
        assertEquals("\u0C1A\u0C47\u0C30\u0C4D\u0C2A\u0C41", t("cherpu"))
        assertEquals("\u0C2A\u0C4B\u0C38\u0C4D\u0C24\u0C4D", t("post"))
    }

    @Test fun mAfterAVowelIsTheAnusvara() {
        // Rule 2: closing the word or before a consonant, but not before m/y/r.
        assertEquals("\u0C2E\u0C28\u0C02", t("manam"))
        assertEquals("\u0C38\u0C02\u0C35\u0C24\u0C4D\u0C38\u0C30\u0C02", t("samvatsaram"))
        assertEquals("\u0C38\u0C2E\u0C4D\u0C30\u0C3E\u0C1C\u0C4D\u0C2F\u0C02", t("samraajyam"))
    }

    @Test fun aClosingTOrDIsRetroflex() {
        // Rule 3: V+du and V+tu are retroflex, except the negative -ledu.
        assertEquals("\u0C35\u0C3E\u0C21\u0C41", t("vaadu"))
        assertEquals("\u0C0E\u0C2A\u0C4D\u0C2A\u0C41\u0C21\u0C41", t("eppudu"))
        assertEquals("\u0C32\u0C47\u0C26\u0C41", t("ledu"))
    }

    @Test fun ntBeforeIUEIsRetroflex() {
        // Rule 4: n+t before i/u/e is the retroflex pair; before a it stays dental.
        assertEquals("\u0C07\u0C02\u0C1F\u0C3F", t("inti"))
        assertEquals("\u0C05\u0C02\u0C1F\u0C47", t("ante"))
        assertEquals("\u0C05\u0C02\u0C24", t("antha"))
    }

    @Test fun shtIsRetroflex() {
        // Rule 5: sh+t is the retroflex pair.
        assertEquals("\u0C07\u0C37\u0C4D\u0C1F\u0C02", t("ishtam"))
        assertEquals("\u0C26\u0C43\u0C37\u0C4D\u0C1F\u0C3F", t("drushti"))
    }

    @Test fun theAdverbSuffixGaIsLong() {
        // Rule 6: a closing ga is long once the word has another vowel.
        assertEquals("\u0C24\u0C4D\u0C35\u0C30\u0C17\u0C3E", t("twaraga"))
        assertEquals("\u0C2C\u0C17\u0C3E", t("baga"))
        assertEquals("\u0C17", t("ga"))
    }

    @Test fun andiIsRetroflex() {
        // Rule 7: -andi is retroflex after two vowels, but not in -mandi or in randi.
        assertEquals("\u0C1A\u0C46\u0C2A\u0C4D\u0C2A\u0C02\u0C21\u0C3F", t("cheppandi"))
        assertEquals("\u0C1A\u0C41\u0C26\u0C02\u0C21\u0C3F", t("chudandi"))
        assertEquals("\u0C30\u0C02\u0C26\u0C3F", t("randi"))
        assertEquals("\u0C15\u0C4A\u0C02\u0C24\u0C2E\u0C02\u0C26\u0C3F", t("konthamandi"))
    }

    @Test fun variantsOfferTheClosingALong() {
        // EXPLICIT's readings: the likely one, then the closing "a" long.
        assertEquals(
            listOf("\u0C05\u0C2E\u0C4D\u0C2E", "\u0C05\u0C2E\u0C4D\u0C2E\u0C3E"),
            engine.variants("amma"),
        )
    }
}
