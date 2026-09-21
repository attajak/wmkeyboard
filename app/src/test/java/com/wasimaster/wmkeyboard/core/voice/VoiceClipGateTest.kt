package com.wasimaster.wmkeyboard.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceClipGateTest {

    /** A 200 Hz tone of [seconds] at [amplitude]; its RMS is amplitude / sqrt(2). */
    private fun tone(seconds: Double, amplitude: Float) =
        FloatArray((seconds * 16_000).toInt()) { (amplitude * sin(2.0 * PI * 200.0 * it / 16_000)).toFloat() }

    @Test
    fun `a tap-and-release is not speech however loud`() {
        assertFalse(VoiceClipGate.hasSpeech(tone(0.2, 0.5f)))
    }

    @Test
    fun `zeros and a quiet room are not speech`() {
        assertFalse(VoiceClipGate.hasSpeech(FloatArray(16_000 * 5)))
        assertFalse(VoiceClipGate.hasSpeech(tone(5.0, 0.002f)))
    }

    @Test
    fun `one loud stretch in a quiet clip is enough`() {
        val clip = FloatArray(16_000 * 5)
        tone(0.5, 0.1f).copyInto(clip, 16_000 * 2)
        assertTrue(VoiceClipGate.hasSpeech(clip))
        assertFalse(VoiceClipGate.isFaint(clip))
    }

    @Test
    fun `a faint clip is transcribed but not trusted`() {
        val clip = tone(2.0, 0.008f)
        assertTrue(VoiceClipGate.hasSpeech(clip))
        assertTrue(VoiceClipGate.isFaint(clip))
    }

    @Test
    fun `a stock phrase is dropped only over a faint clip`() {
        assertEquals("", VoiceClipGate.clean(" Thank you.", faint = true))
        assertEquals("", VoiceClipGate.clean("Thanks for watching!", faint = true))
        assertEquals("", VoiceClipGate.clean("Subtitles by the Amara.org community", faint = true))
        assertEquals("Thank you.", VoiceClipGate.clean(" Thank you.", faint = false))
    }

    @Test
    fun `real words over a faint clip are kept`() {
        assertEquals("Thank you for the flowers.", VoiceClipGate.clean("Thank you for the flowers.", faint = true))
    }

    @Test
    fun `a sound caption is never typed`() {
        assertEquals("", VoiceClipGate.clean("[Music]", faint = false))
        assertEquals("", VoiceClipGate.clean("(coughs)", faint = false))
        assertEquals("", VoiceClipGate.clean("*sighs*", faint = false))
        assertEquals("see (page two) now", VoiceClipGate.clean("see (page two) now", faint = false))
    }

    @Test
    fun `a decoder loop folds to one copy`() {
        assertEquals("be there at five", VoiceClipGate.clean("be there at five five five five five five. five", faint = false))
        assertEquals(
            "okay thank you",
            VoiceClipGate.clean("okay thank you thank you thank you thank you thank you", faint = false),
        )
        // …and what is left is judged like any other transcript.
        assertEquals("", VoiceClipGate.clean("thank you thank you thank you thank you thank you", faint = true))
    }

    @Test
    fun `saying a word a few times is not a loop`() {
        assertEquals("no no no no", VoiceClipGate.clean("no no no no", faint = false))
    }
}
