package com.wasimaster.wmkeyboard.app

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// The scroll rail
// ---------------------------------------------------------------------------

/**
 * The strip down the right edge of a capped list that says the list goes on.
 *
 * A dialog that stops its list at 380 dp scrolls, and nothing on the screen
 * said so: the last row it could fit read as the last row there was. The key
 * action picker holds 31 actions in 7 groups, and someone filed issue #202
 * after reading its first 3 as all of them. Compose draws no scrollbar of its
 * own, so the list draws one.
 *
 * The rail is more than a scrollbar. Its track is cut into one segment per
 * group, each as tall as its share of the list, so the strip is also a table
 * of contents: 7 segments say "7 groups" before a finger touches anything.
 * The segment under the viewport is lit, the thumb rides over it, and dragging
 * the thumb scrubs the list with the group name beside it. The bottom edge of
 * the list fades into the dialog while there is more below, which is the part
 * you see without looking for it.
 *
 * On open the list also runs down a few dp and back, once. A still rail is
 * furniture; a list that moves is an invitation. The peek is skipped when the
 * device has its animations turned off.
 *
 * Use it for a list that is capped, not for a screen: a screen already scrolls
 * a whole page, and the top bar collapsing says so.
 */
@Composable
internal fun ScrollRail(
    state: ScrollRailState,
    modifier: Modifier = Modifier,
    /** What the edges fade into. The dialog container, where this is used. */
    fadeColor: Color = AlertDialogDefaults.containerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    ScrollRailPeek(state)
    val sections by remember(state) {
        derivedStateOf {
            state.sectionTops.entries.sortedBy { it.value }.map { RailSection(it.key, it.value) }
        }
    }
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = RailGutter)
                // Drawn here, outside the scroll, so the gradient sits over the
                // viewport rather than travelling with the content. Reading the
                // scroll position in the draw phase and not in composition
                // keeps a long list off the recomposer while it moves.
                .drawWithContent {
                    drawContent()
                    val fade = FadeHeight.toPx()
                    val above = (state.scroll.value / fade).coerceIn(0f, 1f)
                    val below =
                        ((state.scroll.maxValue - state.scroll.value) / fade).coerceIn(0f, 1f)
                    if (above > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(fadeColor.copy(alpha = above), Color.Transparent),
                                startY = 0f,
                                endY = fade,
                            ),
                            size = Size(size.width, fade),
                        )
                    }
                    if (below > 0f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, fadeColor.copy(alpha = below)),
                                startY = size.height - fade,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, size.height - fade),
                            size = Size(size.width, fade),
                        )
                    }
                }
                .onSizeChanged { state.viewportPx = it.height }
                .onGloballyPositioned { state.viewportCoords = it }
                .verticalScroll(state.scroll),
            content = content,
        )
        if (state.scrollable) ScrollRailTrack(state, sections)
    }
}

/**
 * Marks a heading inside a [ScrollRail] as the start of a group, which gives
 * the rail one segment and one name. A group with no heading needs no call:
 * the rail falls back to a single track.
 *
 * Labels are the segment's identity, so two groups with the same name in one
 * list collapse into one segment.
 */
internal fun Modifier.railSection(state: ScrollRailState, label: String): Modifier =
    this.onGloballyPositioned { coords ->
        val viewport = state.viewportCoords ?: return@onGloballyPositioned
        if (!coords.isAttached || !viewport.isAttached) return@onGloballyPositioned
        // Where the heading sits in the window, plus how far the window has
        // travelled: the offset from the top of the list, whatever the scroll.
        val top = viewport.localPositionOf(coords, Offset.Zero).y + state.scroll.value
        state.sectionTops[label] = top.roundToInt().coerceAtLeast(0)
    }

@Composable
internal fun rememberScrollRailState(): ScrollRailState {
    val scroll = rememberScrollState()
    return remember(scroll) { ScrollRailState(scroll) }
}

