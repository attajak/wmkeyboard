package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LayoutBehaviorSettings
import com.wasimaster.wmkeyboard.ime.FieldKind
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.isNumericPad
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #523: over a keypad the number row's slot carries symbols, not digits,
 * and the only way to be rid of it was to give up the digit row over the
 * letters too. These pin its own switch — and that the switch reaches nothing
 * else.
 */
class NumberRowOnKeypadTest {

    private fun grid() = KeyboardLayout(name = "test", rows = listOf(listOf(Key("a"))))

    private fun state(kind: FieldKind, onKeypad: Boolean) = KeyboardUiState(
        settings = KeyboardSettings(
            numberRow = true,
            layoutBehavior = LayoutBehaviorSettings(numberRowOnKeypad = onKeypad),
        ),
        layouts = LayoutSet(grid(), grid(), grid(), numeric = grid()),
        fieldKind = kind,
    )

    @Test
    fun `the symbol row goes from every keypad when the switch is off`() {
        for (kind in FieldKind.entries.filter { it.isNumericPad }) {
            assertTrue(kind.name, numberRowShown(state(kind, onKeypad = true)))
            assertFalse(kind.name, numberRowShown(state(kind, onKeypad = false)))
        }
    }

    @Test
    fun `the digit row over the letters is untouched either way`() {
        // The whole point of a switch of its own: a user who wants the keypad
        // bare still wants the digits above the letters.
        for (kind in FieldKind.entries.filterNot { it.isNumericPad }) {
            assertTrue(kind.name, numberRowShown(state(kind, onKeypad = true)))
            assertTrue(kind.name, numberRowShown(state(kind, onKeypad = false)))
        }
    }

    @Test
    fun `the switch cannot put a row back that the global one took away`() {
        val off = state(FieldKind.PHONE, onKeypad = true)
            .let { it.copy(settings = it.settings.copy(numberRow = false)) }
        assertFalse(numberRowShown(off))
    }
}
