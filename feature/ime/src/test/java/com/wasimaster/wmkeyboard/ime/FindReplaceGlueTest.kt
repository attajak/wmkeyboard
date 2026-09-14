package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.selection.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Test

class FindReplaceGlueTest {

    private val matches = listOf(0..2, 10..12, 20..22)

    @Test
    fun `the current match is the one the selection covers exactly`() {
        assertEquals(1, FindReplaceGlue.currentMatchIndex(matches, 10, 13))
        assertEquals(-1, FindReplaceGlue.currentMatchIndex(matches, 10, 12))
        assertEquals(-1, FindReplaceGlue.currentMatchIndex(emptyList(), 0, 3))
    }

    @Test
    fun `stepping wraps, and starts from the selection when nothing is current`() {
        assertEquals(2, FindReplaceGlue.stepIndex(matches, 1, 0, +1))
        assertEquals(0, FindReplaceGlue.stepIndex(matches, 2, 0, +1))
        assertEquals(2, FindReplaceGlue.stepIndex(matches, 0, 0, -1))
        assertEquals(1, FindReplaceGlue.stepIndex(matches, -1, 5, +1))
        assertEquals(0, FindReplaceGlue.stepIndex(matches, -1, 5, -1))
        assertEquals(0, FindReplaceGlue.stepIndex(matches, -1, 25, +1))
        assertEquals(2, FindReplaceGlue.stepIndex(matches, -1, 0, -1))
        assertEquals(-1, FindReplaceGlue.stepIndex(emptyList(), -1, 0, +1))
    }

    @Test
    fun `replacing one match drops it and shifts the later ones`() {
        assertEquals(listOf(0..2, 22..24), FindReplaceGlue.shiftMatches(matches, 1, +2))
        assertEquals(listOf(10..12, 20..22), FindReplaceGlue.shiftMatches(matches, 0, -3).map { (it.first + 3)..(it.last + 3) })
        assertEquals(matches, FindReplaceGlue.shiftMatches(matches, 7, 1))
    }

    @Test
    fun `an offset moves by the edits before it`() {
        val edits = listOf(TextEdit(20, 23, "x"), TextEdit(0, 3, "hello"))
        assertEquals(12, FindReplaceGlue.afterOffset(edits, 10))
        assertEquals(0, FindReplaceGlue.afterOffset(edits, 0))
        assertEquals(30 + 2 - 2, FindReplaceGlue.afterOffset(edits, 30))
    }
}
