package com.wasimaster.wmkeyboard.ime

import android.text.Editable
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A hyphen typed after a letter joins the word being composed, so the strip
 * completes the compound ("well-pai" to *well-paid*) instead of starting over
 * on the part after it — and a hyphen the word ends on still lands as typed.
 *
 * The editor is `BaseInputConnection` over a real `Editable`, so the composing
 * region arithmetic is AOSP's own.
 */
@RunWith(RobolectricTestRunner::class)
class HyphenCompoundTypingTest {

    private class FieldEditor : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        private val content: Editable = Editable.Factory.getInstance().newEditable("")
            .also { Selection.setSelection(it, 0) }
        override fun getEditable(): Editable = content
        val text: String get() = content.toString()
    }

    private fun typing(): Pair<FieldEditor, GlideKeyboard> {
        val editor = FieldEditor()
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        // Composing needs the strip on; learning stays off (see glideReadyState).
        seedState(service, glideReadyState(fieldNoSuggestions = false))
        return editor to service
    }

    private fun GlideKeyboard.type(text: String) = text.forEach { onText(it.toString()) }

    private val space = Key(label = " ", action = KeyAction.Space)

    @Test
    fun `a hyphen after a letter keeps the word composing`() {
        val (editor, service) = typing()
        service.type("Well-pai")
        assertEquals("Well-pai", editor.text)
        assertEquals("Well-pai", service.uiState.value.composingPreview)
    }

    @Test
    fun `a pick replaces the whole compound`() {
        val (editor, service) = typing()
        service.type("Well-pai")
        service.onSuggestionTapped("Well-paid")
        assertEquals("Well-paid ", editor.text)
    }

    @Test
    fun `a hyphen the word ends on commits after it`() {
        val (editor, service) = typing()
        service.type("well-")
        service.onKey(space)
        assertEquals("well- ", editor.text)
        assertEquals("", service.uiState.value.composingPreview)
    }

    @Test
    fun `a second hyphen is a dash and ends the word`() {
        val (editor, service) = typing()
        service.type("well--")
        assertEquals("well--", editor.text)
        assertEquals("", service.uiState.value.composingPreview)
    }

    @Test
    fun `a hyphen with no word in front of it is only a hyphen`() {
        val (editor, service) = typing()
        service.type("-a")
        assertEquals("-a", editor.text)
        assertEquals("a", service.uiState.value.composingPreview)
    }
}
