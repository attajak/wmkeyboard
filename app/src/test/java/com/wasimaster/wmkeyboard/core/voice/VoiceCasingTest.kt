package com.wasimaster.wmkeyboard.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recognizer's per-utterance capital, kept or taken back by where the words land (#182). */
class VoiceCasingTest {

    @Test
    fun `a capital mid-sentence is lowered`() {
        assertEquals("on this device", VoiceCasing.apply("On this device", sentenceStart = false))
    }

    @Test
    fun `a capital at a sentence start stays`() {
        assertEquals("On this device", VoiceCasing.apply("On this device", sentenceStart = true))
    }

    @Test
    fun `the pronoun I and its contractions keep their capital anywhere`() {
        assertEquals("I think so", VoiceCasing.apply("I think so", sentenceStart = false))
        assertEquals("I'm here", VoiceCasing.apply("I'm here", sentenceStart = false))
        assertEquals("I’ll go", VoiceCasing.apply("I’ll go", sentenceStart = false))
        assertEquals("I", VoiceCasing.apply("I", sentenceStart = false))
    }

    @Test
    fun `a word with a second capital is spelled that way and is left alone`() {
        assertEquals("NASA launched", VoiceCasing.apply("NASA launched", sentenceStart = false))
        assertEquals("McKinsey said", VoiceCasing.apply("McKinsey said", sentenceStart = false))
    }

    @Test
    fun `only the first word is touched`() {
        assertEquals("the Boston office", VoiceCasing.apply("The Boston office", sentenceStart = false))
    }

    @Test
    fun `text that opens without a capital passes through`() {
        assertEquals("", VoiceCasing.apply("", sentenceStart = false))
        assertEquals("hello", VoiceCasing.apply("hello", sentenceStart = false))
        assertEquals("42 things", VoiceCasing.apply("42 things", sentenceStart = false))
        assertEquals("Ich", VoiceCasing.apply("Ich", sentenceStart = false).replaceFirstChar { it.uppercaseChar() })
    }

    @Test
    fun `an empty field opens a sentence`() {
        assertTrue(VoiceCasing.startsSentence(null))
        assertTrue(VoiceCasing.startsSentence(""))
    }

    @Test
    fun `a field holding only blanks opens a sentence`() {
        assertTrue(VoiceCasing.startsSentence(" "))
        assertTrue(VoiceCasing.startsSentence("   "))
    }

    @Test
    fun `a full stop behind the caret opens a sentence`() {
        assertTrue(VoiceCasing.startsSentence("Done."))
        assertTrue(VoiceCasing.startsSentence("Done. "))
        assertTrue(VoiceCasing.startsSentence("Really?  "))
        assertTrue(VoiceCasing.startsSentence("Stop! "))
        assertTrue(VoiceCasing.startsSentence("and so on… "))
    }

    @Test
    fun `a script's own full stop ends a sentence too`() {
        assertTrue(VoiceCasing.startsSentence("শেষ। "))
        assertTrue(VoiceCasing.startsSentence("終わり。"))
        assertTrue(VoiceCasing.startsSentence("վերջ։ "))
    }

    @Test
    fun `a terminator behind a closing quote or bracket still counts`() {
        assertTrue(VoiceCasing.startsSentence("""he said "stop." """))
        assertTrue(VoiceCasing.startsSentence("(for now.) "))
        assertTrue(VoiceCasing.startsSentence("he said ‘go.’ "))
    }

    @Test
    fun `a new line opens a sentence`() {
        assertTrue(VoiceCasing.startsSentence("a list\n"))
        assertTrue(VoiceCasing.startsSentence("a list\n  "))
    }

    @Test
    fun `mid-sentence does not`() {
        assertFalse(VoiceCasing.startsSentence("and then, "))
        assertFalse(VoiceCasing.startsSentence("the "))
        assertFalse(VoiceCasing.startsSentence("the"))
        assertFalse(VoiceCasing.startsSentence("it won't "))
        assertFalse(VoiceCasing.startsSentence("a quote \""))
    }

    @Test
    fun `the field's own caps mode has no say — an empty field keeps the capital`() {
        // The regression this pins: a plain inputType="text" field never asks
        // for sentence capitals, and Automatic capitals may be off, yet a
        // dictation into the empty field still opens a sentence (#182).
        val sentenceStart = VoiceCasing.startsSentence("")
        assertEquals("Hello world", VoiceCasing.apply("Hello world", sentenceStart))
    }
}
