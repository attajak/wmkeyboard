package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Deleting in a terminal (issue #268).
 *
 * Termux declares TYPE_NULL and hands the IME a bare `BaseInputConnection` over
 * a dummy buffer nothing ever writes to: every read comes back empty however
 * long the command line is, and the only edit that reaches the terminal is a
 * key event. Holding backspace there deleted exactly one character, because the
 * repeat loop polls [WMKeyboardService.canDelete] and the empty read looked
 * like the start of the text.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalDeleteTest {

    /** Termux's connection: key events land, reads are always empty. */
    private class TerminalEditor : BaseRecordingTerminal() {
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
    }

    private open class BaseRecordingTerminal :
        android.view.inputmethod.BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val keys = mutableListOf<Int>()
        val deletions = mutableListOf<Pair<Int, Int>>()

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            event?.let { if (it.action == KeyEvent.ACTION_DOWN) keys += it.keyCode }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletions += beforeLength to afterLength
            return true
        }
    }

    private class TerminalKeyboard(
        private val editor: InputConnection,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = EditorInfo().also {
            it.inputType = InputType.TYPE_NULL
            it.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
    }

    /** A service pointed at a terminal, with the state a key press needs. */
    private fun terminal(): Pair<TerminalKeyboard, TerminalEditor> {
        val editor = TerminalEditor()
        val service = TerminalKeyboard(editor)
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
        return service to editor
    }

    @Test
    fun `a held backspace keeps repeating in a terminal`() {
        val (service, _) = terminal()

        // What the repeat loop polls between ticks. A terminal's empty read is
        // not an empty command line, so it must not stop the hold.
        assertTrue(service.canDelete())
        assertTrue(service.canDeleteField())
    }

    @Test
    fun `each backspace tick reaches the terminal as a key event`() {
        val (service, editor) = terminal()

        repeat(3) { service.onKey(Key(label = "⌫", action = KeyAction.Delete)) }

        assertEquals(listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DEL), editor.keys)
        assertEquals("nothing went to the dummy buffer", emptyList<Pair<Int, Int>>(), editor.deletions)
    }

    @Test
    fun `a word-clearing hold falls back to one character a tick`() {
        val (service, editor) = terminal()

        // "Delete words on hold" in a field with no readable word boundary:
        // one character beats the nothing the word step used to delete.
        service.onDeleteSwipeUnit(byWord = true, forward = false)

        assertEquals(listOf(KeyEvent.KEYCODE_DEL), editor.keys)
    }

    @Test
    fun `forward delete goes out as a key event too`() {
        val (service, editor) = terminal()

        assertTrue(service.canForwardDelete())
        service.onKey(Key(label = "⌦", action = KeyAction.ForwardDelete))
        service.onDeleteSwipeUnit(byWord = true, forward = true)

        assertEquals(
            listOf(KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_FORWARD_DEL),
            editor.keys,
        )
        assertEquals(emptyList<Pair<Int, Int>>(), editor.deletions)
    }

    @Test
    fun `a delete swipe takes no preview in a terminal`() {
        val (service, _) = terminal()

        // -1 is "no preview to be had here", which sends the gesture down the
        // delete-as-you-go path whose key events the terminal actually hears.
        assertEquals(-1, service.onDeleteSwipeSelect(units = 1, byWord = false, forward = false))
    }
}
