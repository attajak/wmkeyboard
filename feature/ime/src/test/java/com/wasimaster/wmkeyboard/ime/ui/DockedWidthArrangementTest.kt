package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.KeyboardAlignment
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a narrowed docked board sits. The alignment argument is how a
 * television field's `horizontalAlignment=` hint beats the stored setting, so
 * it has to win outright rather than blend with it.
 */
class DockedWidthArrangementTest {

    private val half = KeyboardSettings(
        keyboardWidthPercent = 50,
        keyboardAlignment = KeyboardAlignment.CENTER,
    )

    @Test
    fun `the stored alignment places the board when nothing overrides it`() {
        val arrangement = dockedWidthArrangement(half)
        assertEquals(0.5f, arrangement.widthFraction, EPS)
        assertEquals(0.25f, arrangement.leftSlack, EPS)
        assertEquals(0.25f, arrangement.rightSlack, EPS)
    }

    @Test
    fun `a field's alignment beats the stored one`() {
        val right = dockedWidthArrangement(half, KeyboardAlignment.RIGHT)
        assertEquals(0.5f, right.leftSlack, EPS)
        assertEquals(0f, right.rightSlack, EPS)
        val left = dockedWidthArrangement(half, KeyboardAlignment.LEFT)
        assertEquals(0f, left.leftSlack, EPS)
        assertEquals(0.5f, left.rightSlack, EPS)
    }

    @Test
    fun `a full-width board has nowhere to move`() {
        val full = dockedWidthArrangement(half.copy(keyboardWidthPercent = 100), KeyboardAlignment.RIGHT)
        assertEquals(1f, full.widthFraction, EPS)
        assertEquals(0f, full.leftSlack, EPS)
        assertEquals(0f, full.rightSlack, EPS)
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
