package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicScriptPhoneticIndexTest {

    @Test fun arabiziDigitsAreTheLettersTheFoldReads() {
        assertEquals("H'b'b'", ArabicScriptPhoneticIndex.arabizi("7'b'b'"))
        assertEquals("Habibi", ArabicScriptPhoneticIndex.arabizi("7abibi"))
        assertEquals("'arabi", ArabicScriptPhoneticIndex.arabizi("3arabi"))
        assertEquals("shoghl", ArabicScriptPhoneticIndex.arabizi("sho3'l"))
        assertEquals("Za'eef", ArabicScriptPhoneticIndex.arabizi("9'a3eef"))
        assertEquals("khobz", ArabicScriptPhoneticIndex.arabizi("5obz"))
        assertEquals("Tayyib", ArabicScriptPhoneticIndex.arabizi("6ayyib"))
        assertEquals("qalbi", ArabicScriptPhoneticIndex.arabizi("8albi"))
        assertEquals("Sabah", ArabicScriptPhoneticIndex.arabizi("9abah"))
        // Nothing to rewrite, nothing copied.
        assertEquals("salam", ArabicScriptPhoneticIndex.arabizi("salam"))
    }

    @Test fun persianGhIsAlsoQaf() {
        assertEquals(listOf("ghashang", "qashang"), ArabicScriptPhoneticIndex.persianReadings("ghashang"))
        assertEquals(listOf("salam"), ArabicScriptPhoneticIndex.persianReadings("salam"))
    }

    @Test fun egyptianGIsAlsoJim() {
        assertEquals(listOf("gamil", "jamil"), ArabicScriptPhoneticIndex.arabicReadings("gamil"))
        assertEquals(listOf("ghali"), ArabicScriptPhoneticIndex.arabicReadings("ghali"))
    }

    @Test fun arabicWordsFoldLikeUrduOnes() {
        // ي ك ه ة fold onto the letters the Urdu fold keys on, and the answer is
        // the list's own spelling.
        val index = ArabicScriptPhoneticIndex(
            listOf("\u062D\u0628\u064A\u0628\u064A" to 500, "\u0643\u062A\u0627\u0628" to 400),
            ArabicScriptPhoneticIndex::arabicReadings,
        )
        assertEquals("\u062D\u0628\u064A\u0628\u064A", index.lookup("7abibi").firstOrNull())
        assertEquals("\u0643\u062A\u0627\u0628", index.lookup("kitab").firstOrNull())
    }

    @Test fun persianQafIsReachedThroughGh() {
        val index = ArabicScriptPhoneticIndex(
            listOf("\u0642\u0634\u0646\u06AF" to 300),
            ArabicScriptPhoneticIndex::persianReadings,
        )
        assertTrue(index.lookup("ghashang").contains("\u0642\u0634\u0646\u06AF"))
    }
}
