package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.alternateEntries
import com.wasimaster.wmkeyboard.core.layout.holdRepeats
import com.wasimaster.wmkeyboard.core.layout.opensAlternatesPopup
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import kotlin.math.abs
import kotlin.math.max

/**
 * The D-pad ring over the keys themselves: where a television remote is
 * pointing, and what its centre button will type.
 *
 * The keyboard's own window is never focusable — the platform gives an IME
 * `FLAG_NOT_FOCUSABLE` so that the app's text field keeps the cursor — so
 * Compose's own focus traversal can never reach a key, whatever the keys are
 * marked as. Arrow keys instead arrive at `InputMethodService.onKeyDown`, which
 * is service code with no idea where anything is drawn.
 *
 * So the geometry travels *up*, exactly as it does for
 * [PanelFocusController]: the grid publishes the table it is already keeping
 * for the glide decoder and the modifier drag ([KeyRects] — every key's cell,
 * written by `onGloballyPositioned`), the service moves a rectangle through it,
 * and the ring below draws whichever cell that landed on. Nothing about the key
 * composables changes, which is why a phone pays nothing for this: the table is
 * built either way, and the ring is only composed where the setting is on.
 *
 * Movement is geometric rather than row/column indexed, because the drawn grid
 * is not a grid: rows are different lengths, a spacebar is five keys wide, split
 * mode cuts every row in two with a gap down the middle, and an ambiguous layout
 * draws bands. The nearest cell in the direction travelled is the one answer
 * that is right for all of them.
 */
internal class KeyGridFocus {

    /**
     * The ringed cell in root coordinates, or null when the ring is down. Held
     * as snapshot state and read inside a draw lambda, so moving the ring
     * repaints the board without recomposing a single key.
     */
    val cell = mutableStateOf<Rect?>(null)

    /** What pressing the centre button does where the ring is standing. */
    private var focused: (() -> Unit)? = null

    /** The key under the ring, or null when it is on a chip or a tool button. */
    private var focusedKey: Key? = null

    /**
     * The key whose alternates a held centre button has opened, with the cell
     * to hang the popup off; null when no popup is up. Snapshot state: the
     * frame composes the popup from it.
     */
    val alternates = mutableStateOf<RingAlternates?>(null)

    /**
     * The popup's own selection and geometry, driven from the arrow keys here
     * rather than from a finger. The same holder a touch long-press uses, so
     * both routes highlight and commit through one path.
     */
    val hold = AlternatesHold()

    /** Whether a held centre button has a popup open over the ringed key. */
    val alternatesOpen: Boolean get() = alternates.value != null

    private var rects: KeyRects? = null
    private var typeKey: ((Key) -> Unit)? = null

    /**
     * Everything ringable that is not a key: the toolbar's buttons and the
     * suggestion chips, which are ordinary composables with no rect table of
     * their own. Each registers itself with [putTarget] while it is on screen
     * (see [Modifier.dpadTarget]) and drops out again when it leaves.
     *
     * A LinkedHashMap, so the ring's tie-breaks are decided by the order the
     * screen reported its targets — the same left-to-right rule the key grid
     * gets for free.
     */
    private val targets = LinkedHashMap<Any, FocusCell>()

    /** Whether the ring is up. */
    val showing: Boolean get() = cell.value != null

    /**
     * The key grid says where its keys are and what to do with one. Called from
     * a `SideEffect` on every recomposition of the key rows; the last writer
     * wins, the way [PanelFocusController.publish] works.
     */
    fun publish(rects: KeyRects, typeKey: (Key) -> Unit) {
        this.rects = rects
        this.typeKey = typeKey
    }

    /** A toolbar button or a suggestion chip, while it is drawn. */
    fun putTarget(id: Any, rect: Rect, activate: () -> Unit) {
        targets[id] = FocusCell(rect, activate)
    }

    /** …and the same one leaving the screen. */
    fun removeTarget(id: Any) {
        targets.remove(id)
    }

