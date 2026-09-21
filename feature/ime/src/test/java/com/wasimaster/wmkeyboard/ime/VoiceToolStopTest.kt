package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.VoiceBarSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The voice tool pressed over a running dictation is the stop button (#283).
 *
 * It used to close the compact bar through `cancelVoice`, and a clip engine
 * has nothing in the field until its clip is transcribed, so for Whisper and
 * the transcription server the obvious way to say "I am done" threw away
 * everything just said. What these pin is the fork itself: a running session
 * is finished and its surface waits for the words, an idle one is put away as
 * before, and the bar's own close button is still the way to abandon.
 *
 * No recognizer and no recorder exist here, so nothing ever lands: finishing
 * with neither is a status change and a no-op `stopListening`. That is enough,
 * because the old path and the new one part before any engine is involved.
 * The old one left the strip gone and the status idle on the very same press.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceToolStopTest {

    private fun onStrip(status: VoiceStatus) = glideReadyState(
        settings = KeyboardSettings(
            learnFromTyping = false,
            voiceBar = VoiceBarSettings(mode = VoiceBarSettings.MODE_STRIP),
        ),
    ).copy(voice = VoiceUi(status = status, strip = true))

    @Test
    fun `the tool over a listening strip finishes the phrase instead of dropping it`() {
        val (service, _, _) = glideKeyboard(onStrip(VoiceStatus.LISTENING))

        service.onToolTap(ToolbarTool.VOICE)

        val voice = service.uiState.value.voice
        assertEquals(VoiceStatus.FINISHING, voice.status)
        assertTrue("the strip has to stay up for the words to land on", voice.strip)
    }

    @Test
    fun `the tool over a clip already being transcribed leaves it to land`() {
        val (service, _, _) = glideKeyboard(onStrip(VoiceStatus.TRANSCRIBING))

        service.onToolTap(ToolbarTool.VOICE)

        val voice = service.uiState.value.voice
        assertEquals(VoiceStatus.TRANSCRIBING, voice.status)
        assertTrue(voice.strip)
    }

    @Test
    fun `the tool over an idle strip still puts it away`() {
        val (service, _, _) = glideKeyboard(onStrip(VoiceStatus.IDLE))

        service.onToolTap(ToolbarTool.VOICE)

        assertFalse(service.uiState.value.voice.strip)
    }

    @Test
    fun `the strip's close button still abandons a running session`() {
        val (service, _, _) = glideKeyboard(onStrip(VoiceStatus.LISTENING))

        service.onVoiceRailKey(VoiceBarAction.CloseStrip)

        val voice = service.uiState.value.voice
        assertEquals(VoiceStatus.IDLE, voice.status)
        assertFalse(voice.strip)
    }
}
