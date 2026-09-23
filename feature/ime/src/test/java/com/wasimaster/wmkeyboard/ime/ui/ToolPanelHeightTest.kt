package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #333: a tool panel asked for a fixed height, the key rows were measured
 * against what it left, and on a phone turned sideways that was a sliver.
 * [fitToolPanelHeight] is the panel giving way instead.
 */
class ToolPanelHeightTest {

    /** Translate's box while typed into, on the reporter's sideways phone. */
    @Test
    fun `a collapsed panel gives way to the keys under it on a short screen`() {
        // 393dp tall, keys with a digit row 201dp, their strip 34dp.
        val height = fitToolPanelHeight(
            wanted = 146.dp,
            floor = 40.dp,
            screenHeight = 393.dp,
            around = 235.dp,
        )
        assertEquals(393f * 0.8f - 235f, height.value, 0.01f)
    }

    /** Upright there is room, and nothing changes. */
    @Test
    fun `a collapsed panel keeps its height upright`() {
        val height = fitToolPanelHeight(wanted = 146.dp, floor = 40.dp, screenHeight = 800.dp, around = 300.dp)
        assertEquals(146.dp, height)
    }

    /** The header holds the search box and the way back, so it always stays. */
    @Test
    fun `a collapsed panel never goes below its header`() {
        val height = fitToolPanelHeight(wanted = 146.dp, floor = 40.dp, screenHeight = 300.dp, around = 235.dp)
        assertEquals(40.dp, height)
    }

    /**
     * A panel over the board (the converters, the calendar, the AI) loses its
     * extra room first, and never the board's own height: that is the window
     * the keyboard already had.
     */
    @Test
    fun `a panel over the board trims its extra room but never the board`() {
        val board = 200.dp
        val trimmed = fitToolPanelHeight(wanted = board + 140.dp, floor = board, screenHeight = 393.dp, around = 0.dp)
        assertEquals(393f * 0.8f, trimmed.value, 0.01f)
        val tiny = fitToolPanelHeight(wanted = board + 140.dp, floor = board, screenHeight = 200.dp, around = 0.dp)
        assertEquals(board, tiny)
    }
}
