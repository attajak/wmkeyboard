package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The caret magnifier's line (discussion #303): what the field hands back
 * around its selection, cut to the one line the bubble draws, with the caret
 * on the end of the selection that is moving.
 */
class CaretMagnifierTest {

    @Test
    fun `a collapsed caret sits between before and after`() {
        val line = magnifierLine("hello wo", "", "rld", focusAtEnd = true)
        assertEquals(MagnifierLine("hello world", caret = 8, selStart = 8, selEnd = 8), line)
    }

    @Test
    fun `the line is cut at the breaks on both sides`() {
        val line = magnifierLine("first\nsecond li", "", "ne\nthird", focusAtEnd = true)
        assertEquals(MagnifierLine("second line", caret = 9, selStart = 9, selEnd = 9), line)
    }

    @Test
    fun `a caret right after a break starts the line`() {
        val line = magnifierLine("above\n", "", "below", focusAtEnd = true)
        assertEquals(MagnifierLine("below", caret = 0, selStart = 0, selEnd = 0), line)
    }

    @Test
    fun `a selection growing forward puts the caret at its end`() {
        val line = magnifierLine("say ", "hello", " world", focusAtEnd = true)
        assertEquals(MagnifierLine("say hello world", caret = 9, selStart = 4, selEnd = 9), line)
    }

    @Test
    fun `a selection growing backward puts the caret at its start`() {
        val line = magnifierLine("say ", "hello", " world", focusAtEnd = false)
        assertEquals(MagnifierLine("say hello world", caret = 4, selStart = 4, selEnd = 9), line)
    }

    @Test
    fun `a selection across lines shows the moving end's line`() {
        val line = magnifierLine("one ", "two\nthree", " four", focusAtEnd = true)
        assertEquals(MagnifierLine("three four", caret = 5, selStart = 0, selEnd = 5), line)
    }

    @Test
    fun `a long selection keeps only the part next to the caret`() {
        val selected = "a".repeat(100) + "tail"
        val line = magnifierLine("start ", selected, " end", focusAtEnd = true, context = 10)
        assertEquals("aaaaaatail end", line.text)
        assertEquals(10, line.caret)
        assertEquals(0, line.selStart)
        assertEquals(10, line.selEnd)
    }

    @Test
    fun `the context cap never splits a surrogate pair`() {
        // Each 😀 is two chars: a cap of 3 from the end lands inside the first.
        val line = magnifierLine("😀😀", "", "", focusAtEnd = true, context = 3)
        assertEquals("😀", line.text)
        assertEquals(2, line.caret)
    }
}
