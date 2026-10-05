package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Test

class MalayalamPhoneticTest {

    private val engine = IndicPhonetic(MalayalamPhonetic.PROFILE)

    private fun t(s: String) = engine.transliterate(s)

    @Test fun everyVowelIsTypedAndAClosingConsonantIsAChillu() {
        // Plain words: every vowel typed, a closing n the chillu ൻ.
        assertEquals("\u0D05\u0D2E\u0D4D\u0D2E", t("amma")) // അമ്മ
        assertEquals("\u0D1E\u0D3E\u0D7B", t("njaan")) // ഞാൻ
    }

    @Test fun nasalClustersAreWholeTokens() {
        // The extra consonants: nt ന്റ, nth ന്ത, nd ണ്ട, ng ങ്ങ, tth ത്ത — spelled out, never an anusvara.
        assertEquals("\u0D0E\u0D28\u0D4D\u0D31\u0D46", t("ente")) // എന്റെ
        assertEquals("\u0D0E\u0D28\u0D4D\u0D24\u0D4D", t("enthu")) // എന്ത്
        assertEquals("\u0D09\u0D23\u0D4D\u0D1F\u0D4D", t("undu")) // ഉണ്ട്
        assertEquals("\u0D0E\u0D19\u0D4D\u0D19\u0D28\u0D46", t("engane")) // എങ്ങനെ
        assertEquals("\u0D2A\u0D41\u0D30\u0D24\u0D4D\u0D24\u0D4D", t("puratthu")) // പുരത്ത്
    }

    @Test fun aClosingMIsTheAnusvaraAndMbIsMpa() {
        // Rule 1: a closing m, or one before s/bh, is ം; mb is മ്പ.
        assertEquals("\u0D38\u0D41\u0D16\u0D02", t("sukham")) // സുഖം
        assertEquals("\u0D38\u0D02\u0D38\u0D3E\u0D30\u0D02", t("samsaaram")) // സംസാരം
        assertEquals("\u0D38\u0D02\u0D2D\u0D35\u0D02", t("sambhavam")) // സംഭവം
        assertEquals("\u0D05\u0D2E\u0D4D\u0D2A\u0D32\u0D02", t("ambalam")) // അമ്പലം
    }

    @Test fun dBetweenVowelsIsTta() {
        // Rule 2: d between vowels or closing after one is ട, elsewhere ദ.
        assertEquals("\u0D07\u0D35\u0D3F\u0D1F\u0D46", t("ivide")) // ഇവിടെ
        assertEquals("\u0D35\u0D40\u0D1F\u0D4D", t("veedu")) // വീട്
        assertEquals("\u0D26\u0D3F\u0D35\u0D38\u0D02", t("divasam")) // ദിവസം
        assertEquals("\u0D05\u0D26\u0D4D\u0D26\u0D47\u0D39\u0D02", t("addEham")) // അദ്ദേഹം
    }

    @Test fun shAfterRukiIsSsa() {
        // Rule 3: sh after u/o/ee/r is ഷ; elsewhere ശ.
        assertEquals("\u0D38\u0D28\u0D4D\u0D24\u0D4B\u0D37\u0D02", t("santhOsham")) // സന്തോഷം
        assertEquals("\u0D35\u0D7C\u0D37\u0D02", t("varsham")) // വർഷം
        assertEquals("\u0D36\u0D30\u0D3F", t("shari")) // ശരി
    }

    @Test fun chBetweenVowelsIsDoubled() {
        // Rule 4: ch between vowels is ച്ച.
        assertEquals("\u0D15\u0D41\u0D31\u0D1A\u0D4D\u0D1A\u0D41", t("kuRachu")) // കുറച്ചു
        assertEquals("\u0D35\u0D3F\u0D33\u0D3F\u0D1A\u0D4D\u0D1A\u0D41", t("viLichu")) // വിളിച്ചു
    }

