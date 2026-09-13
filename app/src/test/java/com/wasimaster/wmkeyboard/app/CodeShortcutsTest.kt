package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The key table the code editors share, checked against VS Code's own defaults. */
class CodeShortcutsTest {

    private fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        codeCommandFor(key, ctrl, shift, alt)

    @Test
    fun `no key press runs two commands`() {
        val clashes = CodeBindings.groupBy { listOf(it.key, it.ctrl, it.shift, it.alt) }.filterValues { it.size > 1 }
        assertTrue(clashes.values.joinToString { group -> group.joinToString("/") { "${it.label}=${it.command}" } }, clashes.isEmpty())
    }

    @Test
    fun `every command has a key`() {
        val bound = (CodeBindings + CodeChordBindings).map { it.command }.toSet()
        val missing = CodeCommand.entries.filter { it != CodeCommand.CHORD && it !in bound }
        assertTrue(missing.toString(), missing.isEmpty())
    }

    @Test
    fun `the keys are VS Code's`() {
        assertEquals(CodeCommand.TOGGLE_LINE_COMMENT, press(Key.Slash, ctrl = true))
        assertEquals(CodeCommand.TOGGLE_BLOCK_COMMENT, press(Key.A, shift = true, alt = true))
        assertEquals(CodeCommand.MOVE_LINES_UP, press(Key.DirectionUp, alt = true))
        assertEquals(CodeCommand.COPY_LINES_DOWN, press(Key.DirectionDown, shift = true, alt = true))
        assertEquals(CodeCommand.DELETE_LINES, press(Key.K, ctrl = true, shift = true))
        assertEquals(CodeCommand.INSERT_LINE_BELOW, press(Key.Enter, ctrl = true))
        assertEquals(CodeCommand.INSERT_LINE_ABOVE, press(Key.Enter, ctrl = true, shift = true))
        assertEquals(CodeCommand.SELECT_LINE, press(Key.L, ctrl = true))
        assertEquals(CodeCommand.JUMP_TO_BRACKET, press(Key.Backslash, ctrl = true, shift = true))
        assertEquals(CodeCommand.FOLD, press(Key.LeftBracket, ctrl = true, shift = true))
        assertEquals(CodeCommand.OUTDENT_LINES, press(Key.LeftBracket, ctrl = true))
        assertEquals(CodeCommand.FORMAT, press(Key.F, shift = true, alt = true))
        assertEquals(CodeCommand.TOGGLE_WRAP, press(Key.Z, alt = true))
        assertEquals(CodeCommand.FIND, press(Key.F, ctrl = true))
        assertEquals(CodeCommand.REPLACE, press(Key.H, ctrl = true))
        assertEquals(CodeCommand.GO_TO_LINE, press(Key.G, ctrl = true))
        assertEquals(CodeCommand.GO_TO_DEFINITION, press(Key.F12))
        assertEquals(CodeCommand.RENAME, press(Key.F2))
        assertEquals(CodeCommand.NEXT_PROBLEM, press(Key.F8))
        assertEquals(CodeCommand.PREVIOUS_PROBLEM, press(Key.F8, shift = true))
        assertEquals(CodeCommand.UNDO, press(Key.Z, ctrl = true))
        assertEquals(CodeCommand.REDO, press(Key.Z, ctrl = true, shift = true))
        assertEquals(CodeCommand.REDO, press(Key.Y, ctrl = true))
        assertEquals(CodeCommand.SUGGEST, press(Key.Spacebar, ctrl = true))
        assertEquals(CodeCommand.SUGGEST, press(Key.I, ctrl = true))
    }

    @Test
    fun `what the text field already does is left to it`() {
        assertNull(press(Key.A))
        assertNull(press(Key.A, ctrl = true))
        assertNull(press(Key.DirectionLeft))
        assertNull(press(Key.DirectionRight, shift = true))
        assertNull(press(Key.DirectionRight, ctrl = true))
        assertNull(press(Key.DirectionRight, ctrl = true, shift = true))
        assertNull(press(Key.Backspace, ctrl = true))
        assertNull(press(Key.MoveEnd))
        assertNull(press(Key.Enter))
    }

    @Test
    fun `Ctrl+K waits for the key that finishes the chord`() {
        val chords = CodeKeyChords()
        assertEquals(CodeCommand.CHORD, chords.map(Key.K, ctrl = true, shift = false, alt = false))
        // Letting go of Ctrl and pressing it again is not the second key.
        assertNull(chords.map(Key.CtrlLeft, ctrl = true, shift = false, alt = false))
        assertEquals(CodeCommand.FOLD_ALL, chords.map(Key.Zero, ctrl = true, shift = false, alt = false))
        // The chord is over, so the same press means what it means on its own.
        assertEquals(CodeCommand.ZOOM_RESET, chords.map(Key.Zero, ctrl = true, shift = false, alt = false))
        assertEquals(CodeCommand.CHORD, chords.map(Key.K, ctrl = true, shift = false, alt = false))
        assertEquals(CodeCommand.UNFOLD_ALL, chords.map(Key.J, ctrl = true, shift = false, alt = false))
    }

    @Test
    fun `a second key the chord does not know is swallowed, and the press after it is free`() {
        val chords = CodeKeyChords()
        chords.map(Key.K, ctrl = true, shift = false, alt = false)
        assertEquals(CodeCommand.CHORD, chords.map(Key.X, ctrl = false, shift = false, alt = false))
        assertEquals(CodeCommand.FIND, chords.map(Key.F, ctrl = true, shift = false, alt = false))
    }

    @Test
    fun `labels are written the way VS Code writes them`() {
        assertEquals("Ctrl+Shift+K", CodeCommand.DELETE_LINES.shortcutLabel())
        assertEquals("Shift+Alt+F", CodeCommand.FORMAT.shortcutLabel())
        assertEquals("Alt+Z", CodeCommand.TOGGLE_WRAP.shortcutLabel())
        assertEquals("Ctrl+F", CodeCommand.FIND.shortcutLabel())
        assertEquals("Ctrl+K Ctrl+0", CodeCommand.FOLD_ALL.shortcutLabel())
        assertNull(CodeCommand.CHORD.shortcutLabel())
    }
}