/** What a [ScrollRail] knows about the list it is drawn beside. */
@Stable
internal class ScrollRailState(val scroll: ScrollState) {
    /** The window the list is read through, in pixels. */
    internal var viewportPx by mutableIntStateOf(0)

    /** The window's own node, which [railSection] measures headings against. */
    internal var viewportCoords: LayoutCoordinates? = null

    /** Group name to its offset from the top of the list, in pixels. */
    internal val sectionTops = mutableStateMapOf<String, Int>()

    /**
     * Whether the opening peek has run. A plain field rather than state: it is
     * read once by the effect that runs it, and a snapshot write here would
     * recompose the list for nothing.
     */
    internal var peeked = false

    /** The whole list, viewport plus everything below it. */
    internal val contentPx: Int get() = viewportPx + scroll.maxValue

    internal val scrollable: Boolean get() = scroll.maxValue > 0
}

/** One group of the list, as the rail draws it. */
internal data class RailSection(val label: String, val topPx: Int)

/**
 * The one-time peek: the list runs down a little and comes back, so that the
 * first thing the reader learns about it is that it moves.
 *
 * It waits a beat for the dialog to finish arriving, and it gives up the moment
 * the reader is ahead of it, either by scrolling first or by having animations
 * turned off on the device.
 */
@Composable
private fun ScrollRailPeek(state: ScrollRailState) {
    val context = LocalContext.current
    val animated = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
    val peekPx = with(LocalDensity.current) { PeekDistance.toPx() }
    LaunchedEffect(state, state.scrollable) {
        if (state.peeked || !animated || !state.scrollable) return@LaunchedEffect
        state.peeked = true
        delay(PeekDelayMs)
        if (state.scroll.value != 0 || state.scroll.isScrollInProgress) return@LaunchedEffect
        val distance = peekPx.roundToInt().coerceAtMost(state.scroll.maxValue)
        state.scroll.animateScrollTo(distance, tween(PeekOutMs, easing = FastOutSlowInEasing))
        delay(PeekHoldMs)
        state.scroll.animateScrollTo(0, tween(PeekBackMs, easing = FastOutSlowInEasing))
    }
}

/**
 * The track, its segments, the thumb, and the name that follows a drag.
 *
 * Everything positional is read in the draw or the layout phase. The rail
 * repaints while the list moves; it does not recompose, except when the group
 * under the drag changes and the name has to be reworded.
 */