    /**
     * Puts the ring up without moving it, on the key nearest the middle of the
     * bottom row: the shortest trip to the spacebar, Enter and the layer keys,
     * which is where a remote spends its time. False when there is nothing on
     * screen to ring yet.
     */
    fun show(): Boolean {
        if (showing) return true
        val cells = cells() ?: return false
        // The keys only. Seeding onto a suggestion chip would put the ring
        // somewhere that empties itself a keystroke later.
        val keys = cells.filter { it.isKey }.ifEmpty { cells }
        val bottom = keys.maxOf { it.rect.bottom }
        val bottomRow = keys.filter { it.rect.bottom > bottom - it.rect.height / 2f }
        val middle = (keys.minOf { it.rect.left } + keys.maxOf { it.rect.right }) / 2f
        val seed = bottomRow.minByOrNull { abs(it.rect.center.x - middle) } ?: return false
        take(seed)
        return true
    }

    /**
     * One step of the D-pad. False when nothing lies that way, which is how an
     * up-press off the top row leaves the keyboard: unconsumed, the event
     * reaches the app, whose own focus moves on as it would from any other
     * view.
     */
    fun move(dx: Int, dy: Int): Boolean {
        val cells = cells() ?: return false
        val from = current(cells) ?: return show()
        val next = nextKeyCell(cells.map { it.rect }, from, dx, dy) ?: return false
        val target = cells.firstOrNull { it.rect == next } ?: return false
        take(target)
        return true
    }

    /** Presses what the ring is on. False when the ring is down. */
    fun press(): Boolean {
        val run = focused ?: return false
        run()
        return true
    }

    /** Takes the ring down: the board is going away, or a finger has taken over. */
    fun clear() {
        cancelAlternates()
        cell.value = null
        focused = null
        focusedKey = null
    }

    /** Forgets the screen as well — a new input session gets a fresh ring. */
    fun reset() {
        clear()
        rects = null
        typeKey = null
        targets.clear()
    }

    private fun take(target: FocusCell) {
        cell.value = target.rect
        focused = target.activate
        focusedKey = target.key
    }

    /** Whether holding the centre button on the ringed key should repeat it (#231). */
    fun repeatsOnHold(): Boolean = focusedKey?.holdRepeats() == true

    /**
     * Opens the ringed key's alternates, the way a long press does — the only
     * route a remote has to an accented character on a layout that keeps them
     * in a popup. False when the ring is on something with no popup to open,
     * which leaves a held button doing nothing rather than typing twice.
     */
    fun openAlternates(): Boolean {
        if (alternatesOpen) return true
        val key = focusedKey ?: return false
        val at = cell.value ?: return false
        if (!key.opensAlternatesPopup() || key.alternateEntries().isEmpty()) return false
        hold.open()
        alternates.value = RingAlternates(key, at)
        return true
    }

    /**
     * One arrow key inside the open popup.
     *
     * The entries' own rectangles are what the popup already publishes for a
     * hold-drag ([AlternatesHold.rects]), so the ring walks them with the same
     * geometry it walks the keys with — wrapping along a row included. A
     * down-press with nothing below closes the popup and types nothing: on a
     * touch keyboard sliding back down to the key is the cancel, and this is
     * that gesture with a D-pad.
     */
    fun moveAlternates(dx: Int, dy: Int): Boolean {
        if (!alternatesOpen) return false
        val rects = hold.rects
        val index = hold.selected.intValue
        if (rects.isEmpty() || index !in rects.indices) {
            // Opened but not yet placed: step the selection by hand so the
            // first press after the popup appears is never swallowed.
            hold.selected.intValue = (index + dx).coerceAtLeast(0)
            return true
        }
        val next = nextKeyCell(rects, rects[index], dx, dy)
        if (next == null) {
            if (dy > 0) {
                cancelAlternates()
                return true
            }
            return false
        }
        hold.selected.intValue = rects.indexOf(next)
        return true
    }

    /**
     * One arrow key, wherever the ring currently lives: inside the open
     * alternates popup, or over the board. One call so the service's key
     * handler reads as the four directions it is, rather than as eight
     * branches that must not disagree about which layer owns the arrows.
     */
    fun step(inPopup: Boolean, dx: Int, dy: Int): Boolean =
        if (inPopup) moveAlternates(dx, dy) else move(dx, dy)

    /** Types the highlighted alternate and closes the popup. */
    fun commitAlternates(): Boolean {
        if (!alternatesOpen) return false
        // `commit` reads the selection, clears it and calls the popup's own
        // onCommit — the same call a lifted finger makes.
        hold.commit()
        alternates.value = null
        return true
    }

