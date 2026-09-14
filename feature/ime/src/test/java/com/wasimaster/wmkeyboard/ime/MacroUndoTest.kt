package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.selection.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroUndoTest {

    private fun entry(n: Int) = MacroUndoEntry.Selection("o$n", "r$n", 0)

    @Test
    fun `newest last, and the oldest falls off past the limit`() {
        val stack = MacroUndoStack(limit = 3)
        assertTrue(stack.isEmpty)
        assertNull(stack.pop())
        for (i in 1..4) stack.push(entry(i))
        assertEquals(3, stack.size)
        assertEquals(entry(4), stack.peek())
        assertEquals(entry(4), stack.pop())
        assertEquals(entry(3), stack.pop())
        assertEquals(entry(2), stack.pop())
        assertNull(stack.pop())
    }

    @Test
    fun `a field too large to hold is refused`() {
        val stack = MacroUndoStack()
        stack.push(MacroUndoEntry.WholeField("x".repeat(MacroUndoStack.MAX_FIELD_CHARS + 1), "", emptyList(), 0, 0))
        assertTrue(stack.isEmpty)
    }

    @Test
    fun `the live selection must read as the last rewrite`() {
        val e = MacroUndoEntry.Selection(" hello ", " HELLO ", 3)
        assertTrue(MacroUndo.stillHolds(e, " HELLO "))
        assertFalse(MacroUndo.stillHolds(e, "HELLO"))
        assertFalse(MacroUndo.stillHolds(e, null))
        assertTrue(MacroUndo.stillHolds(MacroUndoEntry.WholeField("a", "b", emptyList(), 0, 0), "anything"))
    }

    @Test
    fun `inverted edits round-trip, whatever the lengths`() {
        val before = "the cat and the dog and the end"
        val edits = listOf(TextEdit(24, 27, "THE"), TextEdit(12, 15, "a"), TextEdit(0, 3, "those"))
        val after = MacroUndo.apply(before, edits)
        assertEquals("those cat and a dog and THE end", after)
        val inverse = MacroUndo.invert(before, edits)
        assertEquals(before, MacroUndo.apply(after, inverse))
        // Back-to-front, like the originals.
        assertEquals(inverse.sortedByDescending { it.start }, inverse)
        assertEquals(emptyList<TextEdit>(), MacroUndo.invert(before, emptyList()))
        assertEquals(before, MacroUndo.apply(before, emptyList()))
    }
}
