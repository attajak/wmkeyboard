package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.prediction.OctopusWord
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OctopusPlacement
import kotlin.math.roundToInt

/**
 * One floating word and the rectangle it occupies, both in the key grid's own
 * space.
 *
 * [area] is where it draws, which on the top row reaches above the grid; [hit]
 * is the part of that a finger can actually reach, which is [area] clipped to
 * the board. The two differ only on the top row, and that is the whole of the
 * compromise there: the word is drawn where the eye expects it and tapped where
 * the grid still receives touches.
 */
@Immutable
internal data class OctopusSlot(
    val word: OctopusWord,
    val area: Rect,
    val hit: Rect,
    /** How much the word had to shrink to fit its space; 1 is full size. */
    val scale: Float = 1f,
)

/**
 * Where each word draws, given the keys' measured cells.
 *
 * Pure geometry, so the whole of the layout's behaviour — the placements, the
 * overhang, the overlap rule, the top row — is checkable without a keyboard.
 * [widthOf] measures a word at the size it will be drawn; it is a parameter for
 * the same reason, and because it is the only thing here that needs a font.
 *
 * A key that has not reported its bounds yet yields no slot rather than a slot
 * at the origin.
 */
internal fun octopusSlots(
    words: Collection<OctopusWord>,
    bounds: Map<Int, Rect>,
    placement: OctopusPlacement,
    bandHeightPx: Float,
    /**
     * How much of the band sits above the key's top edge, 0..1. Not 1: the word
     * straddles its key rather than clearing it, which is what both the Z10 and
     * the Octopus tweak drew. On the top row the band is pulled down to the
     * board's edge whatever this says (#171) — a band above the grid is
     * outside every touch the grid gets.
     */
    straddle: Float,
    gapVPx: Float,
    maxOverhangPx: Float,
    boardSize: Size,
    /** Smallest a word may be shrunk before it is dropped instead. */
    minScale: Float,
    widthOf: (String) -> Float,
): List<OctopusSlot> {
    if (bounds.isEmpty() || bandHeightPx <= 0f) return emptyList()
    val kept = ArrayList<OctopusSlot>(words.size)
    for (word in words.sortedBy { it.rank }) {
        val cell = bounds[word.keyCodePoint] ?: continue
        val full = widthOf(word.word)
        if (full <= 0f) continue
        // A word leans into the space either side of its key — the Z10 and the
        // Octopus tweak both let a long word run over its neighbours, and a
        // word that may only be as wide as one key is a word that disappears
        // the moment it has eight letters. "Downloaded" over a 36dp key is the
        // case that found this: it was simply dropped, so the board looked
        // empty exactly when the strip had most to offer.
        //
        // Past that it shrinks rather than vanishing, down to [minScale]. Only
        // a word that still will not fit at its smallest is given up on.
        val allowed = cell.width + maxOverhangPx * 2
        val scale = (allowed / full).coerceAtMost(1f)
        if (scale < minScale) continue
        val text = full * scale
        val top = when (placement) {
            // STRIP shares FLOAT's arithmetic exactly: the reserved lane moved
            // the cell down, so "the band above the cell" is already the lane.
            //
            // Held inside the board (#171). On the top row the straddle would
            // put the upper part of the band over the suggestion strip, and
            // the strip is a sibling of the grid, not something drawn over it:
            // a finger landing there is the strip's, and the strip commits its
            // own word. Hanging the band there looked like the reference
            // boards and lost the tap that is the whole point of the word, so
            // the top row's words sit a little lower than the rest instead.
            OctopusPlacement.FLOAT, OctopusPlacement.STRIP ->
                (cell.top - bandHeightPx * straddle.coerceIn(0f, 1f)).coerceAtLeast(0f)
            OctopusPlacement.IN_KEY -> cell.top + gapVPx
        }
        val centre = cell.center.x
        var left = centre - text / 2
        var right = centre + text / 2
        // Pulled inside the board rather than allowed to hang off it: nothing
        // out there can be read, let alone pressed.
        if (left < 0f) {
            right -= left
            left = 0f
        }
        if (right > boardSize.width) {
            left -= right - boardSize.width
            right = boardSize.width
        }
        val area = Rect(left.coerceAtLeast(0f), top, right, top + bandHeightPx)
        // Walked in rank order, so the better word keeps its place and the
        // worse one goes. Shrinking or shifting the loser would leave a word
        // that no longer points at its own key, which is the one thing the
        // whole feature must not do.
        // A little overlap is what the reference boards look like; a lot is
        // unreadable. Two words may touch, and the worse one goes only when it
        // would take a real bite out of the better one.
        if (kept.none { it.area.overlaps(area) && overlapFraction(it.area, area) > OctopusMaxOverlap }) {
            val hitTop = area.top.coerceIn(0f, boardSize.height)
            val hitBottom = area.bottom.coerceIn(0f, boardSize.height)
            kept.add(
                OctopusSlot(
                    word = word,
                    scale = scale,
                    area = area,
                    // Only the part still over the board can be tapped. Since
                    // the band is held inside the board above, that is the
                    // whole word on every row; the clamp stays as the last
                    // word on what a grid-space touch can reach.
                    hit = if (hitBottom > hitTop) {
                        Rect(area.left, hitTop, area.right, hitBottom)
                    } else {
                        Rect.Zero
                    },
                )
            )
        }
    }
    return kept
}

