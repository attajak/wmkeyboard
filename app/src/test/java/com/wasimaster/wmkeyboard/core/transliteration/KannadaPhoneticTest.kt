package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

class KannadaPhoneticTest {

    private val engine = IndicPhonetic(KannadaPhonetic.PROFILE)

    private fun t(s: String) = engine.transliterate(s)

    @Test fun plainWords() {
        // Every vowel typed, a doubled consonant a conjunct, a nasal before its
        // stop an anusvara, and "th" the dental.
        assertEquals("\u0CA8\u0CA8\u0CCD\u0CA8", t("nanna")) // ನನ್ನ
        assertEquals("\u0C85\u0CAE\u0CCD\u0CAE", t("amma")) // ಅಮ್ಮ
        assertEquals("\u0CA8\u0CAE\u0CB8\u0CCD\u0C95\u0CB0", t("namaskara")) // ನಮಸ್ಕರ
        assertEquals("\u0CA4\u0CC1\u0C82\u0CAC", t("thumba")) // ತುಂಬ
        assertEquals("\u0CAE\u0CBE\u0CA4\u0CC1", t("maathu")) // ಮಾತು
    }

    @Test fun yeOpeningAWordIsE() {
        // Rule 1: a word-opening "ye" is the vowel; "ye" alone stays.
        assertEquals("\u0C8F\u0CA8\u0CC1", t("yenu")) // ಏನು
        assertEquals("\u0C8E\u0CB2\u0CCD\u0CB2\u0CBF", t("yelli")) // ಎಲ್ಲಿ
        assertEquals("\u0CAF\u0CC6", t("ye")) // ಯೆ
    }

    @Test fun owBeforeAConsonantIsAu() {
        // Rule 2.
        assertEquals("\u0CB9\u0CCC\u0CA6\u0CC1", t("howdu")) // ಹೌದು
        assertEquals("\u0C97\u0CCC\u0CA6", t("gowda")) // ಗೌದ
    }

    @Test fun shBeforeARetroflexIsSsa() {
        // Rule 3.
        assertEquals("\u0C95\u0CB7\u0CCD\u0C9F", t("kashta")) // ಕಷ್ಟ
        assertEquals("\u0CA8\u0CBF\u0CB7\u0CCD\u0CA0", t("nishtha")) // ನಿಷ್ಠ
    }

    @Test fun theAaguVerb() {
        // Rule 4: ಆಗಿ everywhere, ಆಗು past the first syllable but not at the end.
        assertEquals("\u0C9A\u0CC6\u0CA8\u0CCD\u0CA8\u0CBE\u0C97\u0CBF", t("chennagi")) // ಚೆನ್ನಾಗಿ
        assertEquals("\u0CAE\u0CBE\u0CA6\u0CB2\u0CBE\u0C97\u0CC1\u0CA4\u0CCD\u0CA4\u0CC6", t("maadalagutte")) // ಮಾದಲಾಗುತ್ತೆ
        assertEquals("\u0C85\u0C97\u0CC1\u0CA4\u0CCD\u0CA4\u0CC6", t("agutte")) // ಅಗುತ್ತೆ
        assertEquals("\u0CAE\u0CB2\u0C97\u0CC1", t("malagu")) // ಮಲಗು
    }

    @Test fun thePluralGalu() {
        // Rule 5.
        assertEquals("\u0CAE\u0CA8\u0CC6\u0C97\u0CB3\u0CC1", t("manegalu")) // ಮನೆಗಳು
        assertEquals("\u0CB9\u0CC6\u0CA8\u0CCD\u0CA8\u0CC1\u0C97\u0CB3\u0CC1", t("hennugalu")) // ಹೆನ್ನುಗಳು
    }

    @Test fun theHortativeOna() {
        // Rule 6, with rule 7 lengthening the o before it.
        assertEquals("\u0CB9\u0CCA\u0C97\u0CCB\u0CA3", t("hogona")) // ಹೊಗೋಣ
        assertEquals("\u0CAE\u0CA6\u0CCB\u0CA3", t("madona")) // ಮದೋಣ
    }

