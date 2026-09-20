package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevanagariRomanizerTest {

    private fun r(word: String) = DevanagariRomanizer.romanizeWord(word)

    @Test fun theClosingInherentVowelIsSilent() {
        assertEquals("kamal", r("कमल"))
        assertEquals("agar", r("अगर"))
        assertEquals("dost", r("दोस्त"))
        assertEquals("dharm", r("धर्म"))
        assertEquals("shabd", r("शब्द"))
    }

    @Test fun exceptWhereTheWordCannotBeSaidWithoutIt() {
        assertEquals("na", r("न"))
        assertEquals("mitra", r("मित्र"))
        assertEquals("satya", r("सत्य"))
    }

    @Test fun anInnerOneIsSilentBetweenTwoVoicedSyllables() {
        assertEquals("karna", r("करना"))
        assertEquals("ladka", r("लड़का"))
        assertEquals("sarkaar", r("सरकार"))
        assertEquals("aadmi", r("आदमी"))
        assertEquals("janta", r("जनता"))
        // Decided from the end backwards, never two in a row.
        assertEquals("samajhna", r("समझना"))
        assertEquals("dhadkan", r("धड़कन"))
    }

    @Test fun aConjunctOrANasalOnEitherSideKeepsIt() {
        assertEquals("namaste", r("नमस्ते"))
        assertEquals("zindagi", r("ज़िंदगी"))
    }

    @Test fun lengthIsDoubledInsideAWordAndSingleAtItsEnd() {
        assertEquals("naam", r("नाम"))
        assertEquals("theek", r("ठीक"))
        assertEquals("door", r("दूर"))
        assertEquals("mera", r("मेरा"))
        assertEquals("ladki", r("लड़की"))
        assertEquals("tu", r("तू"))
        assertEquals("paani", r("पानी"))
    }

    @Test fun nasalisation() {
        assertEquals("hindi", r("हिंदी"))
        assertEquals("lamba", r("लंबा"))
        assertEquals("hain", r("हैं"))
        assertEquals("mein", r("में"))
        assertEquals("main", r("मैं"))
        assertEquals("haan", r("हाँ"))
        assertEquals("kyon", r("क्यों"))
        assertEquals("hoon", r("हूँ"))
        // The one nobody writes.
        assertEquals("nahi", r("नहीं"))
    }

    @Test fun lettersSaidDifferentlyThanWritten() {
        assertEquals("kshama", r("क्षमा"))
        assertEquals("gyaan", r("ज्ञान"))
        assertEquals("kripa", r("कृपा"))
        assertEquals("baccha", r("बच्चा"))
        assertEquals("fon", r("फ़ोन"))
        assertEquals("phir", r("फिर"))
    }

    @Test fun vaIsWExceptBeforeAFrontVowelAndAtTheEnd() {
        assertEquals("wo", r("वो"))
        assertEquals("sawaal", r("सवाल"))
        assertEquals("vishwaas", r("विश्वास"))
        assertEquals("dev", r("देव"))
    }

    @Test fun precomposedNuktaLettersAreTheSameWord() {
        // U+095B is ज़ in one code point; NFC and the word lists spell it as two.
        assertEquals(r("ज़िंदगी"), r("ज़िंदगी"))
        assertEquals("ज़", DevanagariRomanizer.normalize("ज़"))
    }

    @Test fun wholeTextKeepsEverythingElse() {
        assertEquals(
            "main theek hoon. tum kaise ho? 2024 OK",
            DevanagariRomanizer.romanize("मैं ठीक हूँ। तुम कैसे हो? २०२४ OK"),
        )
    }

    @Test fun whatCountsAsAWordCharacter() {
        assertTrue(DevanagariRomanizer.isDevanagari('क'))
        assertTrue(DevanagariRomanizer.isDevanagari('ा'))
        assertFalse(DevanagariRomanizer.isDevanagari('।'))
        assertFalse(DevanagariRomanizer.isDevanagari('२'))
        assertFalse(DevanagariRomanizer.isDevanagari('ক'))
    }
}
