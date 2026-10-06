package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.layout.BuiltInPanelLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MediaSwitcher
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.layoutKind

/**
 * The switch between the emoji, GIF and sticker panels (issue #366): one
 * segment per panel, the open one lit, so the three read as the tabs of one
 * panel the way they do on most keyboards.
 *
 * Every one of the three can carry it as a component of its layout
 * ([PanelFieldKind.MEDIA_TABS]); a GIF or sticker panel the user has not laid
 * out borrows the emoji panel's row that holds it, or puts the switch beside
 * its search box, as [KeyboardSettings.emoji]'s `mediaSwitcher` says.
 */

/** The panels the switch moves between, in the order it draws them. */
internal val MediaTabPanels: List<PanelMode> = listOf(PanelMode.EMOJI, PanelMode.GIF, PanelMode.STICKER)

/** The tool behind each of [MediaTabPanels]: its icon, its name, and whether it is switched on. */
private fun PanelMode.mediaTool(): ToolbarTool? = when (this) {
    PanelMode.EMOJI -> ToolbarTool.EMOJI
    PanelMode.GIF -> ToolbarTool.GIF
    PanelMode.STICKER -> ToolbarTool.STICKER
    else -> null
}

/**
 * The panels the switch offers. Emoji always: the emoji key opens that panel
 * with the tool off the toolbar. GIFs and stickers only while their tool is
 * switched on, shipped in this build and usable, the tests the toolbar makes
 * before it draws one.
 */
internal fun mediaTabs(settings: KeyboardSettings): List<PanelMode> = MediaTabPanels.filter { panel ->
    val tool = panel.mediaTool() ?: return@filter false
    panel == PanelMode.EMOJI ||
        (tool in settings.enabledTools && isSupportedTool(tool) && isUsableTool(tool, settings))
}

/**
 * Where the switch goes: the setting, or [MediaSwitcher.OFF] while there is
 * only the emoji panel to switch to, since a switch with one segment goes
 * nowhere and would only take room from the space bar.
 */
internal fun mediaSwitcherPlacement(settings: KeyboardSettings): MediaSwitcher =
    if (mediaTabs(settings).size < 2) MediaSwitcher.OFF else settings.emoji.mediaSwitcher

/**
 * The panel the emoji key and the emoji tool open, given the panel [open] now
 * and the one of the three that was open [last].
 *
 * Plain emoji until "open the last used" is on. Then the three are one panel:
 * with none of them up, the key brings back whichever was open last; with one
 * of them up, it names that one, which closes it, the way the key has always
 * closed the emoji panel. A last panel whose tool has since been switched off
 * falls back to emoji.
 */
internal fun mediaOpenerTarget(open: PanelMode, last: PanelMode, settings: KeyboardSettings): PanelMode {
    if (!settings.emoji.rememberMediaTab) return PanelMode.EMOJI
    if (open in MediaTabPanels) return open
    return last.takeIf { it in mediaTabs(settings) } ?: PanelMode.EMOJI
}

/**
 * The GIF or sticker panel the user has not laid out (#538): its browser over
 * a row borrowed from the emoji panel, so the three panels end in the same
 * keys and a bottom row the user rearranged on the emoji panel comes across.
 *
 * The row borrowed is the emoji panel's switch row when that is keys and the
 * switch alone, else the last row of keys it has (abc / space / backspace on
 * the shipped panel with the switch up top), else the shipped row for the
 * switch's [placement]. A row that also holds an emoji component, or that a
 * key from the row above reaches down into, belongs to the emoji panel, and
 * lifted out it would draw a hole. The emoji panel's label size, theme and
 * font ride along for the same reason.
 */
internal fun mediaPanelLayout(kind: PanelKind, emoji: PanelLayoutSpec, placement: MediaSwitcher): PanelLayoutSpec {
    val rows = emoji.grid.rows
    val switchRow = rows.indexOfFirst { row -> row.any { it.isMediaTabs() } }
    val keysRow = rows.indexOfLast { row -> row.none { it.action is KeyAction.Field } }
    val index = when {
        rows.liftable(switchRow) -> switchRow
        rows.liftable(keysRow) -> keysRow
        else -> -1
    }
    val own = rows.getOrNull(index)
    val shipped = if (placement == MediaSwitcher.BOTTOM) BuiltInPanelLayouts.mediaBottomRow else BuiltInPanelLayouts.bottomRow
    val height = own?.let { emoji.grid.rowHeights?.getOrNull(index) } ?: 1f
    val spec = BuiltInPanelLayouts.media(kind, own ?: shipped, height)
    return spec.copy(
        grid = spec.grid.copy(fontScale = emoji.grid.fontScale, themeId = emoji.grid.themeId),
        appearance = emoji.appearance,
    )
}

