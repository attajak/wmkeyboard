package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/** The 🌐 key's recently-used order (#311). */
class RecentLayoutOrderTest {

    private val enabled = listOf("a", "b", "c", "d", "e")

    @Test
    fun `no history falls back to switch order after the current layout`() {
        assertEquals(listOf("c", "a", "b", "d", "e"), recentLayoutOrder(emptyList(), enabled, "c"))
    }

    @Test
    fun `recent layouts come first, the rest keep switch order`() {
        assertEquals(
            listOf("e", "a", "c", "b", "d"),
            recentLayoutOrder(listOf("e", "a", "c"), enabled, "e"),
        )
    }

    @Test
    fun `disabled layouts in the history are skipped`() {
        assertEquals(
            listOf("a", "d", "b", "c", "e"),
            recentLayoutOrder(listOf("a", "gone", "d"), enabled, "a"),
        )
    }

    @Test
    fun `a current layout outside the cycle is left out`() {
        assertEquals(listOf("b", "a", "c", "d", "e"), recentLayoutOrder(listOf("b"), enabled, "override"))
    }

    @Test
    fun `a switch puts the target first and where it came from second`() {
        assertEquals(listOf("e", "a", "b"), rememberLayoutSwitch(listOf("b"), from = "a", to = "e"))
    }

    @Test
    fun `switching back and forth trades the first two places`() {
        val once = rememberLayoutSwitch(listOf("a", "c", "d"), from = "a", to = "d")
        assertEquals(listOf("d", "a", "c"), once)
        assertEquals(listOf("a", "d", "c"), rememberLayoutSwitch(once, from = "d", to = "a"))
    }

    @Test
    fun `the history is capped`() {
        val long = (0 until 40).map { "l$it" }
        assertEquals(RECENT_LAYOUT_CAP, rememberLayoutSwitch(long, from = "x", to = "y").size)
    }
}