/**
 * The floating words, drawn over the keys (discussion #102).
 *
 * A sibling of the key grid rather than something the keys draw, for the reason
 * [AutopilotOverlay] states and [KeyVisual] enforces: a key that read the live
 * prediction would cost the whole board its per-keystroke recomposition skip.
 * The live maps are read here, in this leaf's own scope, so a key reporting its
 * position invalidates the overlay and nothing else.
 *
 * Not a [androidx.compose.ui.window.Popup], deliberately, unlike the glide
 * overlay and the key preview. Those exist only while a finger is down; this
 * one would stand for the whole session, and a window over the host app is
 * exactly what the untrusted-touch rules punish. Drawn in the grid instead, it
 * costs no window, it is hit-tested in the grid's own coordinates, and the
 * accessibility carve-out — which is the grid, not the headroom — covers all of
 * it rather than half.
 *
 * Nothing here takes a touch: plain boxes and text, so every press falls
 * through to the key underneath. The taps are resolved from [rects] by the
 * grid's own pointer loop.
 */
@Composable
internal fun BoxScope.OctopusOverlay(
    words: Map<Int, OctopusWord>,
    bounds: Map<Int, Rect>,
    /** One key's width, the unit a word's allowed overhang is measured in. */
    keyWidth: Float,
    /** The key grid's own size; nothing is tapped past it. */
    boardSize: Size,
    /** A glide stroke owns the board, and the picker may be asking already. */
    hidden: Boolean,
    /**
     * The word the keyboard is promising to type, drawn whole in [promiseColor]
     * rather than split into typed head and offered tail. Null on every surface
     * but the widest setting of
     * [com.wasimaster.wmkeyboard.core.settings.GlideCommitColorScope].
     */
    promised: String? = null,
    /** The strip's own primary colour, or null when the user has set none. */
    promiseColor: Color? = null,
    settings: KeyboardSettings,
    palette: KeyPalette,
    kb: KbTheme,
    rects: OctopusRects,
) {
    val octopus = settings.octopus
    if (hidden || words.isEmpty() || bounds.isEmpty() || boardSize.width <= 0f) {
        rects.publish(emptyList(), bounds)
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val fontSize = OctopusLabelSp * settings.fontScale * octopus.fontScale
    val style = hintTextStyle().copy(fontSize = fontSize.sp)
    // Measured, not guessed. The band used to be a multiple of the font size,
    // and the box is sized to exactly that — so a glyph taller than the guess
    // was clipped by its own container. Only the ones that hang below the
    // baseline showed it: "Job" was whole and "job" lost the tail of its j.
    // `includeFontPadding = false`, which the hint style sets, is what makes it
    // bite — it takes away the leading that would otherwise have hidden the
    // descender. Asking the font for a string with both a tall letter and a
    // deep one gives the height the glyphs actually need.
    val bandHeightPx = measurer.measure(OctopusMetricProbe, style, maxLines = 1)
        .size.height.toFloat()
    val gapVPx = with(density) { keyGapV(settings).toPx() }
    val maxOverhangPx = keyWidth * OctopusOverhangWidths
    val slots = octopusSlots(
        words = words.values,
        bounds = bounds,
        placement = octopus.placement,
        bandHeightPx = bandHeightPx,
        straddle = OctopusStraddle,
        gapVPx = gapVPx,
        maxOverhangPx = maxOverhangPx,
        minScale = OctopusMinScale,
        boardSize = boardSize,
        widthOf = { measurer.measure(it, style, maxLines = 1).size.width.toFloat() },
    )
    // Stamped with the very table the rectangles were measured from, so a
    // layout change — which hands the grid a fresh one — reads as nothing until
    // this has run again against it.
    rects.publish(slots, bounds)
    val head = palette.keyText.copy(alpha = OctopusHeadAlpha)
    for (slot in slots) {
        val area = slot.area
        Box(
            modifier = Modifier
                // The key underneath is what a screen reader reads; this word
                // reaches TalkBack through that key's own custom action, so a
                // node here would only offer a target it could never activate.
                .clearAndSetSemantics { }
                // Declared after the key grid, so it already draws over it —
                // but stated rather than inherited from declaration order,
                // because a word half-swallowed by the key under it is the one
                // failure this layer cannot recover from, and z-order is
                // exactly the kind of thing a later refactor moves by accident.
                .zIndex(1f)
                .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                .size(
                    width = with(density) { area.width.toDp() },
                    height = with(density) { area.height.toDp() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = octopusLabel(slot.word, head, kb.accent, promised, promiseColor),
                // Shrunk to fit where a long word would otherwise not have
                // been drawn at all.
                style = if (slot.scale == 1f) style else style.copy(fontSize = (fontSize * slot.scale).sp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** How much of the narrower of two words the wider may cover before one goes. */
private fun overlapFraction(a: Rect, b: Rect): Float {
    val overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
    if (overlap <= 0f) return 0f
    return overlap / minOf(a.width, b.width).coerceAtLeast(1f)
}

/**
 * The word in two tones, split where what the user has typed ends.
 *
 * The head is faded rather than absent: it says "you have written this much",
 * which is what makes the tail read as an offer instead of a whole new word.
 * A correction has no head worth claiming — the point of it is that what was
 * typed was wrong — so it is drawn in the accent throughout, and that is what
 * separates a fix from a continuation at a glance.
 */
internal fun octopusLabel(
    word: OctopusWord,
    head: Color,
    accent: Color,
    promised: String? = null,
    promiseColor: Color? = null,
): AnnotatedString {
    // The promised word is drawn whole in the strip's colour rather than split.
    // The split says "this much is already yours", and none of a glided word is
    // yours until the finger comes up.
    if (promiseColor != null && word.word.equals(promised, ignoreCase = true)) {
        return buildAnnotatedString {
            withStyle(SpanStyle(color = promiseColor)) { append(word.word) }
        }
    }
    // Coerced rather than trusted: a candidate and a buffer that have drifted
    // apart must not throw in the middle of a draw.
    val split = if (word.kind == OctopusKind.CORRECTION) {
        0
    } else {
        word.typedChars.coerceIn(0, word.word.length)
    }
    return buildAnnotatedString {
        if (split > 0) {
            withStyle(SpanStyle(color = head)) { append(word.word.substring(0, split)) }
        }
        if (split < word.word.length) {
            withStyle(SpanStyle(color = accent)) { append(word.word.substring(split)) }
        }
    }
}

/**
 * Base size of a floating word, before the board's own font scale and the
 * user's octopus size. Well above the corner hint's 8.5sp: a hint is one
 * character read off a key already under the eye, and these are whole words
 * read across a board at a glance.
 */
private const val OctopusLabelSp = 12.6f

/**
 * Measured to size the band a word is drawn in: a capital, a letter with a
 * descender and one with both, so the height covers everything a word can
 * contain rather than what a multiple of the font size guesses it might.
 */
private const val OctopusMetricProbe = "Xjgy"

/**
 * How much of that band clears the key. The rest overlaps it, so the word reads
 * as belonging to the key rather than floating between two rows — and so the top
 * row, whose band would otherwise be entirely off the grid, keeps a strip of
 * itself inside the only area that receives touches.
 *
 * Half, rather than the three quarters it started at: reaching further up put
 * the word well inside the cell of the row above, where it competed with that
 * row's key for the same few pixels.
 */
private const val OctopusStraddle = 0.5f

/**
 * How far a word may lean past its own key, as a multiple of the key's width.
 * Generous on purpose: the boards this copies let a long word run right over
 * its neighbours, and the alternative is that every word of eight letters or
 * more silently disappears.
 */
private const val OctopusOverhangWidths = 1f

/** The smallest a word may be drawn before it is dropped instead. */
private const val OctopusMinScale = 0.72f

/** How much of the narrower word two of them may share before one is dropped. */
private const val OctopusMaxOverlap = 0.35f

/** The typed head, faded against the completion it introduces. */
private const val OctopusHeadAlpha = 0.55f

/**
 * Which keys are carrying a word this frame, as one boolean per key.
 *
 * The obvious way to hide a corner hint under a floating word — a flag on
 * [KeyVisual] — is exactly what `KeyVisualTest` forbids: it would put a
 * per-keystroke value in the grid's `remember` key list and re-run all forty
 * key bodies on every keypress, which is the skip that class exists to buy.
 *
 * So the keys read this at *draw* time instead. A flag flipping invalidates
 * that one key's drawing; composition, measurement and layout never hear about
 * it, and in steady state exactly two keys redraw per keystroke — the one that
 * gained a word and the one that lost it.
 *
 * A flag asked for after [set] has run starts out already correct, because a
 * key composing late must not draw a hint under a word that is already there.
 */
internal class OctopusOccupancy {

    private val flags = HashMap<Int, MutableState<Boolean>>()
    private var occupied: Set<Int> = emptySet()

    fun flag(codePoint: Int): State<Boolean> =
        flags.getOrPut(codePoint) { mutableStateOf(codePoint in occupied) }

    fun set(next: Set<Int>) {
        occupied = next
        for ((codePoint, flag) in flags) {
            val wanted = codePoint in next
            // Only the ones that actually changed: writing the same value back
            // would invalidate every hinted key's drawing on every keystroke.
            if (flag.value != wanted) flag.value = wanted
        }
    }
}

/**
 * Static, so a key reading it is not an observable read and the holder never
 * changes identity — the whole point being that nothing recomposes on its
 * account.
 */
internal val LocalOctopusOccupancy = staticCompositionLocalOf { OctopusOccupancy() }
