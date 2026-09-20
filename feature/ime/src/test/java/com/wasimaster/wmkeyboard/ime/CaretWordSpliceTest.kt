package com.wasimaster.wmkeyboard.ime

import android.text.Editable
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A strip pick over the word the caret is parked inside replaces that word
 * whole, even when the field is holding a composing region the keyboard knows
 * nothing about (#267).
 *
 * The splice is `deleteSurroundingText(head, tail)` then `commitText`, and
 * neither of those is measured from the caret while a region exists:
 * `deleteSurroundingText` widens the span it counts from to swallow the
 * region, and `commitText` replaces the region instead of the selection (both
 * in `BaseInputConnection`, and that is the behaviour every editor inherits).
 * A region left over somewhere else in the line therefore took the delete with
 * it and landed the new word on top of the wrong text — the report's "half of
 * the new word and half of the old word", sticky until the keyboard closed
 * because nothing else in a proofreading session finishes a composition.
 *
 * The editor here is `BaseInputConnection` over a real `Editable`, so the text
 * arithmetic under test is AOSP's own and not a model of it.
 */
@RunWith(RobolectricTestRunner::class)
class CaretWordSpliceTest {

    private class FieldEditor(initial: String, caret: Int) :
        BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        private val content: Editable = Editable.Factory.getInstance().newEditable(initial)
            .also { Selection.setSelection(it, caret) }
        override fun getEditable(): Editable = content
        val text: String get() = content.toString()
    }

    /** "the head|ing here": the caret inside a swiped word, being proofread. */
    private fun proofreading(initial: String = "the heading here"): Pair<FieldEditor, GlideKeyboard> {
        val editor = FieldEditor(initial, CARET)
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        seedState(service, glideReadyState())
        service.parkCaretIn("head", "ing", WORD_START, CARET)
        return editor to service
    }

    /**
     * The private mid-word state the caret's own settle builds, planted by
     * hand: nothing in this source set can move a caret.
     */
    private fun WMKeyboardService.parkCaretIn(head: String, tail: String, start: Int, caret: Int) {
        val type = Class.forName("com.wasimaster.wmkeyboard.ime.WMKeyboardService\$CaretWord")
        val ctor = type.getDeclaredConstructor(
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        ctor.isAccessible = true
        WMKeyboardService::class.java.getDeclaredField("caretWord").apply { isAccessible = true }
            .set(this, ctor.newInstance(head, tail, start))
        WMKeyboardService::class.java.getDeclaredField("expectedSelStart")
            .apply { isAccessible = true }.setInt(this, caret)
        WMKeyboardService::class.java.getDeclaredField("expectedSelEnd")
            .apply { isAccessible = true }.setInt(this, caret)
    }

    @Test
    fun `a pick replaces the word the caret is in`() {
        val (editor, service) = proofreading()
        service.onSuggestionTapped("header")
        assertEquals("the header here", editor.text)
    }

    @Test
    fun `a region left over the word itself does not split the replacement`() {
        val (editor, service) = proofreading()
        editor.setComposingRegion(WORD_START, WORD_START + "heading".length)
        service.onSuggestionTapped("header")
        assertEquals("the header here", editor.text)
    }

    @Test
    fun `a region left over an earlier word does not move the replacement`() {
        val (editor, service) = proofreading()
        editor.setComposingRegion(0, 3)
        service.onSuggestionTapped("header")
        assertEquals("the header here", editor.text)
    }

    @Test
    fun `a region left over a later word does not move the replacement`() {
        val (editor, service) = proofreading()
        editor.setComposingRegion(12, 16)
        service.onSuggestionTapped("header")
        assertEquals("the header here", editor.text)
    }

    /** Where "heading" starts in "the heading here". */
    private companion object {
        const val WORD_START = 4

        /** Inside it, after "head". */
        const val CARET = 8
    }
}
