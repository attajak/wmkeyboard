package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HindiPhoneticTest {

    private fun t(input: String) = HindiPhonetic.transliterate(input)

    // Nukta letters are the decomposed pair, as the engine writes them.
    private val za = "ज़"
    private val rrha = "ढ़"

    @Test fun theIssuesOwnExample() {
        assertEquals("कैसे हो", t("kaise ho"))
    }

    @Test fun simpleWords() {
        assertEquals("तुम", t("tum"))
        assertEquals("आप", t("aap"))
        assertEquals("नाम", t("naam"))
        assertEquals("बहुत", t("bahut"))
        assertEquals("मुझे", t("mujhe"))
        assertEquals("और", t("aur"))
        assertEquals("है", t("hai"))
        assertEquals("कोई", t("koi"))
    }

    @Test fun inherentVowel() {
        // Silent after a consonant, अ where there is none to carry it.
        assertEquals("कमल", t("kamal"))
        assertEquals("अब", t("ab"))
        assertEquals("अगर", t("agar"))
    }

    @Test fun aFinalVowelIsLong() {
        assertEquals("मेरा", t("mera"))
        assertEquals("कहा", t("kaha"))
        assertEquals("गया", t("gaya"))
        assertEquals("किया", t("kiya"))
        assertEquals("हुआ", t("hua"))
        assertEquals("भी", t("bhi"))
        assertEquals("तू", t("tu"))
    }

    @Test fun anAIsLongBeforeABackVowel() {
        assertEquals("जाओ", t("jao"))
        assertEquals("खाओ", t("khao"))
    }

    @Test fun aClosingAiIsTheNounEnding() {
        assertEquals("सफाई", t("safai"))
        assertEquals("कमाई", t("kamai"))
        // One syllable: the diphthong, as typed.
        assertEquals("है", t("hai"))
    }

    @Test fun nasalBeforeItsStopIsAnAnusvara() {
        assertEquals("हिंदी", t("hindi"))
        assertEquals("लंबा", t("lamba"))
        assertEquals("रंग", t("rang"))
        assertEquals("${za}िंदगी", t("zindagi"))
    }

    @Test fun grammaticalEndingsStayOffTheirStem() {
        assertEquals("करना", t("karna"))
        assertEquals("सकता", t("sakta"))
        assertEquals("सुनता", t("sunta"))
        assertEquals("उसका", t("uska"))
        // Not an anusvara either: उन + का.
        assertEquals("उनका", t("unka"))
        assertEquals("मुझसे", t("mujhse"))
        assertEquals("देखकर", t("dekhkar"))
        assertEquals("गलती", t("galti"))
        // The doubling is the ending meeting its stem, not a conjunct.
        assertEquals("सुनना", t("sunna"))
    }

    @Test fun clustersTheTableJoins() {
        assertEquals("नमस्ते", t("namaste"))
        assertEquals("दोस्त", t("dost"))
        // A sibilant stem outranks the participle guard.
        assertEquals("दोस्ती", t("dosti"))
        assertEquals("क्या", t("kya"))
        assertEquals("पक्का", t("pakka"))
        assertEquals("बच्चा", t("baccha"))
        assertEquals("धर्म", t("dharm"))
        assertEquals("गर्मी", t("garmi"))
        assertEquals("शब्द", t("shabd"))
        assertEquals("किस्मत", t("kismat"))
        assertEquals("विद्या", t("vidya"))
        assertEquals("तुम्हे", t("tumhe"))
        assertEquals("उन्होने", t("unhone"))
    }

    @Test fun clustersItLeavesApart() {
        assertEquals("कमरा", t("kamra"))
        assertEquals("लदकी", t("ladki"))
    }

    @Test fun capitalsSpellTheRetroflexRow() {
        assertEquals("रोटी", t("roTi"))
        assertEquals("प${rrha}ना", t("paRhna"))
    }

    @Test fun capitalsThatSpellNothingReadAsLowercase() {
        assertEquals("ख", t("KH"))
        assertEquals(t("bahut"), t("Bahut"))
    }

    @Test fun onlyLatinRunsAreRead() {
        assertEquals("तुम कैसे हो? 123", t("tum kaise ho? 123"))
        assertEquals("क्या क्या", t("kya क्या"))
    }

    @Test fun variantsOfferTheOtherReadings() {
        val karna = HindiPhonetic.variants("karna")
        assertEquals("करना", karna.first())
        assertTrue("कर्ना" in karna)
        // The last "a" inside the word read long: what the rules cannot know.
        assertTrue("प्यार" in HindiPhonetic.variants("pyar"))
        assertTrue("सरकार" in HindiPhonetic.variants("sarkar"))
        // Final vowel left as short as it was typed.
        assertTrue("मित्र" in HindiPhonetic.variants("mitra"))
        // Never the same reading twice.
        assertEquals(karna.size, karna.toSet().size)
        assertEquals(listOf("हो"), HindiPhonetic.variants("ho"))
    }
}
