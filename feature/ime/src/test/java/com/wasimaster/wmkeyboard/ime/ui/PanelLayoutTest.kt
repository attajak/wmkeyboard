package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.holdIsSpokenFor
import com.wasimaster.wmkeyboard.core.layout.panelFlexRows
import com.wasimaster.wmkeyboard.core.layout.rowScaledKeyHeight
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The panel layouts as the keyboard draws them (issue #63). */
class PanelLayoutTest {

    private val palette = KeyPalette(
        key = Color.White,
        keyText = Color.Black,
        modifierKey = Color.Gray,
        modifierKeyText = Color.Black,
        enterKey = Color.Blue,
        enterKeyText = Color.White,
        pressedKey = Color.LightGray,
        accent = Color.Red,
        hintText = Color.DarkGray,
    )

    @Test
    fun `abc leaves the panel, a component does nothing, every other key reaches the service`() {
        val sent = mutableListOf<Key>()
        var closed = 0
        val route = { key: Key -> routePanelKey(key, { sent += it }, { closed++ }) }
        route(Key("ABC", action = KeyAction.Letters))
        assertEquals(1, closed)
        route(Key("", action = KeyAction.Field(PanelFieldKind.EMOJI_GRID)))
        assertEquals(1, closed)
        assertTrue(sent.isEmpty())
        val space = Key(" ", action = KeyAction.Space)
        val delete = Key("⌫", action = KeyAction.Delete)
        val edit = Key("", action = KeyAction.Edit(TextEditAction.HOME))
        route(space); route(delete); route(edit)
        assertEquals(listOf(space, delete, edit), sent)
    }

    /**
     * Issue #183: the layer keys leave the panel and then switch, so the
     * layer lands on screen; shift and the chorded keys are plain keys.
     */
    @Test
    fun `layer keys close the panel before they switch, and shift and chords go straight through`() {
        val sent = mutableListOf<Key>()
        val log = mutableListOf<String>()
        val route = { key: Key ->
            routePanelKey(key, { sent += it; log += "key" }, { log += "close" })
        }
        val symbols = Key("?123", action = KeyAction.Symbols)
        val fn = Key("Fn", action = KeyAction.Fn)
        val layout = Key("L", action = KeyAction.Layout("my_layout"))
        for (key in listOf(symbols, fn, layout)) {
            log.clear()
            route(key)
            assertEquals(listOf("close", "key"), log)
        }
        assertEquals(listOf(symbols, fn, layout), sent)
        sent.clear(); log.clear()
        val shift = Key("⇧", action = KeyAction.Shift)
        val caps = Key("⇪", action = KeyAction.CapsLock)
        val dot = Key("·", action = KeyAction.MorseDot)
        val braille = Key("1", action = KeyAction.BrailleDot(1, release = true))
        route(shift); route(caps); route(dot); route(braille)
        assertEquals(listOf(shift, caps, dot, braille), sent)
        assertFalse("close" in log)
    }

    @Test
    fun `the select key lights with selection mode and a text key does not`() {
        val select = Key("", action = KeyAction.Edit(TextEditAction.SELECT))
        val off = keyVisual(select, KeyboardUiState(), palette)
        val on = keyVisual(select, KeyboardUiState(textEditSelecting = true), palette)
        assertEquals(palette.modifierKey, off.background)
        assertEquals(palette.accent, on.background)
        assertNotEquals(off, on)
        val letter = Key("a")
        assertEquals(
            keyVisual(letter, KeyboardUiState(), palette),
            keyVisual(letter, KeyboardUiState(textEditSelecting = true), palette),
        )
    }

    @Test
    fun `an edit key speaks its operation`() {
        val home = Key("", action = KeyAction.Edit(TextEditAction.HOME))
        val visual = keyVisual(home, KeyboardUiState(), palette)
        assertEquals(textEditDescription(TextEditAction.HOME), visual.spoken.textRes)
    }

    /**
     * Every shipped panel's key rows fit inside the key area with room left for
     * the components: the fixed rows of the panel never add up to more than the
     * height the keyboard reserves, at the default key height.
     */
    @Test
    fun `every shipped panel's fixed rows leave the components real height`() {
        val settings = KeyboardSettings()
        val state = KeyboardUiState(settings = settings)
        val available = keyRowsHeight(state)
        for ((kind, spec) in BuiltInPanelLayouts.byKind) {
            val rows = spec.grid.rows
            val flex = panelFlexRows(rows)
            var fixed = 0.dp
            for (i in rows.indices) {
                if (flex[i]) continue
                fixed += rowScaledKeyHeight(settings.keyHeightDp, spec.grid.rowHeights?.getOrNull(i)).dp +
                    keyGapV(settings) * 2
            }
            if (kind.requiredField != null) {
                // The strips and the bottom row together leave the body at
                // least a third of the key area.
                assertTrue("$kind: fixed rows $fixed must leave room in $available", fixed < available * 0.67f)
            } else {
                assertFalse("$kind has no component row", flex.any { it })
            }
        }
    }

    @Test
    fun `the text-editing pad is keys only and holds alternates on the still keys alone`() {
        val keys = BuiltInPanelLayouts.default(PanelKind.TEXT_EDIT).grid.rows.flatten()
        assertTrue(keys.all { it.action is KeyAction.Edit })
        for (key in keys) {
            val op = (key.action as KeyAction.Edit).op
            if (key.actionAlternates.isNotEmpty()) {
                assertFalse("$op repeats and cannot hold an alternate", key.action.holdIsSpokenFor())
            }
        }
        assertEquals(2, keys.count { it.actionAlternates.isNotEmpty() })
    }
}
