package com.wasimaster.wmkeyboard.core.voice.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperScriptTest {

    /** Bangla spoken, decoded as Hindi: right words, wrong alphabet. */
    @Test
    fun `devanagari output on a bangla layout is converted`() {
        // "আমি ভালো আছি" as Whisper spells it when it thinks the language is Hindi.
        assertEquals("আমি ভালো আছি", WhisperScript.rescue("আমি ভালো আছি".toDevanagari(), "bn"))
    }

    @Test
    fun `bangla output on a bangla layout is left alone`() {
        val text = "আমি ভালো আছি"
        assertEquals(text, WhisperScript.rescue(text, "bn"))
    }

    @Test
    fun `bangla output on a hindi layout is converted the other way`() {
        val devanagari = "मैं ठीक हूँ"
        assertEquals(devanagari, WhisperScript.rescue(devanagari.toBengali(), "hi"))
    }

    @Test
    fun `a combined choice with one convertible script corrects towards it`() {
        // English and Bangla dictated together (#416): Hindi letters can only
        // be Bangla misread.
        assertEquals("আমি ভালো আছি", WhisperScript.rescueAmong("আমি ভালো আছি".toDevanagari(), listOf("en", "bn")))
    }

    @Test
    fun `a combined choice with both scripts leaves the text alone`() {
        val devanagari = "मैं ठीक हूँ"
        assertEquals(devanagari, WhisperScript.rescueAmong(devanagari, listOf("bn", "hi")))
    }

    @Test
    fun `a language outside the two scripts is never touched`() {
        // English, and a script the conversion knows nothing about.
        assertEquals("hello there", WhisperScript.rescue("hello there", "en"))
        assertEquals("привет", WhisperScript.rescue("привет", "ru"))
        // Devanagari text while typing English stays as it is: nothing here can
        // say which script English wanted.
        assertEquals("नमस्ते", WhisperScript.rescue("नमस्ते", "en"))
    }

    @Test
    fun `latin and punctuation pass through a conversion untouched`() {
        val mixed = "मैं Android 15 ब्यबहार करि। ok?"
        val out = WhisperScript.rescue(mixed, "bn")
        assertTrue(out, "Android 15" in out)
        assertTrue(out, out.endsWith(" ok?"))
        // The danda is written with the same codepoint in both scripts.
        assertTrue(out, '।' in out)
        assertTrue(out, out.none { it.code in 0x0900..0x0963 })
    }

    @Test
    fun `digits convert with the rest of the script`() {
        // Devanagari digits become Bangla digits.
        assertEquals("১২৩", WhisperScript.rescue("१२३", "bn"))
    }

    @Test
    fun `latin around one indic word counts for neither script`() {
        // The Latin is evidence of nothing, so a single Devanagari word still
        // decides that the line came back in the wrong script, and the Latin
        // passes through the conversion unchanged.
        val bangla = "ok \u0986\u09AE\u09BF ok"
        assertEquals(bangla, WhisperScript.rescue(bangla.toDevanagari(), "bn"))
    }

    @Test
    fun `empty input is returned as is`() {
        assertEquals("", WhisperScript.rescue("", "bn"))
    }

    @Test
    fun `nukta letters come out precomposed`() {
        // Devanagari YYA/DDDHA/RHA line up by offset with the three Bangla letters
        // that have a precomposed form. Emitting the decomposed spelling instead
        // would break every dictionary lookup downstream.
        val out = WhisperScript.rescue("य़ड़ढ़", "bn")
        assertEquals("য়ড়ঢ়", out)
    }

    @Test
    fun `marks bangla does not write are dropped rather than left unassigned`() {
        // The Vedic stress marks have no Bangla codepoint at the shared offset.
        assertEquals("ক", WhisperScript.rescue("क॒॑", "bn"))
    }

    @Test
    fun `every converted character is assigned in the target script`() {
        // The whole Devanagari block through the converter: nothing may come out
        // in an unassigned Bangla slot, which is what an offset-only mapping did.
        val all = (0x0900..0x097F).map { it.toChar() }.joinToString("")
        val out = WhisperScript.rescue(all, "bn")
        for (ch in out) {
            val cp = ch.code
            if (cp in 0x0900..0x097F) continue // shared punctuation passes through
            assertTrue("U+%04X is not assigned in Bangla".format(cp), cp in ASSIGNED_BENGALI)
        }
    }

    private companion object {
        /**
         * The assigned codepoints of the Bangla block, as of Unicode 15. Spelled
         * out because the JVM's own tables move with the platform, and the claim
         * under test is about the mapping, not about the runtime.
         */
        val ASSIGNED_BENGALI: Set<Int> = buildSet {
            addAll(0x0980..0x0983)
            addAll(0x0985..0x098C)
            addAll(0x098F..0x0990)
            addAll(0x0993..0x09A8)
            addAll(0x09AA..0x09B0)
            add(0x09B2)
            addAll(0x09B6..0x09B9)
            addAll(0x09BC..0x09C4)
            addAll(0x09C7..0x09C8)
            addAll(0x09CB..0x09CE)
            add(0x09D7)
            addAll(0x09DC..0x09DD)
            addAll(0x09DF..0x09E3)
            addAll(0x09E6..0x09FE)
        }

        /** Rewrites Bangla letters into Devanagari by the offset the blocks share. */
        fun String.toDevanagari(): String = map { ch ->
            if (ch.code in 0x0980..0x09FF) (ch.code - 0x80).toChar() else ch
        }.joinToString("")

        /** The inverse, for building a wrong-script fixture in the other direction. */
        fun String.toBengali(): String = map { ch ->
            if (ch.code in 0x0900..0x097F) (ch.code + 0x80).toChar() else ch
        }.joinToString("")
    }
}