    /** Closes the popup without typing anything. */
    fun cancelAlternates(): Boolean {
        if (!alternatesOpen) return false
        hold.cancel()
        alternates.value = null
        return true
    }

    /**
     * Everything the ring can stand on, keys first so that a tie between a key
     * and a chip resolves the way the board reads.
     */
    private fun cells(): List<FocusCell>? {
        val type = typeKey
        val keys = if (type == null) {
            emptyList()
        } else {
            rects?.focusCells().orEmpty().map { (rect, key) ->
                FocusCell(rect, { type(key) }, key)
            }
        }
        return (keys + targets.values).takeIf { it.isNotEmpty() }
    }

    /**
     * Where the ring is *now*, against the screen as it stands.
     *
     * Both tables move under it: the key grid is rebuilt whenever the layer
     * changes (pressing `?123` is the ordinary case) and the suggestion chips
     * are replaced on every keystroke. The nearest cell to where the ring was
     * is then the honest answer — the ring stays where the eye left it while
     * the new contents arrive under it.
     */
    private fun current(cells: List<FocusCell>): Rect? {
        val at = cell.value ?: return null
        cells.firstOrNull { it.rect == at }?.let {
            focused = it.activate
            return it.rect
        }
        val nearest = cells.minByOrNull { (it.rect.center - at.center).getDistanceSquared() }
            ?: return null
        take(nearest)
        return nearest.rect
    }
}

/** Somewhere the ring can stand: where it is, and what pressing it does. */
private class FocusCell(
    val rect: Rect,
    val activate: () -> Unit,
    /**
     * The key this cell draws, or null for a toolbar button or a suggestion
     * chip. Carried so a held centre button can open the key's own alternates,
     * which is the remote's only route to an accented character.
     */
    val key: Key? = null,
) {
    /** Keys are what the ring seeds onto, and what it prefers in a tie. */
    val isKey: Boolean get() = key != null
}

/**
 * Whether the D-pad ring is on, for the surfaces that are not the key grid.
 *
 * A composition local rather than a parameter because the two places that need
 * it — the toolbar row and the suggestion chips — sit several composables below
 * the settings object, and both are on the keystroke path where an extra
 * parameter costs a skip. Static, so reading it is free and only flipping the
 * setting recomposes anything.
 */
internal val LocalDpadRing = staticCompositionLocalOf { false }

/**
 * Registers one toolbar button, suggestion chip or other non-key control with
 * the D-pad ring, for as long as it is on screen.
 *
 * Call sites guard with [LocalDpadRing] themselves —
 * `if (ring) Modifier.dpadTarget(…) else Modifier` — so a board with the ring
 * off never calls this at all, and every phone pays exactly one boolean read
 * per strip rather than a composable per chip.
 */
@Composable
internal fun Modifier.dpadTarget(id: Any, onActivate: () -> Unit): Modifier {
    val focus = LocalPanelFocus.current.keyGrid
    // The latest lambda, without re-registering: a suggestion chip's action
    // closes over the word it is showing, and that changes on every keystroke.
    val activate = rememberUpdatedState(onActivate)
    DisposableEffect(focus, id) { onDispose { focus.removeTarget(id) } }
    return onGloballyPositioned { focus.putTarget(id, it.boundsInRoot()) { activate.value() } }
}

/** A long press the ring is holding open: whose alternates, and over which cell. */
internal class RingAlternates(val key: Key, val cell: Rect)

/** The two toolbar buttons that are not tools, as ring ids. */
internal const val BarBackRingId = "wm.bar.back"
internal const val BarToolboxRingId = "wm.bar.toolbox"

/** A suggestion slot, as a ring id. By slot, because the words change under it. */
internal fun SuggestionRingId(slot: Int): Any = "wm.suggestion.$slot"

/**
 * The cell one D-pad step [dx]/[dy] away from [from], or null when the travel
 * leaves the board.
 *
 * Candidates are the cells whose centre lies beyond [from]'s along the axis
 * travelled. Among those, the nearest wins, with sideways drift counted double:
 * from `g`, a press of down should reach `b` rather than the spacebar it also
 * overlaps, and doubling the cross-axis term is what makes "roughly straight
 * ahead" beat "slightly nearer, well off to one side".
 *
 * Horizontal travel wraps within the row instead of returning null. An IME
 * window takes no focus, so an unconsumed arrow key goes to the app behind it —
 * which for left and right, in the middle of typing, means the app quietly
 * moving its own selection while the user is spelling a word. Up and down do
 * fall through deliberately: leaving the board upward is how a remote gets back
 * to the field it is typing into.
 */