/** Whether row [index] stands on its own: keys and the switch, nothing reaching in from above. */
private fun List<List<Key>>.liftable(index: Int): Boolean {
    val row = getOrNull(index) ?: return false
    val reachedInto = take(index).withIndex().any { (r, above) -> above.any { key -> r + key.rowSpan > index } }
    return !reachedInto && row.all { key -> key.rowSpan <= 1 && (key.action !is KeyAction.Field || key.isMediaTabs()) }
}

private fun Key.isMediaTabs(): Boolean = (action as? KeyAction.Field)?.kind == PanelFieldKind.MEDIA_TABS

/**
 * The switch as a pill beside the GIF or sticker search box, or null: only
 * while the switch is on and the panel's layout has no switch cell of its
 * own, so a layout that places it never shows two. Never while a search has
 * the keys: they come back under the panel then.
 */
internal fun mediaSwitchPill(
    state: KeyboardUiState,
    spec: PanelLayoutSpec,
    onPanelChange: (PanelMode) -> Unit,
): (@Composable () -> Unit)? {
    if (state.mediaSearchActive || mediaSwitcherPlacement(state.settings) == MediaSwitcher.OFF) return null
    if (spec.grid.rows.any { row -> row.any { it.isMediaTabs() } }) return null
    return { MediaTabsPill(state, onPanelChange) }
}

/**
 * The GIF or sticker panel (#538): the browser and the keys around it, laid
 * out like the emoji panel and inside the full-bleed chrome when that setting
 * is on. A search draws the browser alone, short, with the key rows back
 * underneath it for typing the query.
 *
 * [browser] draws the panel's body; `fullBleed` says the header already holds
 * the search box, `inGrid` that it is filling a layout cell rather than
 * sizing itself. [headerSearch] is the full-bleed header's search box.
 */
@Composable
internal fun MediaPanelHost(
    state: KeyboardUiState,
    callbacks: PanelLayoutCallbacks,
    headerSearch: @Composable RowScope.() -> Unit,
    browser: @Composable (fullBleed: Boolean, inGrid: Boolean, switcher: (@Composable () -> Unit)?) -> Unit,
) {
    val kind = state.panel.layoutKind ?: return
    val onClose = { callbacks.onPanelChange(state.panel) }
    val spec = state.panelLayout(kind)
    val pill = mediaSwitchPill(state, spec, callbacks.onPanelChange)
    val fullBleed = state.settings.mediaFullBleed
    val searching = state.mediaSearchActive
    val fields: @Composable (PanelFieldKind) -> Unit = { field ->
        when (field) {
            PanelFieldKind.MEDIA_TABS -> MediaTabsField(state, callbacks.onPanelChange)
            kind.requiredField -> browser(fullBleed, true, if (fullBleed) null else pill)
            else -> Unit
        }
    }
    if (fullBleed) {
        FullBleedTool(
            state,
            title = "",
            onClose = onClose,
            // Search collapses the panel so the key rows fit below it, keeping
            // a band of live results up.
            compact = searching,
            headerActions = {
                headerSearch()
                pill?.invoke()
            },
        ) {
            if (searching) {
                browser(true, false, null)
            } else {
                PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
            }
        }
    } else if (searching) {
        browser(false, false, null)
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyRowsHeight(state)),
        ) {
            PanelLayoutGrid(state, spec, callbacks, onClose, fields, Modifier.fillMaxSize())
        }
    }
}

/**
 * The switch as a fixed-size pill for a search row: in the full-bleed
 * header, or at the end of the GIF and sticker search bar.
 */
@Composable
internal fun MediaTabsPill(state: KeyboardUiState, onPanelChange: (PanelMode) -> Unit) {
    val count = mediaTabs(state.settings).size
    if (count < 2) return
    MediaTabsField(
        state, onPanelChange,
        Modifier
            .width(MediaPillSegmentWidth * count)
            .height(MediaPillHeight),
    )
}

private val MediaPillSegmentWidth = 36.dp
private val MediaPillHeight = 32.dp

/**
 * The switch itself, filling what it is given. A segment for the open panel
 * does nothing when pressed: the switch changes panels, it never closes one,
 * which is what abc and the back button are for.
 */
@Composable
internal fun MediaTabsField(
    state: KeyboardUiState,
    onPanelChange: (PanelMode) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val tabs = mediaTabs(state.settings)
    if (tabs.size < 2) return
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (panel in tabs) {
            val tool = panel.mediaTool() ?: continue
            val selected = panel == state.panel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(if (selected) kb.chipActive else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab) {
                        if (!selected) onPanelChange(panel)
                    },
                contentAlignment = Alignment.Center,
            ) {
                SlotIcon(
                    IconSlots.forTool(tool),
                    contentDescription = toolLabel(tool),
                    modifier = Modifier.size(18.dp),
                    tint = if (selected) kb.chipActiveText else kb.chipText,
                )
            }
        }
    }
}
