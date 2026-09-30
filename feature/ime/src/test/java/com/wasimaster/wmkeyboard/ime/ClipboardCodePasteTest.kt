package com.wasimaster.wmkeyboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.clipboard.ClipboardStore
import com.wasimaster.wmkeyboard.core.input.composer.VietnameseTelexComposer
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * A copied code pasted from the keyboard's paste chip, into a Vietnamese
 * (Telex) field.
 *
 * A bare code goes into the field one character at a time — that is what the
 * per-character boxes of a verification form are built for ([commitCodeToField])
 * — and every one of those commits is echoed back by the editor as a caret
 * move. Read as the user having brought the caret back to a word, the word so
 * far typed was re-armed as the composing region; the next character of the
 * same run then replaced that region and ate everything under it. The code
 * below lands as `P4T9R` without the guard, and a report of the same bug lost
 * its leading characters the same way. Tapping the clip from the clipboard
 * panel is unaffected, because a resume is blocked while a panel owns the
 * screen.
 *
 * Vietnamese is where it bites because Telex can spell a word read out of the
 * field back into the keys that compose it ([Composer.resumeBuffer]), so the
 * region really is taken; layouts that cannot resume never got that far.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardCodePasteTest {

    private val textField = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }

    /**
     * The field, with the echoes a real editor sends for every edit the
     * keyboard makes — the one thing [RecordingEditor] does not send on its
     * own, and the whole of this bug.
     *
     * Digits arrive as key events ([commitTypedCharacter]) and the harness
     * swallows those, so this puts the digit in the way a real editor does:
     * inserted at the caret.
     */
    private class EchoingEditor(target: View) : RecordingEditor(target) {
        var service: WMKeyboardService? = null

        /** The regions the service asked this editor to arm, in order. */
        val armed = mutableListOf<String>()

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            armed += "[$start,$end)"
            return super.setComposingRegion(start, end)
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val committed = super.commitText(text, newCursorPosition)
            echo()
            return committed
        }

        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            val digit = event?.let { it.keyCode - KeyEvent.KEYCODE_0 }
            if (event?.action == KeyEvent.ACTION_DOWN && digit != null && digit in 0..9) {
                this.text.append(('0' + digit))
            }
            val sent = super.sendKeyEvent(event)
            echo()
            return sent
        }

        private fun echo() {
            val at = text.length
            val end = at
            Handler(Looper.getMainLooper()).post { service?.onUpdateSelection(at, end, at, end, -1, -1) }
        }
    }

    private class ViKeyboard(
        private val editor: InputConnection,
        private val info: EditorInfo,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = info
    }

    /**
     * A Vietnamese Telex field over [editor], with word sources loaded — the
     * dictionary is what lets a word be read back out of the field at all
     * (`composingResumable` wants `hasWordSources`).
     */
    private fun keyboardOn(editor: RecordingEditor): ViKeyboard {
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
            ).copy(
                composer = VietnameseTelexComposer,
                language = LanguageRegistry.byId("vi"),
            ),
        )
        return service
    }

    /** The field's own timeline: the run commits over 40 ms gaps. */
    private fun pump(times: Int = 60) {
        repeat(times) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)) }
    }

    private fun pasteChip(service: WMKeyboardService, code: String) {
        val store = ClipboardStore(null)
        val field = WMKeyboardService::class.java.getDeclaredField("clipboardStore")
        field.isAccessible = true
        field.set(service, store)
        service.onClipboardItemTapped(store.add(code)!!)
    }

    /**
     * Past the caret-drag window before the paste starts. It reads
     * `uptimeMillis`, which starts near zero here, so the first half-second of
     * test time would otherwise look like a finger still dragging the caret —
     * and a drag suppresses the resume, leaving these tests green whether the
     * bug is there or not.
     */
    private fun settleTheClock() = ShadowSystemClock.advanceBy(Duration.ofSeconds(30))

    @Test
    fun `a code pasted one character at a time keeps every character`() {
        val editor = EchoingEditor(View(RuntimeEnvironment.getApplication()))
        val service = keyboardOn(editor)
        editor.service = service
        settleTheClock()

        pasteChip(service, "KP4T9QMR")
        pump()

        assertEquals("KP4T9QMR", editor.text.toString())
    }

    @Test
    fun `no composing region is armed while the code is still being typed`() {
        val editor = EchoingEditor(View(RuntimeEnvironment.getApplication()))
        val service = keyboardOn(editor)
        editor.service = service
        settleTheClock()

        pasteChip(service, "KP4T9QMR")
        pump()

        // Every arm here would be over text the run has not finished writing,
        // and the run's own next commit is what replaces it. Nothing to arm
        // over is the state this ends in, so any region at all is the bug.
        assertEquals("armed a region mid-paste", emptyList<String>(), editor.armed)
    }
}
