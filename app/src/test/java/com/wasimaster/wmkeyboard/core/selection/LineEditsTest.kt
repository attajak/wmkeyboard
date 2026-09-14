package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineEditsTest {

    @Test
    fun `one line is never edited`() {
        assertNull(LineEdits.sort("hello"))
        assertNull(LineEdits.dedupe("hello"))
        assertNull(LineEdits.number("hello"))
        assertNull(LineEdits.bullet("hello"))
    }

    @Test
    fun `sort is ascending, then descending when already ascending`() {
        assertEquals("a\nb\nc", LineEdits.sort("b\na\nc"))
        assertEquals("c\nb\na", LineEdits.sort("a\nb\nc"))
        assertEquals("a\nb\nc", LineEdits.sort("c\nb\na"))
    }

    @Test
    fun `sort is natural and case-insensitive`() {
        assertEquals("item2\nitem9\nitem10", LineEdits.sort("item10\nitem9\nitem2"))
        assertEquals("a\nB\nc", LineEdits.sort("B\na\nc"))
    }

    @Test
    fun `dedupe keeps the first of a repeat and every blank line`() {
        assertEquals("a\nb\n\n\nc", LineEdits.dedupe("a\nb\na\n\n\nc"))
        assertNull(LineEdits.dedupe("a\nb\nc"))
    }

    @Test
    fun `number and bullet toggle, convert into each other and renumber`() {
        assertEquals("1. a\n2. b", LineEdits.number("a\nb"))
        assertEquals("a\nb", LineEdits.number("1. a\n2. b"))
        assertEquals("1. a\n2. b", LineEdits.number("1. a\nb"))
        assertEquals("1. a\n2. b", LineEdits.number("• a\n• b"))
        assertEquals("• a\n• b", LineEdits.bullet("1. a\n2. b"))
        assertEquals("a\nb", LineEdits.bullet("• a\n• b"))
        // Dashes and stars are bullets already, so the tap takes them off.
        assertEquals("a\nb", LineEdits.bullet("- a\n* b"))
        assertEquals("1. a\n2. b", LineEdits.number("- a\n* b"))
    }

    @Test
    fun `blank lines, indentation and line endings survive`() {
        assertEquals("• a\n\n• b", LineEdits.bullet("a\n\nb"))
        assertEquals("  1. a\n2. b", LineEdits.number("  a\nb"))
        assertEquals("1. a\r\n2. b", LineEdits.number("a\r\nb"))
        assertEquals("a\nb\n", LineEdits.sort("b\na\n"))
    }
}
