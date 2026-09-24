package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard's own fields hold a selection as well as a caret (#352): a long
 * press selects a word, handles stretch it, and every edit replaces it first,
 * the way a real field does.
 */
class CaptureSelectionTest {

    private val hello = CaretText("hello world", caret = 11, anchor = 6)

    @Test
    fun `a selection reads the same whichever end is the caret`() {
        val backwards = CaretText("hello world", caret = 6, anchor = 11)
        assertEquals(6, backwards.selectionStart)
        assertEquals(11, backwards.selectionEnd)
        assertEquals("world", backwards.selectedText)
        assertEquals("world", hello.selectedText)
    }

    @Test
    fun `typing replaces the selection`() {
        assertEquals(CaretText("hello there", 11), hello.typed("there"))
    }

    @Test
    fun `backspace and delete take the selection and nothing else`() {
        assertEquals(CaretText("hello ", 6), hello.deletedBackward())
        assertEquals(CaretText("hello ", 6), hello.deletedForward())
    }

    @Test
    fun `an arrow collapses the selection to the end it points at`() {
        assertEquals(CaretText("hello world", 6), hello.caretMoved(-1))
        assertEquals(CaretText("hello world", 11), hello.caretMoved(1))
    }

    @Test
    fun `shift and an arrow grow the selection from the anchor`() {
        val caret = CaretText("hello world", caret = 5)
        val grown = caret.caretMoved(-2, extend = true)
        assertEquals("lo", grown.selectedText)
        assertEquals(5, grown.anchorAt)
    }

    @Test
    fun `a tap collapses the selection`() {
        assertFalse(hello.caretAt(2).hasSelection)
    }

    @Test
    fun `a strip pick and a glide replace the selection`() {
        assertEquals(CaretText("hello there ", 12), hello.replacedWordAtCaret("there", spaceAfter = true))
        assertEquals("hello there", hello.glided("there").text)
    }

    @Test
    fun `cut leaves the caret where the selection began`() {
        assertEquals(CaretText("hello ", 6), hello.withoutSelection())
    }

    @Test
    fun `select all takes the whole buffer`() {
        val all = CaretText("hello world", caret = 3).selectedAll()
        assertEquals("hello world", all.selectedText)
    }

    @Test
    fun `a long press selects the word under it`() {
        val picked = CaretText("hello world", caret = 0).selectedWordAt(8)
        assertEquals("world", picked.selectedText)
        // Just past the last letter still means that word.
        assertEquals("hello", CaretText("hello world", 0).selectedWordAt(5).selectedText)
    }

    @Test
    fun `a long press on no word only moves the caret`() {
        val spaced = CaretText("a  b", caret = 0).selectedWordAt(2)
        assertFalse(spaced.hasSelection)
        assertEquals(2, spaced.at)
    }

    @Test
    fun `a word span keeps apostrophes inside and drops them at the edges`() {
        assertEquals(1..5, CaretText.wordSpanAt("'don't'", 3))
        assertNull(CaretText.wordSpanAt("", 0))
        assertNull(CaretText.wordSpanAt(" - ", 1))
    }

    @Test
    fun `the anchor is clamped like the caret`() {
        val stale = CaretText("hi", caret = 1, anchor = 40)
        assertEquals(2, stale.anchorAt)
        assertTrue(stale.hasSelection)
        assertEquals("i", stale.selectedText)
    }

    @Test
    fun `a stored caret brings its anchor back only for the same text`() {
        val state = KeyboardUiState(
            dictionarySearchActive = true,
            dictionaryQuery = "hello world",
            captureCaret = CaptureCaret("DICTIONARY_SEARCH", at = 11, text = "hello world", anchor = 6),
        )
        assertEquals("world", state.captureCaretText()?.selectedText)
        assertEquals(6, state.captureAnchorIndex())
        val changed = state.copy(dictionaryQuery = "hello")
        assertFalse(changed.captureCaretText()!!.hasSelection)
    }
}
