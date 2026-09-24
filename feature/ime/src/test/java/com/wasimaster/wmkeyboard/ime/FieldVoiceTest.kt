package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dictation into the keyboard's own fields (#353): which fields carry the
 * microphone, and when a field's strip gives its chips' room to the dictation.
 *
 * Plain JVM: both are pure functions of the enum and the state. The session
 * itself needs a recognizer, which a unit test has none of.
 */
class FieldVoiceTest {

    @Test
    fun `prose fields and searches take dictation`() {
        for (target in listOf(
            CaptureTarget.AI_CHAT, CaptureTarget.AI_CUSTOM, CaptureTarget.EMOJI_SEARCH,
            CaptureTarget.MEDIA_SEARCH, CaptureTarget.DICTIONARY_SEARCH, CaptureTarget.CLIPBOARD_SEARCH,
            CaptureTarget.CLIP_EDIT, CaptureTarget.FIND_QUERY, CaptureTarget.FIND_REPLACEMENT,
            CaptureTarget.PLUGIN, CaptureTarget.KDE_COMPOSE,
        )) {
            assertTrue(target.name, target.takesDictation)
        }
    }

    @Test
    fun `one-word, scored and numeric fields do not`() {
        for (target in listOf(
            CaptureTarget.TYPING_TEST, CaptureTarget.WORD_SPELL, CaptureTarget.LEARN_EDIT,
            CaptureTarget.CALC, CaptureTarget.CONVERTER, CaptureTarget.KDE_HOST,
        )) {
            assertFalse(target.name, target.takesDictation)
        }
    }

    @Test
    fun `only search boxes drop the phrase's full stop`() {
        assertTrue(CaptureTarget.EMOJI_SEARCH.isSearch)
        assertTrue(CaptureTarget.FIND_QUERY.isSearch)
        assertFalse(CaptureTarget.AI_CHAT.isSearch)
        assertFalse(CaptureTarget.CLIP_EDIT.isSearch)
        // What goes in the replacement is text, not a query.
        assertFalse(CaptureTarget.FIND_REPLACEMENT.isSearch)
    }

    @Test
    fun `an idle dictation leaves the strip its chips`() {
        val state = searching(VoiceUi(field = CaptureTarget.EMOJI_SEARCH.name))
        assertEquals(CaptureTarget.EMOJI_SEARCH, state.captureTarget())
        assertFalse(state.fieldVoiceSpeaks())
    }

    @Test
    fun `listening in this field takes the strip`() {
        val state = searching(VoiceUi(field = CaptureTarget.EMOJI_SEARCH.name, status = VoiceStatus.LISTENING))
        assertTrue(state.fieldVoiceSpeaks())
    }

    @Test
    fun `a missing model is said on the strip even while idle`() {
        val state = searching(VoiceUi(field = CaptureTarget.EMOJI_SEARCH.name, whisperNeedsModel = true))
        assertTrue(state.fieldVoiceSpeaks())
    }

    @Test
    fun `a dictation for the app's field or another box is not this field's`() {
        assertFalse(searching(VoiceUi(status = VoiceStatus.LISTENING)).fieldVoiceSpeaks())
        val elsewhere = VoiceUi(field = CaptureTarget.CLIPBOARD_SEARCH.name, status = VoiceStatus.LISTENING)
        assertFalse(searching(elsewhere).fieldVoiceSpeaks())
    }

    private fun searching(voice: VoiceUi) =
        KeyboardUiState(emojiQuery = "cat", emojiSearchActive = true, voice = voice)
}
