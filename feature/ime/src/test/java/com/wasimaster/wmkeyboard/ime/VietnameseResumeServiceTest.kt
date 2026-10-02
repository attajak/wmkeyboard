package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.input.composer.VietnameseTelexComposer
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * What a Vietnamese word does at the edges of being typed: coming back to it,
 * and taking it back.
 *
 * Telex's buffer is the keys and the field holds the word they spelled, so the
 * word has to be spelled back into keys before it can be typed into
 * (`Composer.resumeBuffer`), and a backspace has to take a letter off the word
 * rather than a key off that spelling (`Composer.backspaceBuffer`). Both are
 * checked here end to end: type, commit, backspace the space away, and see
 * whether the next tone key lands on the word or is spelled into it.
 */
@RunWith(RobolectricTestRunner::class)
class VietnameseResumeServiceTest {

    /**
     * A plain text field. The editor info matters: with none, the service reads
     * the field as a null one and sends backspace out as a key event instead of
     * deleting text — which the first version of this test did, and which made
     * the space look as though the keyboard had refused to take it back.
     */
    private val textField = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT
    }

    private class ViKeyboard(
        private val editor: InputConnection,
        private val info: EditorInfo,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = info
    }

    private fun keyboardOn(
        editor: RecordingEditor,
        shift: ShiftState = ShiftState.OFF,
    ): ViKeyboard {
        val service = ViKeyboard(editor, textField)
        plantPersonalStores(service)
        val engine = SuggestionEngine(
            Trie().apply { insert("hello", 100) },
            BengaliPhoneticIndex(emptyList()),
            UserLexicon(null),
        )
        val field = WMKeyboardService::class.java.getDeclaredField("suggestionEngine")
        field.isAccessible = true
        field.set(service, engine)
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    haptics = HapticSettings(enabled = false),
                ),
                fieldNoSuggestions = false,
                shiftState = shift,
            ).copy(
                composer = VietnameseTelexComposer,
                language = LanguageRegistry.byId("vi"),
            ),
        )
        return service
    }

    private fun type(service: ViKeyboard, text: String) {
        for (ch in text) {
            service.onKey(
                if (ch == ' ') Key(label = " ", action = KeyAction.Space)
                else Key(label = ch.toString(), action = KeyAction.Text),
            )
        }
    }

    private fun pressBackspace(service: ViKeyboard) =
        service.onKey(Key(label = "⌫", action = KeyAction.Delete))

    /** The caret update a real editor sends once the text settles at its end. */
    private fun caretSettles(service: ViKeyboard, at: Int) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        service.onUpdateSelection(0, 0, at, at, -1, -1)
    }

    @Test
    fun `the word is composed and committed as usual`() {
        // The control: everything up to the caret coming back.
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "tois")
        assertEquals("tói", editor.text.toString())

        type(service, " ")
        assertEquals("tói ", editor.text.toString())
    }

    @Test
    fun `a caret back at the word arms it as the composing region`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "tois ")
        pressBackspace(service)
        assertEquals("the space went", "tói", editor.text.toString())
        caretSettles(service, at = 3)

        assertEquals("tói", service.uiState.value.composingPreview)
    }

    @Test
    fun `the editor's own report of the armed region does not drop it`() {
        // A real editor echoes every edit back as an onUpdateSelection, the
        // composing region included. That echo is the one thing this harness
        // does not send on its own — and it is where a buffer that is not the
        // field's text (Telex's `tois` behind the field's `tói`) differs from
        // one that is.
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "tois ")
        pressBackspace(service)
        caretSettles(service, at = 3)
        // The echo: caret after the region, the region spanning the word.
        service.onUpdateSelection(3, 3, 3, 3, 0, 3)

        assertEquals("tói", service.uiState.value.composingPreview)
        type(service, "f")
        assertEquals("tòi", editor.text.toString())
    }

    @Test
    fun `a w that takes back the engine's own ư keeps the sentence's capital`() {
        // At the start of a sentence the keyboard capitalises the first key and
        // not the ones after it, so `ww` arrives as `Ww`. The second `w` is the
        // replacement of the `Ư` the first one made, so it takes that letter's
        // case rather than the lower case it is drawn as.
        val editor = RecordingEditor()
        val service = keyboardOn(editor, shift = ShiftState.ON)

        type(service, "ww")
        assertEquals("W", editor.text.toString())
    }

    @Test
    fun `a word the keys cannot spell is not taken into the buffer`() {
        // From a device log: the caret settling after the space armed the
        // composing region with `Web` itself. The reach check asked `transduce`
        // — which hands a word it cannot spell straight back as its own keys —
        // and read that answer as a spelling of the word. The buffer then held
        // letters instead of keys, and backspacing it down to `W` showed `Ư`,
        // because `W` is a `w` key to the composer.
        val editor = RecordingEditor()
        val service = keyboardOn(editor, shift = ShiftState.ON)

        type(service, "wweb ")
        assertEquals("Web ", editor.text.toString())

        pressBackspace(service)
        caretSettles(service, at = 3)
        assertEquals("Web", editor.text.toString())
        pressBackspace(service)
        assertEquals("We", editor.text.toString())
        pressBackspace(service)
        assertEquals("W", editor.text.toString())
        pressBackspace(service)
        assertEquals("", editor.text.toString())
    }

    // --- taking the word back -----------------------------------------------

    @Test
    fun `a backspace takes off a letter, not a key`() {
        // `hướng` is five letters on seven keys, so five presses clear it —
        // where working a key at a time cost eight, and the first three of
        // them only ever peeled marks off letters that stayed.
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "huowngs")
        assertEquals("hướng", editor.text.toString())

        var presses = 0
        while (editor.text.isNotEmpty() && presses < 20) {
            pressBackspace(service)
            presses++
        }
        assertEquals(5, presses)
    }

    @Test
    fun `a tone comes off with the letter it is on`() {
        // `hif` is `hì`: the first press takes the whole `ì` rather than
        // spending itself on the `f` and leaving `hi`.
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "hif")
        assertEquals("hì", editor.text.toString())

        pressBackspace(service)
        assertEquals("h", editor.text.toString())
        pressBackspace(service)
        assertEquals("", editor.text.toString())
    }

    @Test
    fun `a tone key typed after coming back lands on the word`() {
        // The whole point: `f` after the caret returns to `tói` has to tone it
        // into `tòi`, not spell itself into `tóif`.
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "tois ")
        pressBackspace(service)
        caretSettles(service, at = 3)
        type(service, "f")

        assertEquals("tòi", editor.text.toString())
    }
}
