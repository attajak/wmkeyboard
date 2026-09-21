package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.KdeConnectSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the keys belong to the paired computer (issue #285), and — more to the
 * point — when they stop. While [KeyboardUiState.kdeTypingActive] is true,
 * nothing typed reaches the app behind the keyboard, so every way out of that
 * state has to hand the keys back by itself. Plain JVM: the ladder is a `when`.
 */
class KdeCaptureTest {

    private fun state(
        panel: PanelMode = PanelMode.KDE_CONNECT,
        kde: KdeUi = KdeUi(typing = true),
        compose: Boolean = false,
    ) = KeyboardUiState(
        panel = panel,
        kde = kde,
        settings = KeyboardSettings(kdeConnect = KdeConnectSettings(enabled = true, composeMode = compose)),
    )

    @Test
    fun `typing on the computer takes the keys, live or composed`() {
        assertEquals(CaptureTarget.KDE_REMOTE, state().captureTarget())
        assertEquals(CaptureTarget.KDE_COMPOSE, state(compose = true).captureTarget())
        assertTrue(state().keysTakenByKeyboard)
    }

    @Test
    fun `the line is the buffer, and the live line has no caret to move`() {
        val s = state(kde = KdeUi(typing = true, line = "hello"))
        assertEquals("hello", s.captureBuffer())
        assertFalse(CaptureTarget.KDE_REMOTE.movableCaret)
        assertTrue(CaptureTarget.KDE_COMPOSE.movableCaret)
        // Words, so glide and the suggestion strip work while typing there.
        assertTrue(CaptureTarget.KDE_REMOTE.takesWords)
    }

    @Test
    fun `every way out of the input tab hands the keys back`() {
        assertNull(state(panel = PanelMode.NONE).captureTarget())
        assertNull(state(panel = PanelMode.EMOJI).captureTarget())
        assertNull(state(kde = KdeUi(typing = true, tab = KdeTab.MEDIA)).captureTarget())
        assertNull(state(kde = KdeUi(typing = true, showDevices = true)).captureTarget())
        assertNull(state(kde = KdeUi(typing = false)).captureTarget())
    }

    @Test
    fun `the address box outranks typing and is not a word field`() {
        val s = state(kde = KdeUi(typing = true, hostEntry = true, hostDraft = "192.168.1.7"))
        assertEquals(CaptureTarget.KDE_HOST, s.captureTarget())
        assertEquals("192.168.1.7", s.captureBuffer())
        assertFalse(CaptureTarget.KDE_HOST.takesWords)
        // …and it, too, is only ever the panel's.
        assertNull(s.copy(panel = PanelMode.NONE).captureTarget())
    }
}
