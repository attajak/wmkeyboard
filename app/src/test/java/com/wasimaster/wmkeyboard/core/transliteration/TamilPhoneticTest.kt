package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

class TamilPhoneticTest {

    private val engine = IndicPhonetic(TamilPhonetic.PROFILE)

    private fun t(s: String) = engine.transliterate(s)

    @Test fun plainWordsAndSentences() {
        // Plain words: every vowel typed, a pulli on the closing consonant, ந opening a word and ன inside it.
        assertEquals("\u0BB5\u0BA9\u0B95\u0BCD\u0B95\u0BAE\u0BCD", t("vanakkam")) // வனக்கம்
        assertEquals("\u0B85\u0BB5\u0BA9\u0BCD", t("avan")) // அவன்
        assertEquals("\u0BA8\u0BBE\u0BA9\u0BCD", t("naan")) // நான்
        assertEquals("\u0BA8\u0BBE\u0BA9\u0BCD \u0BB5\u0BA8\u0BCD\u0BA4\u0BC7\u0BA9\u0BCD!", t("naan vanthen!")) // நான் வந்தேன்!
    }

    @Test fun ndrIsAlveolarNRa() {
        // Rule 1: "ndr" is ன்ற, not ண்ட்ர.
        assertEquals("\u0BA8\u0BA9\u0BCD\u0BB1\u0BBF", t("nandri")) // நன்றி
        assertEquals("\u0B87\u0BA9\u0BCD\u0BB1\u0BC1", t("indru")) // இன்று
    }

    @Test fun trBetweenVowelsIsDoubledRa() {
        // Rule 2: "tr" or "ttr" between vowels is ற்ற; a word-initial "tr" is a loanword and stays.
        assertEquals("\u0B95\u0BBE\u0BB1\u0BCD\u0BB1\u0BC1", t("kaatru")) // காற்று
        assertEquals("\u0B95\u0BBE\u0BB1\u0BCD\u0BB1\u0BC1", t("kaattru")) // காற்று
        assertEquals("\u0B9F\u0BCD\u0BB0\u0BC8\u0BA9\u0BCD", t("train")) // ட்ரைன்
    }

    @Test fun rAfterNOrLIsRa() {
        // Rule 3: native Tamil has no ன்ர or ல்ர.
        assertEquals("\u0B9A\u0BCA\u0BB2\u0BCD\u0BB1\u0BC7\u0BA9\u0BCD", t("solren")) // சொல்றேன்
        assertEquals("\u0B8E\u0BA9\u0BCD\u0BB1\u0BC1", t("enru")) // என்று
    }

    @Test fun loneStopBetweenVowelsIsDoubled() {
        // Rule 4: a voiced stop is typed g/s/d/b, so an unvoiced one between vowels is the geminate.
        assertEquals("\u0B87\u0BB0\u0BC1\u0B95\u0BCD\u0B95\u0BC1", t("iruku")) // இருக்கு
        assertEquals("\u0BAA\u0BCA\u0B9A\u0BCD\u0B9A\u0BC1", t("pochu")) // பொச்சு
        assertEquals("\u0BB5\u0BC0\u0B9F\u0BCD\u0B9F\u0BC1", t("veetu")) // வீட்டு
    }

    @Test fun stopAfterRaOrZhaIsDoubled() {
        // Rule 4b: native Tamil geminates a stop after ர and ழ.
        assertEquals("\u0BB5\u0BBE\u0BB4\u0BCD\u0B95\u0BCD\u0B95\u0BC8", t("vaazhkai")) // வாழ்க்கை
        assertEquals("\u0BAA\u0BBE\u0BB0\u0BCD\u0BA4\u0BCD\u0BA4\u0BC7\u0BA9\u0BCD", t("paarthen")) // பார்த்தேன்
    }

    @Test fun finalEOrOIsLong() {
        // Rule 5 (with rule 4 in ipo): no Tamil word ends on a short ெ or ொ.
        assertEquals("\u0BA4\u0BBE\u0BA9\u0BC7", t("thaane")) // தானே
        assertEquals("\u0BAA\u0BCB", t("po")) // போ
        assertEquals("\u0B87\u0BAA\u0BCD\u0BAA\u0BCB", t("ipo")) // இப்போ
    }