@Composable
private fun BoxScope.ScrollRailTrack(state: ScrollRailState, sections: List<RailSection>) {
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    var trackPx by remember { mutableFloatStateOf(0f) }
    val thumbWidth by animateDpAsState(
        if (dragging) ThumbWidthHeld else ThumbWidth,
        label = "railThumbWidth",
    )
    val trackColor = scheme.onSurfaceVariant.copy(alpha = 0.20f)
    val liveColor = scheme.primary.copy(alpha = 0.32f)
    val thumbColor = scheme.primary.copy(alpha = if (dragging) 0.95f else 0.60f)

    Canvas(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(RailTouchWidth)
            .onSizeChanged { trackPx = it.height.toFloat() }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    val track = trackPx
                    val content = state.contentPx
                    if (track <= 0f || content <= 0) return@rememberDraggableState
                    // A pixel of rail is a whole list's worth of pixels: the
                    // thumb keeps up with the finger rather than the list.
                    scope.launch { state.scroll.scrollBy(delta * content / track) }
                },
                onDragStarted = { dragging = true },
                onDragStopped = { dragging = false },
            )
            // The list beside it is already a scrollable node for TalkBack, and
            // a second one that reads "unlabelled" helps nobody.
            .clearAndSetSemantics {},
    ) {
        val content = state.contentPx.toFloat()
        if (content <= 0f || size.height <= 0f) return@Canvas
        val x = size.width - RailInset.toPx() - (thumbWidth.toPx() / 2f)
        val trackWidth = RailWidth.toPx()
        val gap = SegmentGap.toPx()
        val scrolled = state.scroll.value.toFloat()
        // The group the top of the window is in. It is the one the reader is
        // reading, so it is the one the rail lights up.
        val liveAt = sections.indexOfLast { it.topPx <= scrolled + 1f }

        fun capsule(fromY: Float, toY: Float, color: Color, width: Float) {
            val cap = width / 2f
            val from = fromY + cap
            val to = toY - cap
            if (to <= from) {
                // Too short to draw as a line. A dot still holds the place of a
                // one-row group, which is the point of the segment.
                drawCircle(color, radius = cap, center = Offset(x, (fromY + toY) / 2f))
            } else {
                drawLine(
                    color = color,
                    start = Offset(x, from),
                    end = Offset(x, to),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
        }

        if (sections.isEmpty()) {
            capsule(0f, size.height, trackColor, trackWidth)
        } else {
            sections.forEachIndexed { index, section ->
                val nextTop = sections.getOrNull(index + 1)?.topPx?.toFloat() ?: content
                val top = section.topPx / content * size.height
                val bottom = nextTop / content * size.height
                capsule(
                    fromY = top + gap / 2f,
                    toY = bottom - gap / 2f,
                    color = if (index == liveAt) liveColor else trackColor,
                    width = trackWidth,
                )
            }
        }

        val span = (state.viewportPx / content).coerceIn(ThumbMinSpan, 1f)
        val thumbHeight = span * size.height
        val travel = size.height - thumbHeight
        val at = if (state.scroll.maxValue > 0) scrolled / state.scroll.maxValue else 0f
        val thumbTop = at.coerceIn(0f, 1f) * travel
        capsule(thumbTop, thumbTop + thumbHeight, thumbColor, thumbWidth.toPx())
    }

    val label by remember(state, sections) {
        derivedStateOf { sections.lastOrNull { it.topPx <= state.scroll.value + 1 }?.label }
    }
    val density = LocalDensity.current
    AnimatedVisibility(
        visible = dragging && label != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset {
                // Beside the thumb, and read in the layout phase so that a drag
                // moves the name without recomposing it.
                val content = state.contentPx.toFloat()
                val track = trackPx
                if (content <= 0f || track <= 0f) return@offset IntOffset.Zero
                val span = (state.viewportPx / content).coerceIn(ThumbMinSpan, 1f)
                val thumbHeight = span * track
                val at = if (state.scroll.maxValue > 0) {
                    state.scroll.value.toFloat() / state.scroll.maxValue
                } else {
                    0f
                }
                val centre = at.coerceIn(0f, 1f) * (track - thumbHeight) + thumbHeight / 2f
                val pill = with(density) { PillHeight.toPx() }
                IntOffset(
                    x = -with(density) { RailTouchWidth.toPx() }.roundToInt(),
                    y = (centre - pill / 2f).roundToInt().coerceIn(0, (track - pill).roundToInt()),
                )
            },
    ) {
        Surface(
            color = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
            shape = RoundedCornerShape(PillCorner),
            shadowElevation = PillElevation,
        ) {
            Text(
                text = label.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** The room kept on the right of the list so that no row runs under the rail. */
private val RailGutter = 14.dp
private val RailTouchWidth = 28.dp
private val RailInset = 6.dp
private val RailWidth = 3.dp
private val ThumbWidth = 5.dp
private val ThumbWidthHeld = 7.dp
private val SegmentGap = 4.dp
private val FadeHeight = 24.dp
private val PeekDistance = 28.dp
private val PillHeight = 32.dp
private val PillCorner = 50.dp
private val PillElevation = 3.dp

/** A thumb below this reads as a speck, whatever the list's length says. */
private const val ThumbMinSpan = 0.10f
private const val PeekDelayMs = 420L
private const val PeekOutMs = 260
private const val PeekHoldMs = 90L
private const val PeekBackMs = 340
