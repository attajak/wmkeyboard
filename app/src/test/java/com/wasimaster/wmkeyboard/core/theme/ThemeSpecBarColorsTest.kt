package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The suggestion-strip and navigation-bar fills (issue #109), and the
 * toolbar's own (#504): all nullable so a theme inherits the board unless it
 * says otherwise, all have to survive a round trip, all must read as
 * "inherit" out of a theme written before they existed, and a reseed must let
 * them go — they are exceptions to a palette that the reseed has just replaced.
 */
class ThemeSpecBarColorsTest {

    private fun barred() = ThemeSpec(
        id = "custom_1",
        name = "Two bars",
        boardBackground = 0xFF17181C,
        suggestionBarBackground = 0x8021242B,
        toolbarBackground = 0xCC1B1D23,
        navigationBarBackground = 0xFF0B0C0F,
    )

    @Test
    fun `bar fills survive a round trip, alpha and all`() {
        val decoded = ThemeCodec.decode(ThemeCodec.encode(barred()))
        assertNotNull(decoded)
        assertEquals(0x8021242B, decoded?.suggestionBarBackground)
        assertEquals(0xCC1B1D23, decoded?.toolbarBackground)
        assertEquals(0xFF0B0C0F, decoded?.navigationBarBackground)
    }

    @Test
    fun `a theme written before this feature inherits the board`() {
        val old = """
            {"id":"custom_9","name":"Old","dark":true,"boardBackground":-15329508}
        """.trimIndent()
        val decoded = ThemeCodec.decode(old)
        assertNotNull(decoded)
        assertNull(decoded?.suggestionBarBackground)
        assertNull(decoded?.toolbarBackground)
        assertNull(decoded?.navigationBarBackground)
    }

    @Test
    fun `a reseed drops both fills back to the board`() {
        val reseeded = barred().reseeded(seed = 0xFF4C8DF6, dark = false)
        assertNull(reseeded.suggestionBarBackground)
        assertNull(reseeded.toolbarBackground)
        assertNull(reseeded.navigationBarBackground)
    }

    @Test
    fun `every built-in theme inherits both bars`() {
        // The feature ships as opt-in: no shipped theme paints either bar, so
        // nothing about the stock look changes when this lands.
        for (theme in BuiltInThemes.flattenedThemes()) {
            assertNull(theme.id, theme.suggestionBarBackground)
            assertNull(theme.id, theme.toolbarBackground)
            assertNull(theme.id, theme.navigationBarBackground)
        }
    }
}
