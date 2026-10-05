package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assamese is Avro's rules with three fix-ups; these pin the fix-ups, and leave
 * Avro's own output to AvroPhoneticTest.
 */
class AssamesePhoneticTest {

    private fun t(input: String) = AssamesePhonetic.transliterate(input)

    @Test fun neverTheBanglaRa() {
        for (word in listOf("ami", "tumar", "ghor", "karon", "bhitor", "rati")) {
            assertFalse(word, '\u09B0' in t(word))
        }
        assertTrue('\u09F0' in t("tumar"))
    }

    @Test fun xIsS() {
        // xokolu সকলো, axom অসম: x is the Assamese s, read before Avro sees it.
        assertEquals(AvroPhonetic.transliterate("sokolu").replace('\u09B0', '\u09F0'), t("xokolu"))
        assertTrue(t("axom").contains('\u09B8'))
    }

    @Test fun aWGlideIsWa() {
        // Avro's ওয় inside a word is Assamese's ৱ…
        val glide = t("suwali")
        assertTrue(glide, '\u09F1' in glide)
        assertFalse(glide, glide.contains("\u0993\u09DF"))
        // …but a word that opens on it keeps its vowel.
        assertTrue(t("wasi").startsWith("\u0993"))
    }

    @Test fun toBengaliIsTheWayIntoTheBanglaIndex() {
        assertEquals("\u09B0\u09AC", AssamesePhonetic.toBengali("\u09F0\u09F1"))
    }

    @Test fun theIndexAnswersInAssameseSpelling() {
        val index = AssamesePhoneticIndex(listOf("\u09A4\u09CB\u09AE\u09BE\u09F0" to 100, "\u09AE\u0987" to 90))
        assertEquals("\u09A4\u09CB\u09AE\u09BE\u09F0", index.lookup("tomar").firstOrNull())
        assertEquals(100, index.frequencyOf("\u09A4\u09CB\u09AE\u09BE\u09F0"))
    }
}
