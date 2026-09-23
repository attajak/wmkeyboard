package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.keyman.KeymanLayers
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.compileNamed
import com.wasimaster.wmkeyboard.core.layout.expandForTablet
import com.wasimaster.wmkeyboard.core.layout.fillRowFor
import com.wasimaster.wmkeyboard.core.layout.gridForTelevision
import com.wasimaster.wmkeyboard.core.layout.numberRowFor
import com.wasimaster.wmkeyboard.core.layout.panelLayers
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.layout.tabletGridWidth
import com.wasimaster.wmkeyboard.core.layout.televisionGridColumns
import com.wasimaster.wmkeyboard.core.settings.DeviceForm

/**
 * The grids the keyboard draws for [spec], repaired and compiled for a field of
 * [fieldKind] on a screen of [form].
 *
 * One function for every host of [com.wasimaster.wmkeyboard.ime.ui.KeyboardScreen]:
 * the service, which caches the result per field, and the settings app's layout
 * previews, which have to draw exactly what the service would. A second copy of
 * this in the settings app would be a preview that drifts from the keyboard the
 * first time either side learns a new layer.
 *
 * [secondaries] rides along by reference (see [compileSecondaryGrids]), and
 * [television] swaps the tablet widening for the remote-friendly grid.
 */
fun compileLayoutSet(
    spec: LayoutSpec,
    fieldKind: FieldKind,
    form: DeviceForm,
    numberRowShown: Boolean,
    secondaries: Map<String, KeyboardLayout>,
    television: Boolean,
): LayoutSet {
    val safe = spec.repair().spec
    val letters = safe.compile(LayoutLayer.LETTERS)
    // Only the letters layer widens. The symbols and Fn layers have no shift
    // key and so decline on their own, and the numeric keypads must never be
    // stretched to twelve columns — a four-column PIN pad at that width is
    // not a keypad any more.
    // A television takes the grid transform instead of the tablet one, even
    // though its screen reports as a large tablet's: the two want opposite
    // things. A tablet widens the board so two hands can reach more keys at
    // once; a remote wants the *fewest* presses between keys, which is an
    // even rectangle. `tabletExpand` gates both — a layout whose geometry is
    // authored (T9, the kana pads) says no to being rebuilt at all.
    val reflow = television && safe.tabletExpand && televisionGridColumns(letters) != null
    val expand = !reflow && form.isTablet && safe.tabletExpand
    val gridWidth = if (expand) tabletGridWidth(letters, form) else null
    return LayoutSet(
        letters = when {
            reflow -> letters.gridForTelevision()
            gridWidth != null -> letters.expandForTablet(form, numberRowShown)
            else -> letters
        },
        symbols = safe.compile(LayoutLayer.SYMBOLS),
        symbolsShifted = safe.compile(LayoutLayer.SYMBOLS_SHIFTED),
        // Only when the layout actually defines one: compile() falls back
        // to the shipped grid for a missing layer, which would give every
        // layout an Fn layer that is really a second copy of the letters.
        fn = safe.layer(LayoutLayer.FN)?.let { safe.compile(LayoutLayer.FN) },
        numeric = fieldKind.numericLayer?.let(safe::compile),
        // Same "only when authored" rule as Fn: the Numpad panel draws its
        // own hardcoded pad otherwise, with the calculator-order setting.
        number = safe.layer(LayoutLayer.NUMBER)?.let { safe.compile(LayoutLayer.NUMBER) },
        // The layout's own panel grids, already through the panel repair
        // as part of `safe`. Not through the panel-layout cache: they are
        // this layout's, so they live and die with its set.
        panels = safe.panelLayers.mapValues { (kind, grid) ->
            PanelLayoutSpec(kind, grid, appearance = safe.appearance)
        },
        numberRows = buildMap {
            safe.numberRowFor(LayoutLayer.LETTERS)?.let { put(LayoutMode.LETTERS, it) }
            safe.numberRowFor(LayoutLayer.SYMBOLS)?.let { put(LayoutMode.SYMBOLS, it) }
            safe.numberRowFor(LayoutLayer.SYMBOLS_SHIFTED)
                ?.let { put(LayoutMode.SYMBOLS_SHIFTED, it) }
            safe.numberRowFor(LayoutLayer.FN)?.let { put(LayoutMode.FN, it) }
        },
        symbolsFillRow = safe.fillRowFor(LayoutLayer.SYMBOLS),
        gridWidth = gridWidth,
        secondaries = secondaries,
        themeId = safe.themeId,
        keymanShift = safe.compileNamed(KeymanLayers.SHIFT),
        keymanCaps = safe.compileNamed(KeymanLayers.CAPS),
        named = safe.layers.keys
            .filter { it.startsWith(KeymanLayers.PREFIX) && it != KeymanLayers.SHIFT && it != KeymanLayers.CAPS }
            .mapNotNull { name -> safe.compileNamed(name)?.let { name to it } }
            .toMap(),
        keymanLayerKeys = safe.layers.keys.takeIf { keys ->
            safe.keyman != null || keys.any { it.startsWith(KeymanLayers.PREFIX) }
        },
    )
}

/**
 * The user's secondary layouts (issue #62), each compiled to its letters grid,
 * by id. Callers cache the map against the custom list's identity: every
 * [LayoutSet] shares the one instance, which is how a cached set can tell an
 * unchanged secondary list from a re-decoded one.
 */
fun compileSecondaryGrids(customs: List<LayoutSpec>): Map<String, KeyboardLayout> =
    secondaryLayouts(customs).associate { spec ->
        spec.id to spec.repair().spec.compile(LayoutLayer.LETTERS)
    }
