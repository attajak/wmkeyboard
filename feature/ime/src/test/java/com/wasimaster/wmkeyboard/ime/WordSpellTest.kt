package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spelling bar's caret and selection (#204), as plain edits on a draft.
 * The service tests in `WordCardEditTest` are about which keys reach it;
 * these are about what each edit does once they have.
 */
class WordSpellTest {

    private fun spell(draft: String, cursor: Int = draft.length, anchor: Int = cursor) =
        WordSpell(word = draft, draft = draft, cursor = cursor, anchor = anchor)

    @Test
    fun `a new draft starts with the caret at its end`() {
        val s = WordSpell(word = "teh", draft = "teh")
        assertEquals(3, s.cursor)
        assertFalse(s.hasSelection)
    }

    @Test
    fun `typing goes in at the caret`() {
        val s = spell("teh", cursor = 2).typed("x", 32)
        assertEquals("texh", s.draft)
        assertEquals(3, s.cursor)
    }

    @Test
    fun `typing replaces the selection`() {
        val s = spell("teh", cursor = 3, anchor = 1).typed("he", 32)
        assertEquals("the", s.draft)
        assertEquals(3, s.cursor)
        assertFalse(s.hasSelection)
    }

    @Test
    fun `a full draft cuts what is typed, not what is there`() {
        val s = spell("abcd", cursor = 2).typed("xyz", 5)
        assertEquals("abxcd", s.draft)
    }

    @Test
    fun `backspace takes the character before the caret, a whole emoji included`() {
        assertEquals("th", spell("teh", cursor = 2).deletedBackward().draft)
        val emoji = "a😀b"
        val s = spell(emoji, cursor = 3).deletedBackward()
        assertEquals("ab", s.draft)
        assertEquals(1, s.cursor)
    }

    @Test
    fun `backspace at the start does nothing`() {
        val s = spell("teh", cursor = 0)
        assertEquals(s, s.deletedBackward())
    }

    @Test
    fun `forward delete takes the character after the caret`() {
        val s = spell("teh", cursor = 1).deletedForward()
        assertEquals("th", s.draft)
        assertEquals(1, s.cursor)
    }

    @Test
    fun `a caret move stops at either end`() {
        assertEquals(0, spell("ab", cursor = 0).caretMoved(-1, extend = false).cursor)
        assertEquals(2, spell("ab").caretMoved(1, extend = false).cursor)
    }

    @Test
    fun `an extending move grows the selection from its anchor`() {
        val s = spell("teh").caretMoved(-1, extend = true).caretMoved(-1, extend = true)
        assertEquals(1, s.selectionStart)
        assertEquals(3, s.selectionEnd)
        assertEquals(3, s.anchor)
    }

    @Test
    fun `a plain move collapses a selection to the side it points at`() {
        val left = spell("hello", cursor = 4, anchor = 1).caretMoved(-1, extend = false)
        assertEquals(1, left.cursor)
        assertFalse(left.hasSelection)
        val right = spell("hello", cursor = 1, anchor = 4).caretMoved(1, extend = false)
        assertEquals(4, right.cursor)
    }

    @Test
    fun `re-casing keeps the selection so the next press takes the next step`() {
        val title = spell("teh", cursor = 3, anchor = 0).recased { it.replaceFirstChar(Char::uppercaseChar) }
        assertEquals("Teh", title?.draft)
        assertTrue(title?.hasSelection == true)
        assertEquals(0, title?.selectionStart)
        assertEquals(3, title?.selectionEnd)
    }

    @Test
    fun `re-casing needs a selection and a change`() {
        assertNull(spell("teh").recased { it.uppercase() })
        assertNull(spell("১২৩", cursor = 3, anchor = 0).recased { it.uppercase() })
    }

    @Test
    fun `a touch selection is clamped to the draft`() {
        val s = spell("teh").selected(-4, 99)
        assertEquals(0, s.selectionStart)
        assertEquals(3, s.selectionEnd)
    }
}
