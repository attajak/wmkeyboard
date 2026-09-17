package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.theme.KEY_OVERRIDE_LABEL_SCALE_RANGE
import com.wasimaster.wmkeyboard.core.theme.KeyOverride
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-key style overrides through [keyVisual]: the override recolours
 * exactly its own key, derives its pressed shade, and — the invariant the key
 * grid rests on — still varies with nothing a keystroke changes, since the
 * map rides the palette and the palette rides the theme.
 */
class KeyOverrideVisualTest {

    private val red = 0xFFFF0000
    private val white = 0xFFFFFFFF

    private fun palette(overrides: Map<String, KeyOverride> = emptyMap()) = KeyPalette(
        key = Color.White,
        keyText = Color.Black,
        modifierKey = Color.Gray,
        modifierKeyText = Color.DarkGray,
        enterKey = Color.Blue,
        enterKeyText = Color.Yellow,
        pressedKey = Color.LightGray,
        accent = Color.Magenta,
        overrides = overrides,
    )

    private fun state() = KeyboardUiState(
        settings = KeyboardSettings(),
        layouts = LayoutSet(
            BuiltInLayouts.QWERTY.compile(LayoutLayer.LETTERS),
            BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS),
            BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS_SHIFTED),
        ),
    )

    @Test
    fun `override recolours its own key and no other`() {
        val palette = palette(mapOf("a" to KeyOverride(background = red, text = white)))
        val a = keyVisual(Key("a"), state(), palette)
        val b = keyVisual(Key("b"), state(), palette)
        assertEquals(Color(red.toInt()), a.background)
        assertEquals(Color(white.toInt()), a.contentColor)
        assertEquals(Color.White, b.background)
        assertEquals(Color.Black, b.contentColor)
    }

    @Test
    fun `an overridden face derives its pressed shade`() {
        val palette = palette(mapOf("a" to KeyOverride(background = red)))
        val a = keyVisual(Key("a"), state(), palette)
        assertEquals(lerp(Color(red.toInt()), a.contentColor, 0.25f), a.pressedBackground)
    }

    @Test
    fun `popup and border colours ride the visual`() {
        val palette = palette(
            mapOf("a" to KeyOverride(border = red, popupBackground = red, popupText = white)),
        )
        val a = keyVisual(Key("a"), state(), palette)
        assertEquals(Color(red.toInt()), a.borderColor)
        assertEquals(Color(red.toInt()), a.popupBackground)
        assertEquals(Color(white.toInt()), a.popupText)
        val plain = keyVisual(Key("b"), state(), palette)
        assertNull(plain.borderColor)
        assertNull(plain.popupBackground)
    }

    @Test
    fun `special keys resolve by action name`() {
        assertEquals("ENTER", keyOverrideId(Key("", action = KeyAction.Enter)))
        assertEquals("SPACE", keyOverrideId(Key("", action = KeyAction.Space)))
        assertEquals("SHIFT", keyOverrideId(Key("", action = KeyAction.Shift)))
        assertEquals(
            "MOD_CTRL",
            keyOverrideId(Key("", action = KeyAction.Mod(ModifierKey.CTRL))),
        )
        assertEquals("a", keyOverrideId(Key("A")))
        assertNull(keyOverrideId(Key("")))
    }

    @Test
    fun `an override keyed by letter is case-insensitive to the label`() {
        val palette = palette(mapOf("a" to KeyOverride(background = red)))
        val upper = keyVisual(Key("A"), state(), palette)
        assertEquals(Color(red.toInt()), upper.background)
    }

    @Test
    fun `a key's own hint colour beats the theme's`() {
        val themed = palette(mapOf("a" to KeyOverride(hint = red)))
            .copy(hintText = Color.Green)
        assertEquals(Color(red.toInt()), keyVisual(Key("a"), state(), themed).hintColor)
        // Every other key still takes the theme's hint, not the default fade.
        assertEquals(Color.Green, keyVisual(Key("b"), state(), themed).hintColor)
    }

    @Test
    fun `bold is a three-state answer`() {
        val palette = palette(
            mapOf("a" to KeyOverride(bold = true), "b" to KeyOverride(bold = false)),
        )
        assertEquals(true, keyVisual(Key("a"), state(), palette).bold)
        assertEquals(false, keyVisual(Key("b"), state(), palette).bold)
        // Null is the third answer, and it is what "follow the board" is.
        assertNull(keyVisual(Key("c"), state(), palette).bold)
    }

    @Test
    fun `a label scale out of a file is clamped, and the layout's own wins`() {
        val palette = palette(mapOf("a" to KeyOverride(labelScale = 9f)))
        val visual = keyVisual(Key("a"), state(), palette)
        assertEquals(KEY_OVERRIDE_LABEL_SCALE_RANGE.endInclusive, visual.labelScale)
        assertEquals(KEY_OVERRIDE_LABEL_SCALE_RANGE.endInclusive, visual.drawnLabelScale())
        // A key the layout sized itself keeps that size: the layout is the one
        // board, the theme is worn by all of them.
        val authored = keyVisual(Key("a", labelScale = 1.4f), state(), palette)
        assertEquals(1.4f, authored.drawnLabelScale())
    }

    @Test
    fun `a nonsense label scale is dropped rather than drawn`() {
        val palette = palette(mapOf("a" to KeyOverride(labelScale = Float.NaN)))
        assertNull(keyVisual(Key("a"), state(), palette).labelScale)
    }

    @Test
    fun `the override id rides the visual for the texture and the burst`() {
        val palette = palette(mapOf("a" to KeyOverride(background = red)))
        assertEquals("a", keyVisual(Key("a"), state(), palette).overrideId)
        // Keys the theme never named carry an id too, so one override does not
        // make every other key look one up.
        assertEquals("b", keyVisual(Key("b"), state(), palette).overrideId)
        // A theme with no single-key styles at all asks nothing of any key.
        assertNull(keyVisual(Key("a"), state(), palette()).overrideId)
    }

    @Test
    fun `an effect slice belongs to the key that asked for it`() {
        val glyphs = EffectGlyphs(
            // The slice is an index range; the bitmaps behind it are the
            // rasterizer's business and need a device to make.
            bitmaps = emptyList(),
            base = 0..1,
            perKey = mapOf("a" to 2..4),
        )
        assertEquals(2..4, glyphs.sliceFor("a"))
        assertEquals(0..1, glyphs.sliceFor("b"))
        assertEquals(0..1, glyphs.sliceFor(null))
    }

    @Test
    fun `a board whose only effect is one key's still throws that key's`() {
        val glyphs = EffectGlyphs(
            bitmaps = emptyList(),
            base = IntRange.EMPTY,
            perKey = mapOf("ENTER" to 0..1),
        )
        assertEquals(0..1, glyphs.sliceFor("ENTER"))
        // And a key with no effect of its own throws nothing rather than
        // borrowing the one the enter key was given.
        assertTrue(glyphs.sliceFor("a").isEmpty())
    }
}
