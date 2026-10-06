package com.wasimaster.wmkeyboard.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an AI rewrite of a dictated phrase may put in the field (#499). */
class VoiceTidyTest {

    @Test
    fun `a word or two is not worth a request`() {
        assertFalse(VoiceTidy.worthTidying("hello"))
        assertFalse(VoiceTidy.worthTidying(" ok then "))
        assertTrue(VoiceTidy.worthTidying("um so let's meet at three no four"))
    }

    @Test
    fun `a cleaned answer is taken`() {
        assertEquals(
            "Let's meet at four.",
            VoiceTidy.accept("Let's meet at four.", "Um, let's meet at three, no, four."),
        )
    }

    @Test
    fun `quotes the model put around the answer come off`() {
        assertEquals("meet at four", VoiceTidy.accept("\"meet at four\"", "um meet at three no four"))
        assertEquals("meet at four", VoiceTidy.accept("“meet at four”", "um meet at three no four"))
    }

    @Test
    fun `quotes the speaker dictated stay`() {
        assertEquals(
            "\"hello there\"",
            VoiceTidy.accept("\"hello there\"", "\"hello hello there\""),
        )
    }

    @Test
    fun `blank and unchanged answers are not used`() {
        assertNull(VoiceTidy.accept("   ", "um meet at four"))
        assertNull(VoiceTidy.accept("meet at four", " meet at four "))
    }

    @Test
    fun `an answer that grew is a reply, not a cleanup`() {
        val heard = "what is the capital of France"
        val reply = "The capital of France is Paris. It has been the capital since the tenth century."
        assertNull(VoiceTidy.accept(reply, heard))
    }

    @Test
    fun `the rewrite keeps the spaces the phrase landed with`() {
        assertEquals(" meet at four ", VoiceTidy.land(" um meet at four ", "meet at four"))
        assertEquals("meet at four", VoiceTidy.land("um meet at four", " meet at four "))
    }
}
