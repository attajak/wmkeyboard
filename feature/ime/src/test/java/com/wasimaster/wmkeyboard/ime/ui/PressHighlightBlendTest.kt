package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chord-drag and layer-peek highlight is drawn over the keys, so it must
 * tint the face without painting over the label of the key it lights.
 */
class PressHighlightBlendTest {

    @Test
    fun `dark theme with a lighter press lightens`() {
        val blend = pressHighlightBlend(key = Color(0xFF303134), keyText = Color.White, pressed = Color(0xFF5F6368))
        assertEquals(BlendMode.Lighten, blend)
    }

    @Test
    fun `light theme with a darker press darkens`() {
        val blend = pressHighlightBlend(key = Color.White, keyText = Color.Black, pressed = Color(0xFFBDC1C6))
        assertEquals(BlendMode.Darken, blend)
    }

    @Test
    fun `a press that moves away from the label falls back to a plain fill`() {
        val blend = pressHighlightBlend(key = Color(0xFF5F6368), keyText = Color.White, pressed = Color(0xFF202124))
        assertEquals(BlendMode.SrcOver, blend)
    }
}
