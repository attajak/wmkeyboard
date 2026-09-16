package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * How a newline reaches a field that declared no editor action: as a key event,
 * or committed as text.
 *
 * The key event is the default and has to stay the default — a web page's
 * handlers and a terminal both need a real Enter. The exception is issue #214:
 * a multi-line box flying IME_FLAG_NO_ENTER_ACTION (Discord's message box, and
 * every React Native chat input) reads KEYCODE_ENTER as a submit, so the key
 * event sent the half-written message the flag had just asked the keyboard not
 * to send. There the break is committed, which nothing can intercept.
 *
 * Driven like the other service tests here: a subclass with a context attached
 * and `onCreate` never called, which Robolectric has no shadow for.
 */
@RunWith(RobolectricTestRunner::class)
class EnterNewlineFieldTest {

    /** A field the keyboard types into, and the EditorInfo it declares. */
    private class EnterKeyboard(
        private val editor: InputConnection,
        private val info: EditorInfo,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = info
    }

    private fun editorInfo(inputType: Int, imeOptions: Int) = EditorInfo().also {
        it.inputType = inputType
        it.imeOptions = imeOptions
    }

    /** Taps the on-screen Enter key against a field shaped by [info]. */
    private fun pressEnter(info: EditorInfo): RecordingEditor {
        val editor = RecordingEditor()
        val service = EnterKeyboard(editor, info)
        plantPersonalStores(service)
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    haptics = HapticSettings(enabled = false),
                ),
            ),
        )
        service.onKey(Key(label = "\n", action = KeyAction.Enter))
        return editor
    }

    /** Discord's message box, as `dumpsys input_method` reports it (#214). */
    private val discordBox = editorInfo(
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_DONE,
    )

    @Test
    fun `a multi-line box that refused the enter action gets a committed break`() {
        val editor = pressEnter(discordBox)

        assertEquals(listOf("\n"), editor.commits)
        assertFalse(
            "the key event Discord reads as a submit still went out",
            KeyEvent.KEYCODE_ENTER in editor.keys,
        )
    }

    @Test
    fun `a plain multi-line field still gets the key event`() {
        // A textarea in a web page: multi-line, IME_ACTION_NONE, and no flag —
        // which is what Chromium declares. Its handlers need a real Enter.
        val editor = pressEnter(
            editorInfo(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                imeOptions = EditorInfo.IME_ACTION_NONE,
            ),
        )

        assertTrue(KeyEvent.KEYCODE_ENTER in editor.keys)
        assertEquals(emptyList<String>(), editor.commits)
    }

    @Test
    fun `a terminal still gets the key event`() {
        // TYPE_NULL, no action, no flag: nothing but the event reaches it.
        val editor = pressEnter(editorInfo(inputType = InputType.TYPE_NULL, imeOptions = 0))

        assertTrue(KeyEvent.KEYCODE_ENTER in editor.keys)
        assertEquals(emptyList<String>(), editor.commits)
    }

    @Test
    fun `a single-line field that refused the action still gets the key event`() {
        // The flag without MULTI_LINE is not the pair this is about: a
        // single-line box has no line to break, and whatever it does with the
        // event it is not putting a newline in itself.
        val editor = pressEnter(
            editorInfo(
                inputType = InputType.TYPE_CLASS_TEXT,
                imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_DONE,
            ),
        )

        assertTrue(KeyEvent.KEYCODE_ENTER in editor.keys)
        assertEquals(emptyList<String>(), editor.commits)
    }

    @Test
    fun `a field that declared an action still fires it`() {
        // The branch ahead of both: Discord's own actionDone, with the flag
        // taken off, is an action and not a newline.
        val editor = pressEnter(
            editorInfo(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                imeOptions = EditorInfo.IME_ACTION_DONE,
            ),
        )

        assertEquals(emptyList<String>(), editor.commits)
        assertFalse(KeyEvent.KEYCODE_ENTER in editor.keys)
        assertEquals(listOf(EditorInfo.IME_ACTION_DONE), editor.editorActions)
    }
}