internal fun nextKeyCell(cells: List<Rect>, from: Rect, dx: Int, dy: Int): Rect? {
    if (dx == 0 && dy == 0) return null
    val horizontal = dx != 0
    val forward: (Rect) -> Float =
        if (horizontal) {
            { (it.center.x - from.center.x) * dx }
        } else {
            { (it.center.y - from.center.y) * dy }
        }
    // Half a cell of slack, so two keys of different heights in the same row —
    // a tall Enter beside the punctuation keys — do not read as being above one
    // another.
    val slack = if (horizontal) from.width / 2f else from.height / 2f
    val ahead = cells.filter { it != from && forward(it) > slack }
    if (ahead.isNotEmpty()) {
        return ahead.minByOrNull { candidate ->
            val along = forward(candidate)
            val across = if (horizontal) {
                gap(from.top, from.bottom, candidate.top, candidate.bottom)
            } else {
                gap(from.left, from.right, candidate.left, candidate.right)
            }
            // Centres break the ties that spans leave: a staggered row puts two
            // keys equally under the one above them, and on a board where the
            // rows line up the nearer centre is also the one under the eye. The
            // quarter weight keeps it a tie-break rather than a second opinion.
            val drift = if (horizontal) {
                abs(candidate.center.y - from.center.y)
            } else {
                abs(candidate.center.x - from.center.x)
            }
            along + across * 2f + drift / 4f
        }
    }
    if (!horizontal) return null
    // Wrap: the far end of the row the ring is already on — the cells whose
    // centre line falls inside this one's, which is the question "same row?"
    // asked in a way that a row merely *touching* this one cannot answer yes to.
    val row = cells.filter {
        it != from && it.center.y > from.top && it.center.y < from.bottom
    }
    return row.maxByOrNull { (from.center.x - it.center.x) * dx }
}

/** How far apart two spans are; 0 when they overlap at all. */
private fun gap(aStart: Float, aEnd: Float, bStart: Float, bEnd: Float): Float =
    max(0f, max(bStart - aEnd, aStart - bEnd))

/**
 * The ring itself, drawn over the keys the way the layer peek draws its
 * highlight: one canvas across the grid, reading the rectangle inside the draw
 * lambda so a move repaints and never recomposes.
 *
 * [origin] is the grid box's own position, because [KeyRects] files its cells in
 * root coordinates and this canvas is laid out inside the box — the same
 * conversion `detectLayerPeek` does for its highlight.
 */
@Composable
internal fun BoxScope.KeyFocusRing(
    focus: KeyGridFocus,
    settings: KeyboardSettings,
    origin: () -> Offset,
) {
    val kbTheme = LocalKbTheme.current
    val gapH = keyGapH(settings)
    val gapV = keyGapV(settings)
    val faceShape = kbTheme.keyShape(bleedDp = gapH.value)
    Canvas(modifier = Modifier.matchParentSize()) {
        val cell = focus.cell.value?.translate(-origin()) ?: return@Canvas
        // The drawn face rather than the touch cell, so the ring sits on the key
        // instead of in the gap around it.
        val face = Size(cell.width - gapH.toPx() * 2, cell.height - gapV.toPx() * 2)
        if (face.width <= 0f || face.height <= 0f) return@Canvas
        val outline = faceShape.createOutline(face, layoutDirection, this)
        translate(cell.left + gapH.toPx(), cell.top + gapV.toPx()) {
            // Fill *and* outline, for the same reason [Modifier.focusRing] uses
            // both: a bare accent border already means "this is the current
            // one" on several panels, and a key drawn only in outline would be
            // saying two things at once.
            drawOutline(outline, kbTheme.accent, alpha = 0.16f)
            drawOutline(outline, kbTheme.accent, style = Stroke(width = RingStrokeDp * density))
        }
    }
}

/** 2 dp, matching [Modifier.focusRing]'s border in the panels. */
private const val RingStrokeDp = 2f
