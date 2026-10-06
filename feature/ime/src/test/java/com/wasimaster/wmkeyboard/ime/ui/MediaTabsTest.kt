package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.Layouts
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.canBeEnabled
import com.wasimaster.wmkeyboard.core.layout.resolvePanelLayouts
import com.wasimaster.wmkeyboard.core.settings.EmojiSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MediaSwitcher
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.PanelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The switch between the emoji, GIF and sticker panels (issue #366). */
class MediaTabsTest {

    private val base = LayoutSet(Layouts.QWERTY, Layouts.SYMBOLS, Layouts.SYMBOLS_SHIFTED)
    private val switch = Key("", action = KeyAction.Field(PanelFieldKind.MEDIA_TABS), width = 3f)

    private fun settings(
        switcher: MediaSwitcher = MediaSwitcher.BOTTOM,
        remember: Boolean = false,
        tools: List<ToolbarTool> = ToolbarTool.entries.toList(),
    ) = KeyboardSettings(
        enabledTools = tools,
        emoji = EmojiSettings(mediaSwitcher = switcher, rememberMediaTab = remember),
    )

    private fun PanelLayoutSpec.fieldRows(): List<List<PanelFieldKind>> =
        grid.rows.map { row -> row.mapNotNull { (it.action as? KeyAction.Field)?.kind } }

    @Test
    fun `the switch offers emoji always and GIFs and stickers while their tools are on`() {
        assertEquals(MediaTabPanels, mediaTabs(settings()))
        assertEquals(
            listOf(PanelMode.EMOJI, PanelMode.STICKER),
            mediaTabs(settings(tools = listOf(ToolbarTool.STICKER))),
        )
        // The emoji tool off the toolbar still leaves the emoji key's panel.
        assertEquals(listOf(PanelMode.EMOJI), mediaTabs(settings(tools = emptyList())))
    }

    @Test
    fun `a switch with nowhere to go is not drawn`() {
        assertEquals(MediaSwitcher.BOTTOM, mediaSwitcherPlacement(KeyboardSettings()))
        assertEquals(MediaSwitcher.TOP, mediaSwitcherPlacement(settings(MediaSwitcher.TOP)))
        assertEquals(MediaSwitcher.OFF, mediaSwitcherPlacement(settings(tools = listOf(ToolbarTool.EMOJI))))
    }

    @Test
    fun `the shipped emoji panel puts the switch where the setting says`() {
        fun state(settings: KeyboardSettings) =
            KeyboardUiState(layouts = base, settings = settings, panelLayouts = resolvePanelLayouts(emptyList()))

        val bottom = state(settings()).panelLayout(PanelKind.EMOJI)
        assertEquals(BuiltInPanelLayouts.EMOJI, bottom)
        assertEquals(BuiltInPanelLayouts.mediaBottomRow, bottom.grid.rows.last())

        val top = state(settings(MediaSwitcher.TOP)).panelLayout(PanelKind.EMOJI)
        assertTrue(PanelFieldKind.MEDIA_TABS in top.fieldRows().first())
        assertEquals(BuiltInPanelLayouts.bottomRow, top.grid.rows.last())

        for (off in listOf(settings(MediaSwitcher.OFF), settings(tools = listOf(ToolbarTool.EMOJI)))) {
            val spec = state(off).panelLayout(PanelKind.EMOJI)
            assertFalse(spec.fieldRows().flatten().contains(PanelFieldKind.MEDIA_TABS))
            assertEquals(BuiltInPanelLayouts.bottomRow, spec.grid.rows.last())
        }
        // Every shape is one the panel repair takes as it is.
        for (placement in MediaSwitcher.entries) {
            assertTrue(placement.name, BuiltInPanelLayouts.emoji(placement).canBeEnabled())
        }
    }

    @Test
    fun `an emoji panel the user laid out keeps its own switch whatever the setting`() {
        val own = PanelLayoutSpec(
            PanelKind.EMOJI,
            LayerSpec(listOf(listOf(Key("", action = KeyAction.Field(PanelFieldKind.EMOJI_GRID), width = 10f)))),
        )
        val state = KeyboardUiState(
            layouts = base,
            settings = settings(MediaSwitcher.TOP),
            panelLayouts = resolvePanelLayouts(listOf(own)),
        )
        assertEquals(own, state.panelLayout(PanelKind.EMOJI))
    }

    private fun PanelLayoutSpec.bottom(): List<Key> = grid.rows.last()

    @Test
    fun `the GIF and sticker panels borrow the emoji panel's own switch row`() {
        // The shipped panel: its bottom row, as it ships, under the browser.
        val gif = mediaPanelLayout(PanelKind.GIF, BuiltInPanelLayouts.EMOJI, MediaSwitcher.BOTTOM)
        assertEquals(BuiltInPanelLayouts.GIF, gif)
        assertTrue(gif.canBeEnabled())
        // A bottom row the user rearranged comes across, height and all.
        val comma = Key(",")
        val rearranged = listOf(Key("ABC", action = KeyAction.Letters), switch, comma, Key(" ", action = KeyAction.Space))
        val grid = Key("", action = KeyAction.Field(PanelFieldKind.EMOJI_GRID), width = 10f)
        val own = PanelLayoutSpec(PanelKind.EMOJI, LayerSpec(listOf(listOf(grid), rearranged), rowHeights = listOf(3f, 1.2f)))
        val mirrored = mediaPanelLayout(PanelKind.STICKER, own, MediaSwitcher.BOTTOM)
        assertEquals(PanelKind.STICKER, mirrored.panel)
        assertEquals(listOf(PanelFieldKind.STICKER_BROWSER), mirrored.fieldRows().first())
        assertEquals(rearranged, mirrored.bottom())
        assertEquals(listOf(3f, 1.2f), mirrored.grid.rowHeights)
        assertTrue(mirrored.canBeEnabled())
    }

    @Test
    fun `with the switch up top the GIF panel still ends in abc space and backspace`() {
        // Issue #538: the emoji panel's own last row of keys, not an empty edge.
        for (placement in listOf(MediaSwitcher.TOP, MediaSwitcher.OFF)) {
            val gif = mediaPanelLayout(PanelKind.GIF, BuiltInPanelLayouts.emoji(placement), placement)
            assertEquals(placement.name, BuiltInPanelLayouts.bottomRow, gif.bottom())
            assertTrue(placement.name, gif.canBeEnabled())
        }
    }

    @Test
    fun `a switch row that is not keys and the switch alone stays with the emoji panel`() {
        val grid = Key("", action = KeyAction.Field(PanelFieldKind.EMOJI_GRID), width = 10f)
        val tabs = Key("", action = KeyAction.Field(PanelFieldKind.EMOJI_TABS), width = 7f)
        // Beside the tab strip, with no row of keys: the shipped row for the setting.
        val beside = PanelLayoutSpec(PanelKind.EMOJI, LayerSpec(listOf(listOf(tabs, switch), listOf(grid))))
        assertEquals(BuiltInPanelLayouts.mediaBottomRow, mediaPanelLayout(PanelKind.GIF, beside, MediaSwitcher.BOTTOM).bottom())
        assertEquals(BuiltInPanelLayouts.bottomRow, mediaPanelLayout(PanelKind.GIF, beside, MediaSwitcher.TOP).bottom())
        // Beside the tab strip over a row of keys: that row, without a switch.
        val keys = listOf(Key("ABC", action = KeyAction.Letters), Key("⌫", action = KeyAction.Delete))
        val over = PanelLayoutSpec(PanelKind.EMOJI, LayerSpec(listOf(listOf(tabs, switch), listOf(grid), keys)))
        assertEquals(keys, mediaPanelLayout(PanelKind.GIF, over, MediaSwitcher.BOTTOM).bottom())
        // Under a tall key that reaches down into it.
        val tall = Key("⌫", action = KeyAction.Delete, rowSpan = 2)
        val reached = PanelLayoutSpec(PanelKind.EMOJI, LayerSpec(listOf(listOf(grid, tall), listOf(switch))))
        assertEquals(BuiltInPanelLayouts.mediaBottomRow, mediaPanelLayout(PanelKind.GIF, reached, MediaSwitcher.BOTTOM).bottom())
        // No switch and no keys at all.
        val none = PanelLayoutSpec(PanelKind.EMOJI, LayerSpec(listOf(listOf(grid))))
        assertEquals(BuiltInPanelLayouts.mediaBottomRow, mediaPanelLayout(PanelKind.GIF, none, MediaSwitcher.BOTTOM).bottom())
    }

    @Test
    fun `a GIF panel the user laid out is drawn as laid out`() {
        val browser = Key("", action = KeyAction.Field(PanelFieldKind.GIF_BROWSER), width = 10f)
        val own = PanelLayoutSpec(PanelKind.GIF, LayerSpec(listOf(listOf(browser), listOf(switch))))
        assertTrue(own.canBeEnabled())
        val state = KeyboardUiState(
            layouts = base,
            settings = settings(MediaSwitcher.TOP),
            panelLayouts = resolvePanelLayouts(listOf(own)),
        )
        assertEquals(own, state.panelLayout(PanelKind.GIF))
        // The switch goes on any of the three media panels, never on the clipboard.
        assertTrue(PanelFieldKind.MEDIA_TABS.isOn(PanelKind.STICKER))
        assertFalse(PanelFieldKind.MEDIA_TABS.isOn(PanelKind.CLIPBOARD))
        assertFalse(PanelFieldKind.GIF_BROWSER.isOn(PanelKind.STICKER))
    }

    @Test
    fun `the emoji key opens emoji until the last used panel is asked for`() {
        val plain = settings()
        assertEquals(PanelMode.EMOJI, mediaOpenerTarget(PanelMode.NONE, PanelMode.GIF, plain))
        assertEquals(PanelMode.EMOJI, mediaOpenerTarget(PanelMode.STICKER, PanelMode.STICKER, plain))

        val remember = settings(remember = true)
        assertEquals(PanelMode.GIF, mediaOpenerTarget(PanelMode.NONE, PanelMode.GIF, remember))
        assertEquals(PanelMode.EMOJI, mediaOpenerTarget(PanelMode.CLIPBOARD, PanelMode.EMOJI, remember))
        // With one of the three up, the key names it, and naming it closes it.
        assertEquals(PanelMode.STICKER, mediaOpenerTarget(PanelMode.STICKER, PanelMode.STICKER, remember))
        assertEquals(PanelMode.GIF, mediaOpenerTarget(PanelMode.GIF, PanelMode.EMOJI, remember))
        // A last panel whose tool has gone since is emoji again.
        val noGif = settings(remember = true, tools = listOf(ToolbarTool.EMOJI, ToolbarTool.STICKER))
        assertEquals(PanelMode.EMOJI, mediaOpenerTarget(PanelMode.NONE, PanelMode.GIF, noGif))
    }
}
