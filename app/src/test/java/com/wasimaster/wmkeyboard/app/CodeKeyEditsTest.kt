package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The edits behind the code editors' keys, as plain functions of a string and a selection. */
class CodeKeyEditsTest {

    // ---- Tab ----------------------------------------------------------------

    @Test
    fun `Tab types spaces at a caret or over part of a line, and moves whole lines in`() {
        val text = "ab\ncd"
        assertFalse(tabShiftsLines(text, TextRange(1)))
        assertFalse(tabShiftsLines(text, TextRange(0, 1)))
        assertTrue(tabShiftsLines(text, TextRange(0, 2)))
        assertTrue(tabShiftsLines(text, TextRange(1, 4)))
    }

    @Test
    fun `typed indentation reaches the next stop`() {
        assertEquals("a b", insertIndent("ab", TextRange(1)).applyTo("ab"))
        assertEquals(TextRange(2), insertIndent("ab", TextRange(1)).selection)
        assertEquals("  ab", insertIndent("ab", TextRange(0)).applyTo("ab"))
        assertEquals("x\n    y", insertIndent("x\n  y", TextRange(4)).applyTo("x\n  y"))
        assertEquals("  ", insertIndent("ab", TextRange(0, 2)).applyTo("ab"))
    }

    // ---- lines --------------------------------------------------------------

    @Test
    fun `deleting a line keeps the caret's column on the line that moves up`() {
        val text = "a\nbb\nc"
        val edit = deleteLines(text, TextRange(3))
        assertEquals("a\nc", edit.applyTo(text))
        assertEquals(TextRange(3), edit.selection)
    }

    @Test
    fun `deleting the last line takes the break before it`() {
        val text = "aaa\nbb"
        val edit = deleteLines(text, TextRange(6))
        assertEquals("aaa", edit.applyTo(text))
        assertEquals(TextRange(2), edit.selection)
        assertEquals("", deleteLines("abc", TextRange(1)).applyTo("abc"))
    }

    @Test
    fun `a new line below or above takes the indent of the caret's line`() {
        val text = "  a\nb"
        val below = insertLine(text, TextRange(1), above = false)
        assertEquals("  a\n  \nb", below.applyTo(text))
        assertEquals(TextRange(6), below.selection)
        val above = insertLine(text, TextRange(1), above = true)
        assertEquals("  \n  a\nb", above.applyTo(text))
        assertEquals(TextRange(2), above.selection)
    }

    @Test
    fun `copying lines up leaves the caret on the upper copy, down on the lower`() {
        val text = "a\nb"
        val up = copyLines(text, TextRange(0), up = true)
        assertEquals("a\na\nb", up.applyTo(text))
        assertEquals(TextRange(0), up.selection)
        val down = copyLines(text, TextRange(0), up = false)
        assertEquals("a\na\nb", down.applyTo(text))
        assertEquals(TextRange(2), down.selection)
    }

    @Test
    fun `Ctrl+L selects the line with its break, then the next line too`() {
        val text = "a\nb\nc"
        val once = expandLineSelection(text, TextRange(2))
        assertEquals(TextRange(2, 4), once)
        val twice = expandLineSelection(text, once)
        assertEquals(TextRange(2, 5), twice)
        assertEquals(twice, expandLineSelection(text, twice))
    }

    @Test
    fun `Home goes to the first written character, then to the start of the line`() {
        val text = "x\n  ab"
        assertEquals(4, smartHome(text, 6))
        assertEquals(2, smartHome(text, 4))
        assertEquals(4, smartHome(text, 2))
        assertEquals(4, smartHome(text, 3))
    }

    @Test
    fun `a line copied with nothing selected ends in a break and pastes in above the caret's line`() {
        val text = "ab\ncd"
        assertEquals("cd\n", lineText(text, 4))
        assertEquals("ab\n", lineText(text, 0))
        val paste = pasteLines(text, 4, "x\n")
        assertEquals("ab\nx\ncd", paste.applyTo(text))
        assertEquals(TextRange(6), paste.selection)
    }