    @Test fun oPastTheFirstSyllableIsLong() {
        // Rule 7: long, but short before a geminate or a nasal cluster.
        assertEquals("\u0CB8\u0C82\u0CA4\u0CCB\u0CB6", t("santosha")) // ಸಂತೋಶ
        assertEquals("\u0CB6\u0CC1\u0CAD\u0CCB\u0CA6\u0CAF", t("shubhodaya")) // ಶುಭೋದಯ
        assertEquals("\u0CA4\u0CC6\u0C97\u0CC6\u0CA6\u0CC1\u0C95\u0CCA\u0C82\u0CA6\u0CC1", t("tegedukondu")) // ತೆಗೆದುಕೊಂದು
    }

    @Test fun eInATwoSyllableWordIsLong() {
        // Rule 8: long in two syllables, short in longer words; with rule 1 in "yeshu".
        assertEquals("\u0CAC\u0CC7\u0C95\u0CC1", t("beku")) // ಬೇಕು
        assertEquals("\u0CAE\u0CC7\u0CB2\u0CC6", t("mele")) // ಮೇಲೆ
        assertEquals("\u0C8F\u0CA8\u0CC1", t("enu")) // ಏನು
        assertEquals("\u0C8F\u0CB6\u0CC1", t("yeshu")) // ಏಶು
        assertEquals("\u0CB9\u0CC6\u0CB8\u0CB0\u0CC1", t("hesaru")) // ಹೆಸರು
    }

    @Test fun lAfterAShortOIsLla() {
        // Rule 9, with rule 10 in "madikolli".
        assertEquals("\u0C92\u0CB3\u0C97\u0CC6", t("olage")) // ಒಳಗೆ
        assertEquals("\u0C92\u0CB3\u0CCD\u0CB3\u0CC6", t("olle")) // ಒಳ್ಳೆ
        assertEquals("\u0CAE\u0CA1\u0CBF\u0C95\u0CCA\u0CB3\u0CCD\u0CB3\u0CBF", t("madikolli")) // ಮಡಿಕೊಳ್ಳಿ
    }

    @Test fun dBetweenVowelsIsDda() {
        // Rule 10: ಡ before i and after a typed long vowel, ದ after a short u or e.
        assertEquals("\u0C85\u0C82\u0C97\u0CA1\u0CBF", t("angadi")) // ಅಂಗಡಿ
        assertEquals("\u0CA8\u0CC0\u0CA1\u0CC1", t("needu")) // ನೀಡು
        assertEquals("\u0CAE\u0CBE\u0CA6\u0CC1\u0CA6\u0CBF\u0CB2\u0CCD\u0CB2", t("maadudilla")) // ಮಾದುದಿಲ್ಲ
        assertEquals("\u0CAA\u0CA6\u0CC6\u0CA6\u0CBF\u0CA6\u0CC6", t("padedide")) // ಪದೆದಿದೆ
    }

    @Test fun aFinalDhuIsDa() {
        // Rule 11.
        assertEquals("\u0C87\u0CA6\u0CC1", t("idhu")) // ಇದು
        assertEquals("\u0C85\u0CA6\u0CC1", t("adhu")) // ಅದು
    }

    @Test fun theSpokenFirstPerson() {
        // Rule 12.
        assertEquals("\u0CAC\u0CB0\u0CCD\u0CA4\u0CC0\u0CA8\u0CBF", t("bartini")) // ಬರ್ತೀನಿ
        assertEquals("\u0C87\u0CA6\u0CCD\u0CA6\u0CC0\u0CA8\u0CBF", t("iddini")) // ಇದ್ದೀನಿ
    }

    @Test fun variantsOfferTheLongClosingA() {
        // The EXPLICIT model's second reading: the closing "a" long.
        assertEquals(
            listOf("\u0C85\u0CAE\u0CCD\u0CAE", "\u0C85\u0CAE\u0CCD\u0CAE\u0CBE"), // ಅಮ್ಮ ಅಮ್ಮಾ
            engine.variants("amma"),
        )
    }
}
