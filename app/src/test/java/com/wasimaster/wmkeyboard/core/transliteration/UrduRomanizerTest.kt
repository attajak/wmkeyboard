package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrduRomanizerTest {

    private fun r(word: String) = UrduRomanizer.romanizeWord(word)

    @Test fun urduLettersAreWordCharactersAndItsDigitsAreNot() {
        assertTrue(UrduRomanizer.isUrdu('ک'))
        assertTrue(UrduRomanizer.isUrdu('ھ'))
        assertTrue(UrduRomanizer.isUrdu('ے'))
        assertTrue(UrduRomanizer.isUrdu('ں'))
        assertFalse(UrduRomanizer.isUrdu('a'))
        // Not the full stop, the comma, or either set of digits: a word span
        // read out of a field must not swallow them.
        assertFalse(UrduRomanizer.isUrdu('۔'))
        assertFalse(UrduRomanizer.isUrdu('،'))
        assertFalse(UrduRomanizer.isUrdu('۵'))
        assertFalse(UrduRomanizer.isUrdu('٩'))
    }

    @Test fun arabicLetterShapesFoldOntoTheUrduOnes() {
        // Text pasted from an Arabic or Persian keyboard carries ي for ی, ك for
        // ک and ه for ہ — same shapes, different code points, and a word
        // spelled with them is one no word list has seen.
        assertEquals("کیا", UrduRomanizer.normalize("كيا"))
        assertEquals("ہے", UrduRomanizer.normalize("هے"))
        // The harakat go the same way: Urdu text normally carries none.
        assertEquals("کتاب", UrduRomanizer.normalize("کِتاب"))
        assertEquals("اللہ", UrduRomanizer.normalize("اللّہ"))
        // Nothing to fold, nothing copied.
        assertEquals("کرنا", UrduRomanizer.normalize("کرنا"))
    }

    @Test fun theVowelUrduLeftOutComesBack() {
        assertEquals("karna", r("کرنا"))
        assertEquals("bacha", r("بچہ"))
        assertEquals("roza", r("روزہ"))
        // …but a word that genuinely closes on a cluster keeps it.
        assertEquals("mard", r("مرد"))
        assertEquals("dost", r("دوست"))
    }

    @Test fun aspirationFoldsIntoTheConsonantBeforeIt() {
        assertEquals("dikh", r("دیکھ"))
        assertEquals("bhi", r("بھی"))
    }

    @Test fun theNasalisationIsAnN() {
        assertEquals("hin", r("ہیں"))
    }

    @Test fun wholeTextKeepsWhatIsNotUrdu() {
        assertEquals("aap kise hin", UrduRomanizer.romanize("آپ کیسے ہیں"))
        assertEquals("kam.", UrduRomanizer.romanize("کام۔"))
        assertEquals("5 kam", UrduRomanizer.romanize("۵ کام"))
        assertEquals("ok karna", UrduRomanizer.romanize("ok کرنا"))
    }
}
