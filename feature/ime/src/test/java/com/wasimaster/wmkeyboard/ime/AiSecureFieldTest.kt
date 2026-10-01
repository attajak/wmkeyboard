package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiActions
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A password field's text never reaches an AI provider.
 *
 * The chat attachment has always refused a secure field
 * ([WMKeyboardService.aiChatAttachmentFromField]), but the AI *action* path read
 * the field with no such check, and the chips are enabled from `aiHasText`
 * alone. With a cloud provider configured, one tap in a password box sent its
 * contents out. The guard now sits on [WMKeyboardService.runAi], the choke point
 * every action passes through, with `aiFieldHasText` greying the chips out to
 * match.
 *
 * Driven like the other service tests here: a subclass with a context attached
 * and `onCreate` never called.
 */
@RunWith(RobolectricTestRunner::class)
class AiSecureFieldTest {

    /** An action that reads the field: the shape the leak had. */
    private val rewrite = BuiltInAiActions.actions.first { it.id == BuiltInAiActions.REWRITE_ID }

    /**
     * A keyboard with a cloud provider ready to send, so nothing but the
     * secure-field guard stands between the tap and a request.
     */
    private fun serviceWith(text: String, secure: Boolean): GlideKeyboard {
        val service = GlideKeyboard(RecordingEditor(initial = text))
        plantPersonalStores(service)
        seedState(
            service,
            glideReadyState(
                glideReady = false,
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    haptics = HapticSettings(enabled = false),
                    ai = AiSettings(provider = AiProvider.ANTHROPIC, anthropicKey = "test-key"),
                ),
                secureField = secure,
                fieldNoSuggestions = false,
            ),
        )
        return service
    }

    /**
     * Refused outright: `AiUi.Idle` is what a run that never started leaves
     * behind. One that did would sit at `AiUi.Loading` — or an error, for a
     * provider the data saver blocked — so any state but Idle means the guard
     * let the request through.
     */
    @Test
    fun `an action in a password field never starts a run`() {
        val service = serviceWith("hunter2", secure = true)
        service.onAiAction(rewrite)
        assertTrue(service.uiState.value.ai is AiUi.Idle)
    }
}
