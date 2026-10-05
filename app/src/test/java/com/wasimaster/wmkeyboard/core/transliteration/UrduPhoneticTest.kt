package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrduPhoneticTest {

    private fun t(input: String) = UrduPhonetic.transliterate(input)

    @Test fun wholeSentence() {
        assertEquals("آپ کیسے ہو?", t("aap kaise ho?"))
    }

    @Test fun shortVowelsAreNotWritten() {
        // Rule 1, and what makes this engine unlike the Indic two: Urdu marks a
        // short vowel with a diacritic nobody types.
        assertEquals("دن", t("din"))
        assertEquals("کل", t("kal"))
        assertEquals("تم", t("tum"))
        assertEquals("ہم", t("hum"))
        assertEquals("مرد", t("mard"))
        assertEquals("سچ", t("sach"))
        assertEquals("دوست", t("dost"))
    }

    @Test fun aWordFinalVowelIsLong() {
        // Rule 2: Urdu ends a word on a letter, never on a mark.
        assertEquals("کا", t("ka"))
        assertEquals("کی", t("ki"))
        assertEquals("کے", t("ke"))
        assertEquals("ہے", t("hai"))
        assertEquals("تو", t("to"))
        assertEquals("کرنا", t("karna"))
        assertEquals("میرا", t("mera"))
        assertEquals("کیسے", t("kaise"))
    }

    @Test fun aWordInitialVowelTakesItsCarrier() {
        // Rule 3.
        assertEquals("اب", t("ab"))
        assertEquals("آپ", t("aap"))
        assertEquals("ایک", t("ek"))
        assertEquals("اور", t("aur"))
        assertEquals("آج", t("aaj"))
    }

    @Test fun aVowelOnAVowelTakesAHamza() {
        // Rule 4.
        assertEquals("کوئی", t("koi"))
        assertEquals("ہوا", t("hua"))
        assertEquals("جاؤ", t("jaao"))
        assertEquals("آؤ", t("aao"))
    }

    @Test fun aConsonantalWawOrYeCarriesNothing() {
        // The other half of rule 4: the ی of "yar" and the و of "woh" are
        // consonants, and a vowel after one of them sits on the consonant
        // itself — one letter, not a hamza between two.
        assertEquals("یہ", t("yeh"))
        assertEquals("وہ", t("woh"))
        assertEquals("کیا", t("kya"))
        assertEquals("لیا", t("liya"))
        assertEquals("نیا", t("naya"))
    }

    @Test fun aspirationIsDoChashmiHe() {
        // Rule 5.
        assertEquals("دیکھ", t("dekh"))
        assertEquals("لکھ", t("likh"))
        assertEquals("پھر", t("phir"))
        assertEquals("گھر", t("ghar"))
        assertEquals("تھیک", t("theek"))
        assertEquals("جھوت", t("jhoot"))
        assertEquals("سمجھ", t("samajh"))
        assertEquals("چھوتا", t("chhota"))
    }

    @Test fun capitalsSpellTheRetroflexesAndTheArabicLetters() {
        // Rule 5's other half: ٹ ڈ ڑ, which no roman spelling marks, and the
        // Perso-Arabic letters, for anyone who wants to be exact.
        assertEquals("ٹمتر", t("Tamatar"))
        assertEquals("ڈڈا", t("DaDa"))
        assertEquals("کڑا", t("kaRa"))
        assertEquals("ٹھیک", t("Theek"))
        assertEquals("خبر", t("Khabar"))
        assertEquals("غلٹ", t("GhalaT"))
        assertEquals("صبح", t("SubH"))
        assertEquals("ضرور", t("Zaroor"))
    }

    @Test fun khOpeningAWordIsKhe() {
        // Rule 6: خوش, خبر, خدا open on خ, while the verbs that end on the
        // aspirate keep کھ.
        assertEquals("خش", t("khush"))
        assertEquals("خبر", t("khabar"))
        assertEquals("دیکھو", t("dekho"))
        assertEquals("انکھ", t("ankh"))
        // "Kh" is خ wherever it is typed.
        assertEquals("آخر", t("aaKhir"))
    }

    @Test fun aDoubledConsonantIsWrittenOnce() {
        // Rule 7: the shadda nobody types.
        assertEquals("سنا", t("sunna"))
        assertEquals("بنا", t("banna"))
        assertEquals("اس", t("iss"))
        assertEquals("پکا", t("pakka"))
        // …and a doubled "c" is the aspirate, which is the word people mean:
        // "accha" is اچھا.
        assertEquals("اچھا", t("accha"))
        assertEquals("اچا", t("acha"))
    }

    @Test fun capitalsThatSpellNothingReadAsTheirLowercaseSelves() {
        // A sentence-opening capital, or caps lock, on a letter whose capital
        // is not a rule of its own.
        assertEquals(t("kitab"), t("Kitab"))
        assertEquals(t("mera"), t("Mera"))
        assertEquals(t("log"), t("Log"))
    }

    @Test fun theLongAIsOfferedAsAVariant() {
        // What the rules cannot know: whether a lone "a" inside a word is a
        // long ا or nothing at all. The literal reading leads and the long ones
        // follow, for a user with no word list to settle it.
        assertEquals("کتب", t("kitab"))
        assertTrue("کتاب" in UrduPhonetic.variants("kitab"))
        assertEquals("پنی", t("pani"))
        assertTrue("پانی" in UrduPhonetic.variants("pani"))
        assertTrue("انسان" in UrduPhonetic.variants("insan"))
        assertEquals(t("kitab"), UrduPhonetic.variants("kitab").first())
        // A word with no inner "a" has one reading and no more.
        assertEquals(listOf("دوست"), UrduPhonetic.variants("dost"))
    }

    @Test fun nonLatinPassesThrough() {
        assertEquals("٭ 123 ٭", t("٭ 123 ٭"))
        assertEquals("کام 24/7", t("kaam 24/7"))
    }

    @Test fun apostropheIsAin() {
        // No roman letter stands for ع, so the apostrophe does, as
        // transcriptions of Urdu and Arabic both write it.
        assertEquals("سعت", t("sa'at"))
    }
}