    // ---- comments -----------------------------------------------------------

    @Test
    fun `a block comment goes round the selection and comes off again`() {
        val text = "x = 1"
        val on = toggleBlockComment(text, TextRange(4, 5), "--[[", "]]")
        val commented = on.applyTo(text)
        assertEquals("x = --[[ 1 ]]", commented)
        assertEquals(TextRange(9, 10), on.selection)
        val off = toggleBlockComment(commented, on.selection, "--[[", "]]")
        assertEquals("x = 1", off.applyTo(commented))
        assertEquals(TextRange(4, 5), off.selection)
        val whole = toggleBlockComment(commented, TextRange(4, 13), "--[[", "]]")
        assertEquals("x = 1", whole.applyTo(commented))
        assertEquals(TextRange(4, 5), whole.selection)
    }

    @Test
    fun `a block comment at a caret leaves the caret inside it, and a second press takes it off`() {
        val on = toggleBlockComment("ab", TextRange(1), "--[[", "]]")
        val commented = on.applyTo("ab")
        assertEquals("a--[[  ]]b", commented)
        assertEquals(TextRange(6), on.selection)
        assertEquals("ab", toggleBlockComment(commented, on.selection, "--[[", "]]").applyTo(commented))
    }

    // ---- selection, problems, brackets and folds ----------------------------

    @Test
    fun `Shift+Alt+Right grows from the word to the line, the block and the document`() {
        val text = "x()\nif a then\n  b = cc\nend"
        val block = TextRange(4, text.length)
        var selection = TextRange(20)
        val steps = listOf(TextRange(20, 22), TextRange(16, 22), TextRange(14, 22), block, TextRange(0, text.length))
        for (step in steps) {
            selection = requireNotNull(expandSelection(text, selection, listOf(block)))
            assertEquals(step, selection)
        }
        assertNull(expandSelection(text, selection, listOf(block)))
    }

    @Test
    fun `F8 walks the problems in order and goes round`() {
        val problems = listOf(TextRange(30, 32), TextRange(5, 6), TextRange(12, 14))
        assertEquals(TextRange(12, 14), nextProblem(problems, 5, forward = true))
        assertEquals(TextRange(5, 6), nextProblem(problems, 30, forward = true))
        assertEquals(TextRange(12, 14), nextProblem(problems, 30, forward = false))
        assertEquals(TextRange(30, 32), nextProblem(problems, 5, forward = false))
        assertNull(nextProblem(emptyList(), 0, forward = true))
    }

    @Test
    fun `Ctrl+Shift+backslash goes between a pair, and out to the bracket that closes the caret in`() {
        val text = """{"a":[1,2]}"""
        val brackets = JsonCode.brackets(text)
        val pair = { at: Int -> JsonCode.matchingBracket(text, brackets, at) }
        assertEquals(11, bracketJump(text, brackets, 1, pair))
        assertEquals(1, bracketJump(text, brackets, 11, pair))
        assertEquals(10, bracketJump(text, brackets, 7, pair))
        assertEquals(11, bracketJump(text, brackets, 3, pair))
        assertNull(bracketJump("abc", emptyList(), 1) { null })
    }

    @Test
    fun `Ctrl+Shift+brackets fold the innermost open block at the caret and open a folded one`() {
        val text = "if a then\n  if b then\n    c()\n  end\nend"
        val outer = TextRange(0, text.length)
        val inner = TextRange(12, 35)
        val regions = listOf(outer, inner)
        assertEquals(inner, foldToClose(text, regions, emptySet(), 26))
        assertEquals(outer, foldToClose(text, regions, setOf(12), 26))
        assertEquals(outer, foldToClose(text, regions, emptySet(), 2))
        assertEquals(12, foldToOpen(text, regions, setOf(0, 12), 14))
        assertNull(foldToOpen(text, regions, emptySet(), 14))
    }
}
