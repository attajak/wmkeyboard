package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
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
 * A caret that settles at the end of a word has that word re-armed as the
 * composing region, so the strip can still correct it and typing on extends it
 * — unless the enter key is what put the caret there (issue #236).
 *
 * Enter hands the field to the app. A search box keeps the text and a message
 * box empties itself, and either way the word is finished with: re-composing it
 * puts the keyboard back inside text that has already gone out, and the next
 * enter commits that buffer all over again. In a message box, where the app has
 * meanwhile cleared the field without the keyboard being told (a TextWatcher
 * restyling mentions drops the span the same silent way), that commit retypes
 * the message into the empty box and the action sends it a second time — and a
 * third, for as long as the user keeps pressing enter.
 *
 * Driven like the other service tests here: a subclass with a context attached
 * and `onCreate` never called.
 */
@RunWith(RobolectricTestRunner::class)
class EnterResumeTest {

    /** A field the keyboard types into, and the EditorInfo it declares. */
    private class EnterKeyboard(
        private val editor: InputConnection,
        private val info: EditorInfo,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = info
    }

    /** A chat box: single line, and Send behind the enter key. */
    private val messageBox = EditorInfo().also {
        it.inputType = InputType.TYPE_CLASS_TEXT
        it.imeOptions = EditorInfo.IME_ACTION_SEND
    }

    /**
     * A service typing into [editor], with a word list behind it — the resume
     * asks the engine whether there is anything to complete from before it
     * arms anything, so a test without one can never see the behaviour at all.
     */
    private fun keyboardOn(editor: RecordingEditor): EnterKeyboard {
        val service = EnterKeyboard(editor, messageBox)
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
            ),
        )
        return service
    }

    private fun pressEnter(service: EnterKeyboard) =
        service.onKey(Key(label = "\n", action = KeyAction.Enter))

    /**
     * The caret update an editor sends once the text is settled at its end.
     *
     * The clock is walked past the caret-scrub window first: a caret that moves
     * within a few hundred milliseconds of a scrub is treated as a finger still
     * dragging, and Robolectric starts its clock inside that window.
     */
    private fun caretSettles(service: EnterKeyboard, at: Int) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        service.onUpdateSelection(0, 0, at, at, -1, -1)
    }

    @Test
    fun `a caret landing at the end of a word resumes it`() {
        // The control: without the enter key in the story, this is exactly what
        // a tap at the end of "hello" is meant to do.
        val editor = RecordingEditor(initial = "hello")
        val service = keyboardOn(editor)

        caretSettles(service, at = 5)

        assertEquals("hello", service.uiState.value.composingPreview)
    }

    @Test
    fun `the word enter sent is not resumed by the caret update that follows`() {
        val editor = RecordingEditor(initial = "hello")
        val service = keyboardOn(editor)

        pressEnter(service)
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), editor.editorActions)
        caretSettles(service, at = 5)

        assertEquals("", service.uiState.value.composingPreview)
    }

    @Test
    fun `a second enter types nothing of its own`() {
        // The bug as reported: the message goes out again, and again, because
        // each enter commits a buffer the one before it had re-armed.
        val editor = RecordingEditor(initial = "hello")
        val service = keyboardOn(editor)

        pressEnter(service)
        caretSettles(service, at = 5)
        pressEnter(service)

        assertEquals(emptyList<String>(), editor.commits)
        assertEquals("hello", editor.text.toString())
        assertEquals(
            listOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_SEND),
            editor.editorActions,
        )
    }

    @Test
    fun `the block is spent by the update that answers the enter`() {
        // It is a one-shot, not a mode: the next caret landing — a tap back
        // into the word, an edit the app made — resumes as it always did.
        val editor = RecordingEditor(initial = "hello")
        val service = keyboardOn(editor)

        pressEnter(service)
        caretSettles(service, at = 5)
        caretSettles(service, at = 5)

        assertEquals("hello", service.uiState.value.composingPreview)
    }
}