    @Test fun njAfterAVowelIsDoubled() {
        // Rule 5: nj after a vowel is ഞ്ഞ.
        assertEquals("\u0D2A\u0D31\u0D1E\u0D4D\u0D1E\u0D41", t("paRanju")) // പറഞ്ഞു
    }

    @Test fun lOfThePluralAndPronounsIsLla() {
        // Rule 6: l after the inherent vowel, closing or before a case ending, is ള; the closing -ol is ോൾ.
        assertEquals("\u0D28\u0D3F\u0D19\u0D4D\u0D19\u0D7E", t("ningal")) // നിങ്ങൾ
        assertEquals("\u0D28\u0D3F\u0D19\u0D4D\u0D19\u0D33\u0D41\u0D1F\u0D46", t("ningalude")) // നിങ്ങളുടെ
        assertEquals("\u0D05\u0D35\u0D7E\u0D15\u0D4D\u0D15\u0D4D", t("avalkku")) // അവൾക്ക്
        assertEquals("\u0D15\u0D41\u0D1F\u0D4D\u0D1F\u0D3F\u0D15\u0D33\u0D46", t("kuttikale")) // കുട്ടികളെ
        assertEquals("\u0D07\u0D2A\u0D4D\u0D2A\u0D4B\u0D7E", t("ippol")) // ഇപ്പോൾ
    }

    @Test fun nInAClosingAnamIsNna() {
        // Rule 7: n in a closing -anam is ണ.
        assertEquals("\u0D2A\u0D23\u0D02", t("panam")) // പണം
        assertEquals("\u0D15\u0D3E\u0D30\u0D23\u0D02", t("kaaranam")) // കാരണം
        assertEquals("\u0D2A\u0D31\u0D2F\u0D23\u0D02", t("paRayanam")) // പറയണം
    }

    @Test fun rAndLClosingASyllableAreChillus() {
        // Rule 8: r, l, L closing a syllable inside a word are chillus, except before യ, a doubled letter, and ല before പ.
        assertEquals("\u0D36\u0D7C\u0D15\u0D4D\u0D15\u0D30", t("sharkkara")) // ശർക്കര
        assertEquals("\u0D15\u0D3E\u0D30\u0D4D\u0D2F\u0D02", t("kaaryam")) // കാര്യം
        assertEquals("\u0D35\u0D46\u0D32\u0D4D\u0D32\u0D02", t("vellam")) // വെല്ലം
        assertEquals("\u0D15\u0D32\u0D4D\u0D2A\u0D28", t("kalpana")) // കല്പന
    }

    @Test fun aClosingUIsTheHalfU() {
        // Rule 9: a closing u is ്, except after the past-tense clusters and a chillu letter.
        assertEquals("\u0D05\u0D24\u0D4D", t("athu")) // അത്
        assertEquals("\u0D0E\u0D28\u0D3F\u0D15\u0D4D\u0D15\u0D4D", t("enikku")) // എനിക്ക്
        assertEquals("\u0D35\u0D28\u0D4D\u0D28\u0D41", t("vannu")) // വന്നു
        assertEquals("\u0D1A\u0D46\u0D2F\u0D4D\u0D24\u0D41", t("cheythu")) // ചെയ്തു
        assertEquals("\u0D12\u0D30\u0D41", t("oru")) // ഒരു
    }

    @Test fun wholeSentence() {
        // Punctuation and spaces pass through between the words.
        assertEquals("\u0D1E\u0D3E\u0D7B \u0D07\u0D35\u0D3F\u0D1F\u0D46 \u0D09\u0D23\u0D4D\u0D1F\u0D4D.", t("njaan ivide undu.")) // ഞാൻ ഇവിടെ ഉണ്ട്.
    }

    @Test fun theLastInnerAIsOfferedLong() {
        // EXPLICIT variants: the likely reading first, then the last inner "a" long (അവൻ / അവാൻ).
        assertEquals(listOf("\u0D05\u0D35\u0D7B", "\u0D05\u0D35\u0D3E\u0D7B"), engine.variants("avan"))
    }
}
