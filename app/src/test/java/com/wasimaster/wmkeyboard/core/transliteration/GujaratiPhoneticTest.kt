package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

class GujaratiPhoneticTest {

    private val engine = IndicPhonetic(GujaratiPhonetic.PROFILE)

    private fun t(s: String) = engine.transliterate(s)

    @Test fun plainWords() {
        // Words the hook leaves alone: the NORTH reading as is.
        assertEquals("\u0A95\u0AC7\u0AAE", t("kem")) // કેમ
        assertEquals("\u0AA4\u0AAE\u0AC7", t("tame")) // તમે
        assertEquals("\u0AA8\u0AAE\u0AB8\u0ACD\u0AA4\u0AC7", t("namaste")) // નમસ્તે
        assertEquals("\u0A95\u0ABE\u0AAE", t("kaam")) // કામ
        assertEquals("\u0A9D\u0A98\u0AA6\u0ACB", t("zaghdo")) // ઝઘદો
        assertEquals("\u0AB2\u0A95\u0ACD\u0AB7\u0AAE\u0AC0", t("laxmi")) // લક્ષમી
        assertEquals("\u0AAA\u0AB3\u0ABE", t("paLa")) // પળા
    }

    @Test fun finalUIsTheNeuterNasal() {
        // Rule 1: a final -u is -ું, a final -au is -ાઉં.
        assertEquals("\u0AB9\u0AC1\u0A82", t("hu")) // હું
        assertEquals("\u0A95\u0AB0\u0AB5\u0AC1\u0A82", t("karvu")) // કરવું
        assertEquals("\u0A95\u0AB0\u0ACD\u0AAF\u0AC1\u0A82", t("karyu")) // કર્યું
        assertEquals("\u0AB9\u0AA4\u0AC1\u0A82", t("hatu")) // હતું
        assertEquals("\u0A9C\u0ABE\u0A89\u0A82", t("jau")) // જાઉં
    }

    @Test fun theLocativeIsNasal() {
        // Rule 2: -ma, -maa, -mathi are -માં, -માંથી.
        assertEquals("\u0A98\u0AB0\u0AAE\u0ABE\u0A82", t("gharma")) // ઘરમાં
        assertEquals("\u0A98\u0AB0\u0AAE\u0ABE\u0A82", t("gharmaa")) // ઘરમાં
        assertEquals("\u0A98\u0AB0\u0AAE\u0ABE\u0A82\u0AA5\u0AC0", t("gharmathi")) // ઘરમાંથી
        assertEquals("\u0AB6\u0AB9\u0AC7\u0AB0\u0AAE\u0ABE\u0A82", t("shaherma")) // શહેરમાં
    }

    @Test fun theObliqueInfinitiveIsLong() {
        // Rule 3: the infinitive's v takes ા before an oblique ending.
        assertEquals("\u0A95\u0AB0\u0AB5\u0ABE\u0AA8\u0AC1\u0A82", t("karvanu")) // કરવાનું
        assertEquals("\u0A95\u0AB0\u0AB5\u0ABE\u0AAE\u0ABE\u0A82", t("karvama")) // કરવામાં
        assertEquals("\u0A9C\u0AAE\u0AB5\u0ABE\u0AA8\u0AC1\u0A82", t("jamvanu")) // જમવાનું
    }

    @Test fun doubledVBeforeTheInfinitiveIsTwoSyllables() {
        // Rule 4: no conjunct between the stem's v and the infinitive's.
        assertEquals("\u0A86\u0AB5\u0AB5\u0AC1\u0A82", t("aavvu")) // આવવું
        assertEquals("\u0A86\u0AB5\u0AB5\u0ABE\u0AA8\u0AC1\u0A82", t("aavvanu")) // આવવાનું
    }

    @Test fun oneSyllableAiIsAPlusLongI() {
        // Rule 5: gai, thai, jaish are C + ઈ; a longer word keeps ઐ.
        assertEquals("\u0A97\u0A88", t("gai")) // ગઈ
        assertEquals("\u0AA5\u0A88", t("thai")) // થઈ
        assertEquals("\u0A9C\u0A88\u0AB6", t("jaish")) // જઈશ
        assertEquals("\u0AB2\u0A88\u0AA8\u0AC7", t("laine")) // લઈને
        assertEquals("\u0AAA\u0AC8\u0AB8\u0ABE", t("paisa")) // પૈસા
    }

    @Test fun iIsLongAfterAVowelOrBeforeAVerbalSuffix() {
        // Rule 6: ઈ after a vowel, ી before -ne, -sh, -e; and -iye is -ઈએ.
        assertEquals("\u0A9C\u0ACB\u0A88\u0A8F", t("joie")) // જોઈએ
        assertEquals("\u0A95\u0AB0\u0AC0\u0AA8\u0AC7", t("karine")) // કરીને
        assertEquals("\u0A95\u0AB0\u0AC0\u0AB6", t("karish")) // કરીશ
        assertEquals("\u0AAE\u0AB2\u0AC0\u0A8F", t("malie")) // મલીએ
        assertEquals("\u0A9C\u0ACB\u0A88\u0A8F", t("joiye")) // જોઈએ
        assertEquals("\u0A95\u0AB0\u0AC0\u0A8F", t("kariye")) // કરીએ
        assertEquals("\u0A9C\u0A88\u0A8F", t("jaiye")) // જઈએ
    }

    @Test fun aSanskritTraNounEndsInTheConsonant() {
        // Rule 7: no long vowel after -tra, -dra, -shra.
        assertEquals("\u0AAE\u0ABF\u0AA4\u0ACD\u0AB0", t("mitra")) // મિત્ર
        assertEquals("\u0AA8\u0AB0\u0AC7\u0A82\u0AA6\u0ACD\u0AB0", t("narendra")) // નરેંદ્ર
        assertEquals("\u0AB8\u0ACC\u0AB0\u0AB6\u0ACD\u0AA4\u0ACD\u0AB0", t("saurashtra")) // સૌરશ્ત્ર
    }

    @Test fun variantsOfferTheOtherReadings() {
        // The likely reading first, then the long inner a, then the joined cluster.
        // કરવું કારવું કર્વું
        assertEquals(
            listOf("\u0A95\u0AB0\u0AB5\u0AC1\u0A82", "\u0A95\u0ABE\u0AB0\u0AB5\u0AC1\u0A82", "\u0A95\u0AB0\u0ACD\u0AB5\u0AC1\u0A82"),
            engine.variants("karvu"),
        )
    }
}
