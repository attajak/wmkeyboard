package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.prediction.OctopusWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that made the tap on a floating word stop working (#209).
 *
 * [OctopusRects] stamps its rectangles with the bounds table they were measured
 * from and refuses to answer against any other, which is right: a layout change
 * hands the grid a fresh table, and rectangles measured against the old one
 * describe a board that has left the screen.
 *
 * What it means for the caller is the part worth pinning. The refusal is
 * *silent* — a null, indistinguishable from "no word here" — so a pointer loop
 * that captured the table instead of reading it live does not mis-hit, it stops
 * hitting at all, for as long as that loop lives. The keyboard's loops outlive
 * layout changes by design, so the table has to be read through a `State` at
 * the down; the flick, which reads the key centres and the live board, never
 * had the problem, which is why the report read as "only the flick works".
 */
class OctopusRectsTest {

    private val word = OctopusWord(
        keyCodePoint = 'w'.code,
        word = "world",
        typedChars = 0,
        kind = OctopusKind.NEXT_WORD,
        rank = 0,
    )

    private val slot = OctopusSlot(
        word = word,
        area = Rect(0f, 0f, 100f, 20f),
        hit = Rect(0f, 0f, 100f, 20f),
    )

    private val inside = Offset(50f, 10f)

    @Test
    fun `a word is found against the table it was measured from`() {
        val table = mutableMapOf('w'.code to Rect(0f, 0f, 100f, 100f))
        val rects = OctopusRects()
        rects.publish(listOf(slot), table)

        assertEquals(word, rects.wordAt(inside, table))
    }

    @Test
    fun `the same rectangles answer nothing against a table from another layout`() {
        val old = mutableMapOf('w'.code to Rect(0f, 0f, 100f, 100f))
        val fresh = mutableMapOf('w'.code to Rect(0f, 0f, 100f, 100f))
        val rects = OctopusRects()
        rects.publish(listOf(slot), fresh)

        // Equal by value, a different table by identity — which is exactly what
        // a pointer loop holds after the layout it started under is gone.
        assertEquals(old, fresh)
        assertNull(
            "a stale bounds table answered a hit test (#209)",
            rects.wordAt(inside, old),
        )
        // And the live one still does, so the fix is to read the table rather
        // than to loosen the stamp.
        assertEquals(word, rects.wordAt(inside, fresh))
    }
}
