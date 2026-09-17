package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import com.wasimaster.wmkeyboard.core.tools.FocusGrid
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.PanelMode

/**
 * How the service moves a focus ring through a panel it cannot see inside.
 *
 * The index travels *down* on [com.wasimaster.wmkeyboard.ime.KeyboardUiState],
 * which every panel already receives. The geometry travels *up* through this
 * holder, because that is the only direction that works: half the panels keep
 * their item list in a composable-local `remember` (the symbol category, the
 * emoji pager page, the Wikipedia tab, the image-search dedup) and two size
 * their grids with `GridCells.Adaptive`, whose column count nothing knows until
 * layout has run. Hoisting all of that into the service to serve a highlight
 * would be a much larger change than letting the panels publish two numbers and
 * a lambda.
 *
 * Same shape as [ToolDragController], which already does exactly this for the
 * toolbox's drag-and-drop.
 */
class PanelFocusController {

    /**
     * The same idea one layer out: the ring over the *keys*, for a television
     * remote (see [KeyGridFocus]).
     *
     * Carried here rather than passed to [com.wasimaster.wmkeyboard.ime.ui.KeyboardScreen]
     * as a parameter of its own, because that function's argument list already
     * compiles to a method at the JVM's 64K ceiling — the note on its signature
     * is not decoration. This object is already the service's handle on "where
     * the focus ring is", and the two rings are never up at once: a panel owns
     * the arrow keys whenever one is open.
     */
    internal val keyGrid = KeyGridFocus()

    /**
     * The panel that published the current geometry. The service checks it
     * before every move: a panel that has just closed leaves its counts behind
     * for a frame, and acting on those would activate an invisible item.
     */
    var panel: PanelMode = PanelMode.NONE
        private set

    /** The regions Tab cycles, in the order the open panel published them. */
    var regions: List<FocusRegion> = emptyList()
        private set

    private val grids = HashMap<FocusRegion, FocusGrid>()
    private val actions = HashMap<FocusRegion, (Int) -> Unit>()

    /**
     * A panel region says how many items it has, how wide its grid is, and what
     * to do when one is activated. Called every recomposition; the last writer
     * for a region wins.
     */
    fun publish(
        panel: PanelMode,
        region: FocusRegion,
        count: Int,
        columns: Int,
        onActivate: (Int) -> Unit,
    ) {
        if (this.panel != panel) {
            // A different panel is drawing: drop the old one's geometry rather
            // than letting two panels' regions coexist.
            grids.clear()
            actions.clear()
            this.panel = panel
        }
        grids[region] = FocusGrid(count, columns)
        actions[region] = onActivate
        regions = com.wasimaster.wmkeyboard.ime.panelFocusRegions(panel).filter { it in grids }
    }

    fun grid(region: FocusRegion): FocusGrid = grids[region] ?: FocusGrid(0, 1)

    fun activate(region: FocusRegion, index: Int) {
        actions[region]?.invoke(index)
    }

    /** The first region with something in it, or null when the panel has nothing to focus. */
    fun firstUsableRegion(): FocusRegion? = regions.firstOrNull { grid(it).count > 0 }

    fun matches(panel: PanelMode): Boolean = this.panel == panel

    fun reset() {
        panel = PanelMode.NONE
        regions = emptyList()
        grids.clear()
        actions.clear()
    }
}

val LocalPanelFocus = staticCompositionLocalOf { PanelFocusController() }

/**
 * Registers one navigable region of the open panel with the hardware focus ring.
 *
 * Written in a [SideEffect] rather than the composition body on purpose: the
 * controller holds plain vars read from outside Compose, and writing them while
 * composing is how a recomposition loop starts.
 */
@Composable
fun PanelFocusTarget(
    panel: PanelMode,
    count: Int,
    columns: Int,
    region: FocusRegion = FocusRegion.RESULTS,
    onActivate: (Int) -> Unit,
) {
    val controller = LocalPanelFocus.current
    SideEffect { controller.publish(panel, region, count, columns, onActivate) }
}
