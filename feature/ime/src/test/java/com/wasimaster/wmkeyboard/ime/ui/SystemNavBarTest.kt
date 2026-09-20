package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.theme.GradientSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour the IME window hands the system navigation bar below Android 15,
 * where the drawn band ([NavigationBarBackground]) never gets an inset to sit
 * in and the bar would otherwise keep the platform's own colour — opaque white
 * under a light keyboard on some OEM builds (issue #255).
 */
class SystemNavBarTest {

    private fun base() =
        defaultKbTheme(lightColorScheme(), dark = false, amoled = false, settings = KeyboardSettings())

    @Test
    fun `a plain theme hands the bar its board colour`() {
        val kb = base().copy(board = Color(0xFFE0EBE7))
        assertEquals(Color(0xFFE0EBE7), navigationBandColor(kb))
    }

    @Test
    fun `a theme that names the band wins over the board`() {
        val kb = base().copy(board = Color(0xFFE0EBE7), navigationBar = Color(0xFF0B0C0F))
        assertEquals(Color(0xFF0B0C0F), navigationBandColor(kb))
    }

    @Test
    fun `a gradient board is represented by its far stop`() {
        val kb = base().copy(
            board = Color(0xFF101010),
            boardGradient = GradientSpec(colors = listOf(0xFF102040, 0xFF4080C0)),
        )
        assertEquals(Color(0xFF4080C0), navigationBandColor(kb))
    }

    @Test
    fun `a translucent band is flattened onto the board, not handed over see-through`() {
        // The system re-enables its contrast scrim over a translucent bar
        // colour, so alpha has to be spent here instead.
        val kb = base().copy(board = Color.White, navigationBar = Color(0x80000000))
        val painted = navigationBandColor(kb)
        assertEquals(1f, painted.alpha, 0f)
        assertEquals(0.5f, painted.red, 0.01f)
    }

    @Test
    fun `a see-through board still yields an opaque bar`() {
        val kb = base().copy(board = Color(0x40FF0000))
        assertEquals(1f, navigationBandColor(kb).alpha, 0f)
    }

    @Test
    fun `icon colour follows the bar, not the theme's light-dark flag`() {
        // An AMOLED-black band under a "light" theme still needs light icons,
        // and a pale band under a dark one still needs dark ones.
        assertTrue(navigationBarWantsDarkIcons(Color(0xFFE0EBE7)))
        assertFalse(navigationBarWantsDarkIcons(Color(0xFF0B0C0F)))
    }
}
