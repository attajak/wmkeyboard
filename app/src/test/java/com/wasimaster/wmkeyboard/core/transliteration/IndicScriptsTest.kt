package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The move from Devanagari into each script, and back. Expected values from the Python prototype. */
class IndicScriptsTest {

    @Test fun tamilFoldsVoicedStopsAndSpellsTheAnusvaraOut() {
        // गंगा: the ग folds onto க and the anusvara is a ங் before it.
        assertEquals("கங்கா", IndicScript.TAMIL.fromDevanagari("गंगा"))
        assertEquals("ஹிந்தீ", IndicScript.TAMIL.fromDevanagari("हिंदी"))
    }

    @Test fun malayalamClosesOnAChillu() {
        assertEquals("അവൻ", IndicScript.MALAYALAM.fromDevanagari("अवन्"))
        // Not inside a word.
        assertEquals("ന്ന", IndicScript.MALAYALAM.fromDevanagari("न्न"))
        // And back: the chillu is its consonant and a virama.
        assertEquals("अवन्", IndicScript.MALAYALAM.toDevanagari("അവൻ"))
    }

    @Test fun gurmukhiWritesAddakTippiAndNoConjuncts() {
        assertEquals("ਪੱਕਾ", IndicScript.GURMUKHI.fromDevanagari("पक्का")) // ਪੱਕਾ
        assertEquals("ਕੰਮ", IndicScript.GURMUKHI.fromDevanagari("कम्म")) // ਕੰਮ
        assertEquals("ਨੂੰ", IndicScript.GURMUKHI.fromDevanagari("नूं")) // ਨੂੰ
        assertEquals("ਸਵਾਲ", IndicScript.GURMUKHI.fromDevanagari("स्वाल")) // ਸਵਾਲ
        // The addak reads back as the letter written twice.
        assertEquals("पक्का", IndicScript.GURMUKHI.toDevanagari("ਪੱਕਾ"))
        assertEquals("कंम", IndicScript.GURMUKHI.toDevanagari("ਕੰਮ"))
    }

    @Test fun odiaWritesYaByPosition() {
        assertEquals("ଯୋଗ", IndicScript.ORIYA.fromDevanagari("योग")) // ଯୋଗ
        assertEquals("ଜୟ", IndicScript.ORIYA.fromDevanagari("जय")) // ଜୟ
        assertEquals("जय", IndicScript.ORIYA.toDevanagari("ଜୟ"))
    }

    @Test fun theOthersAreAShift() {
        assertEquals("કેમ", IndicScript.GUJARATI.fromDevanagari("केम"))
        assertEquals("నేను", IndicScript.TELUGU.fromDevanagari("नेनु"))
        assertEquals("ನಾನು", IndicScript.KANNADA.fromDevanagari("नानु"))
        assertEquals("नानु", IndicScript.KANNADA.toDevanagari("ನಾನು"))
        assertEquals("abc 12", IndicScript.TELUGU.fromDevanagari("abc 12"))
    }

    @Test fun nativeMeansLettersNotDigitsOrDandas() {
        assertTrue(IndicScript.TAMIL.isNative('க'))
        assertFalse(IndicScript.TAMIL.isNative('௧')) // ௧
        assertFalse(IndicScript.TAMIL.isNative('क'))
        assertFalse(IndicScript.DEVANAGARI.isNative('।'))
    }
}
