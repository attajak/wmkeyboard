package com.wasimaster.wmkeyboard.ime

import android.content.res.Resources
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import com.wasimaster.wmkeyboard.core.selection.SelectionKind
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SelectionMacroSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The selection bar's rewrite and its Undo, against a field that models a
 * range selection.
 *
 * [RecordingEditor] keeps its caret at the end, which is right for a glide and
 * useless here: a rewrite replaces a *range* and reselects it. So the editor
 * below overrides the six calls the rewrite makes, and the assertions read the
 * field first, the keyboard's state second, for the reason the harness gives.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionMacroUndoServiceTest {

    private class SelectionEditor(initial: String, var selStart: Int, var selEnd: Int) : RecordingEditor(initial = initial) {
        override fun getSelectedText(flags: Int): CharSequence? =
            if (selStart == selEnd) null else text.substring(selStart, selEnd)

        override fun setSelection(start: Int, end: Int): Boolean {
            selStart = start.coerceIn(0, text.length)
            selEnd = end.coerceIn(0, text.length)
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val committed = text?.toString().orEmpty()
            commits += committed
            this.text.replace(selStart, selEnd, committed)
            selStart += committed.length
            selEnd = selStart
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = text.substring(0, selStart).takeLast(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = text.substring(selEnd).take(n)

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().also {
            it.text = text.toString()
            it.startOffset = 0
            it.selectionStart = selStart
            it.selectionEnd = selEnd
        }

        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun finishComposingText(): Boolean = true
    }

    /** A service over "hello world" with "hello" selected and the bar offered for it. */
    private fun keyboard(): Pair<WMKeyboardService, SelectionEditor> {
        val editor = SelectionEditor("hello world", 0, 5)
        val service = GlideKeyboard(editor)
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    haptics = HapticSettings(enabled = false),
                    selectionMacros = SelectionMacroSettings(enabled = true),
                ),
            ).copy(selectionMacros = SelectionMacroOffer("hello", SelectionKind.TEXT, listOf(SelectionMacro.FORMAT))),
        )
        for ((name, value) in listOf("expectedSelStart" to 0, "expectedSelEnd" to 5)) {
            val field = WMKeyboardService::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.setInt(service, value)
        }
        return service to editor
    }

    @Test
    fun `a rewrite reselects its result, and undo puts the original back`() {
        val (service, editor) = keyboard()

        service.onSelectionMacro(SelectionMacro.CASE_UPPER)

        assertEquals("HELLO world", editor.text.toString())
        assertEquals(0 to 5, editor.selStart to editor.selEnd)
        val offer = service.uiState.value.selectionMacros!!
        assertEquals("HELLO", offer.text)
        assertEquals(1, offer.undoDepth)
        assertEquals(SelectionMacro.UNDO, offer.macros.first())

        service.onSelectionMacro(SelectionMacro.UNDO)

        assertEquals("hello world", editor.text.toString())
        assertEquals(0 to 5, editor.selStart to editor.selEnd)
        assertEquals(0, service.uiState.value.selectionMacros!!.undoDepth)
    }

    @Test
    fun `undo refuses a field that changed underneath it`() {
        val (service, editor) = keyboard()
        service.onSelectionMacro(SelectionMacro.CASE_UPPER)
        editor.text.replace(0, 5, "HOWDY")

        // The refusal ends in a toast, and this module's tests run without
        // Android resources, so that one lookup throws after the state has
        // been settled. Everything below is about that state.
        runCatching { service.onSelectionMacro(SelectionMacro.UNDO) }
            .exceptionOrNull()?.let { if (it !is Resources.NotFoundException) throw it }

        assertEquals("HOWDY world", editor.text.toString())
        assertEquals(0, service.uiState.value.selectionMacros!!.undoDepth)
    }
}
