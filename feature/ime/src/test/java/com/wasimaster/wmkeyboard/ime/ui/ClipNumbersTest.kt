package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The clipboard panel's numbers and the header its full-bleed form lifts out
 * of the layout: both are rules the user leans on to pick the right clip.
 */
class ClipNumbersTest {

    private fun clip(id: Long) = ClipItem(id = id, text = "clip $id", timestamp = id)

    @Test
    fun `numbers follow the panel's order from 1`() {
        val items = listOf(clip(9), clip(4), clip(6))
        assertEquals(mapOf(9L to 1, 4L to 2, 6L to 3), clipNumbers(items))
    }

    @Test
    fun `a search keeps each clip's own number`() {
        val all = listOf(clip(9), clip(4), clip(6))
        val numbers = clipNumbers(all)
        // The filter shows the third clip alone; it is still number 3.
        val shown = all.filter { it.id == 6L }
        assertEquals(listOf(3), shown.map { numbers.getValue(it.id) })
    }

    @Test
    fun `the shipped clipboard header lifts the search pill and the view switch`() {
        val strip = BuiltInPanelLayouts.CLIPBOARD.leadingStrip(
            setOf(PanelFieldKind.CLIPBOARD_SEARCH, PanelFieldKind.CLIPBOARD_VIEW),
        )!!
        assertEquals(
            listOf(PanelFieldKind.CLIPBOARD_SEARCH, PanelFieldKind.CLIPBOARD_VIEW),
            strip.map { (it.action as KeyAction.Field).kind },
        )
        // The emoji panel's own header rule does not claim the clipboard's row.
        assertNull(BuiltInPanelLayouts.CLIPBOARD.leadingStrip())
    }
}