    @Test fun verbEndingsEnAndOmAreLong() {
        // Rule 6: -en and -om are long in a word of two syllables or more.
        assertEquals("\u0BB5\u0BA8\u0BCD\u0BA4\u0BC7\u0BA9\u0BCD", t("vanthen")) // வந்தேன்
        assertEquals("\u0BAA\u0BCA\u0BB5\u0BCB\u0BAE\u0BCD", t("povom")) // பொவோம்
    }

    @Test fun pluralIsKal() {
        // Rule 7: -gal / -kkal is கள், closing the word or before a case ending; "kalai" is no plural.
        assertEquals("\u0BA8\u0BC0\u0B99\u0BCD\u0B95\u0BB3\u0BCD", t("neengal")) // நீங்கள்
        assertEquals("\u0BAE\u0B95\u0BCD\u0B95\u0BB3\u0BCD", t("makkal")) // மக்கள்
        assertEquals("\u0B89\u0B99\u0BCD\u0B95\u0BB3\u0BC1\u0B95\u0BCD\u0B95\u0BC1", t("ungalukku")) // உங்களுக்கு
        assertEquals("\u0B95\u0BB2\u0BC8", t("kalai")) // கலை
    }

    @Test fun caseEndingOdaIsLong() {
        // Rule 8: -oda has a long o.
        assertEquals("\u0B8E\u0BA9\u0BCD\u0BA9\u0BCB\u0B9F", t("ennoda")) // என்னோட
    }

    @Test fun formalPresentKirIsRa() {
        // Rule 9: -kir- / -gir- is கிற, but never opening a word.
        assertEquals("\u0B87\u0BB0\u0BC1\u0B95\u0BCD\u0B95\u0BBF\u0BB1\u0BC7\u0BA9\u0BCD", t("irukkiren")) // இருக்கிறேன்
        assertEquals("\u0B87\u0BB0\u0BC1\u0B95\u0BCD\u0B95\u0BBF\u0BB1\u0BA4\u0BC1", t("irukkirathu")) // இருக்கிறது
        assertEquals("\u0B95\u0BBF\u0BB0\u0BA9\u0BCD", t("kiran")) // கிரன்
    }

    @Test fun spokenPresentUrIsRa() {
        // Rule 10: -ur- before a personal ending is உற.
        assertEquals("\u0BAA\u0BC6\u0B9A\u0BC1\u0BB1\u0BC7\u0BA9\u0BCD", t("pesuren")) // பெசுறேன்
        assertEquals("\u0BAA\u0BA9\u0BCD\u0BA9\u0BC1\u0BB1\u0BBE\u0B99\u0BCD\u0B95", t("pannuraanga")) // பன்னுறாங்க
    }

    @Test fun iAfterAVowelIsTheGlide() {
        // Rule 11: Tamil has no ெஇ or ாஇ, so the "i" is ய்.
        assertEquals("\u0B9A\u0BC6\u0BAF\u0BCD", t("sei")) // செய்
        assertEquals("\u0B9A\u0BC6\u0BAF\u0BCD\u0BAF", t("seiya")) // செய்ய
        assertEquals("\u0BAA\u0BCA\u0BAF\u0BCD", t("poi")) // பொய்
    }

    @Test fun obligativeAnumIsNa() {
        // Rule 12: -anum after a verb stem is ணும், but an n-stem noun's -um stays.
        assertEquals("\u0B9A\u0BCA\u0BB2\u0BCD\u0BB2\u0BA3\u0BC1\u0BAE\u0BCD", t("sollanum")) // சொல்லணும்
        assertEquals("\u0BAA\u0BBE\u0B95\u0BCD\u0B95\u0BA3\u0BC1\u0BAE\u0BCD", t("paakanum")) // பாக்கணும்
        assertEquals("\u0B85\u0BB5\u0BA9\u0BC1\u0BAE\u0BCD", t("avanum")) // அவனும்
    }

    @Test fun tthIsDentalTha() {
        // Rule 13: Tamil has no ட்த.
        assertEquals("\u0BB0\u0BA4\u0BCD\u0BA4\u0BAE\u0BCD", t("rattham")) // ரத்தம்
        assertEquals("\u0BAA\u0BA4\u0BCD\u0BA4\u0BC1", t("patthu")) // பத்து
    }

    @Test fun closingAIsOfferedLong() {
        // The engine, not the hook: "amma" reads the closing a short, and the long one follows.
        assertEquals(listOf("\u0B85\u0BAE\u0BCD\u0BAE", "\u0B85\u0BAE\u0BCD\u0BAE\u0BBE"), engine.variants("amma")) // அம்ம அம்மா
    }
}
