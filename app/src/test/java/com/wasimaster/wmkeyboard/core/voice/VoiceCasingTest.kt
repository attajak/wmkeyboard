package com.wasimaster.wmkeyboard.core.voice

import org.junit.Assert.assertEquals
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
}
