package com.wasimaster.wmkeyboard.ime.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.wasimaster.wmkeyboard.ime.CaretAnchor
import com.wasimaster.wmkeyboard.ime.CaretDragSource
import com.wasimaster.wmkeyboard.ime.CaretMagnifierState
import com.wasimaster.wmkeyboard.ime.MagnifierLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * The caret magnifier's two ends (discussion #303): the drag surfaces report
 * when they start and stop moving the caret, and the bubble draws whatever the
 * service says is under it. Rides [ToolHoldCallbacks] for the reason
 * [DictionaryBarCallbacks] does: `KeyboardScreen` cannot take another parameter.
 */
@Immutable
class CaretMagnifierSeam(
    val onDrag: (CaretDragSource, Boolean) -> Unit = { _, _ -> },
    val state: StateFlow<CaretMagnifierState?> = MutableStateFlow(null),
)

/**
 * [CaretMagnifierSeam.onDrag], for the spacebar deep in the key grid. A local
 * for the reason [LocalCursorMoveVertical] is one.
 */
internal val LocalCaretDrag = staticCompositionLocalOf<(CaretDragSource, Boolean) -> Unit> { { _, _ -> } }

private val MagnifierWidth = 176.dp
private val MagnifierHeight = 48.dp
private val MagnifierGap = 12.dp
private val MagnifierFontSize = 22.sp

/** Wide enough for the fade to read as the line going on, narrow enough to keep the caret's neighbours. */
private const val MagnifierFadeFraction = 0.18f

/**
 * A window that never takes a touch or the focus. It only exists while a
 * finger is dragging the caret on the keyboard, so it can never sit idle over
 * the app and swallow a tap there.
 */
private val MagnifierPopupProperties = PopupProperties(
    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
)

/**
 * The bubble, composed only while [state] holds something. Collected here and
 * nowhere else, so a drag redraws the bubble and not the keyboard.
 */
@Composable
internal fun CaretMagnifierHost(state: StateFlow<CaretMagnifierState?>) {
    val current by state.collectAsState()
    val magnifier = current ?: return
    val view = LocalView.current
    val density = LocalDensity.current
    // Where this window starts on screen. The editor reports its caret in
    // screen pixels, and a popup is placed in its parent window's.
    val origin = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
    val provider = remember(magnifier.anchor, origin[0], origin[1]) {
        MagnifierPositionProvider(
            anchor = magnifier.anchor,
            windowOrigin = IntOffset(origin[0], origin[1]),
            gapPx = with(density) { MagnifierGap.roundToPx() },
        )
    }
    Popup(popupPositionProvider = provider, properties = MagnifierPopupProperties) {
        MagnifierBubble(magnifier.line)
    }
}

/**
 * Above the caret's line, or below it when the line is too near the top of
 * the screen, centred on the caret and kept on screen. With no caret to go by,
 * centred just above the keyboard.
 */
private class MagnifierPositionProvider(
    private val anchor: CaretAnchor?,
    private val windowOrigin: IntOffset,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        if (anchor == null) {
            return IntOffset(
                anchorBounds.left + (anchorBounds.width - width) / 2,
                anchorBounds.top - height - gapPx,
            )
        }
        val maxX = (windowSize.width - width).coerceAtLeast(0)
        val x = (anchor.x - windowOrigin.x - width / 2f).roundToInt().coerceIn(0, maxX)
        val above = (anchor.top - windowOrigin.y).roundToInt() - gapPx - height
        // A screen-space test: the top of the screen is where the room runs out,
        // wherever this window happens to start.
        val y = if (above + windowOrigin.y >= gapPx) {
            above
        } else {
            (anchor.bottom - windowOrigin.y).roundToInt() + gapPx
        }
        return IntOffset(x, y)
    }
}

/**
 * The magnified line, scrolled so the caret is always in the middle, fading
 * out at both ends. Drawn in the key preview bubble's colours, so it reads as
 * part of the keyboard rather than a piece of the app.
 */
@Composable
private fun MagnifierBubble(line: MagnifierLine) {
    val kb = LocalKbTheme.current
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(color = kb.popupText, fontSize = MagnifierFontSize))
    val shape = RoundedCornerShape(50)
    val textColor = kb.popupText
    Box(
        modifier = Modifier
            .shadow(6.dp, shape, clip = false)
            .background(kb.popup, shape)
            .clip(shape)
            .size(MagnifierWidth, MagnifierHeight)
            // Its own layer, so the fade below cuts into the text alone and
            // leaves the bubble's fill under it whole.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val layout = measurer.measure(
                    AnnotatedString(line.text),
                    style = style,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    maxLines = 1,
                )
                val caretX = layout.getHorizontalPosition(line.caret, usePrimaryDirection = true)
                val shiftX = size.width / 2 - caretX
                val shiftY = (size.height - layout.size.height) / 2
                val selection = if (line.selEnd > line.selStart) {
                    layout.getPathForRange(line.selStart, line.selEnd)
                } else {
                    null
                }
                val caretWidth = 2.dp.toPx()
                val caretTop = shiftY + 2.dp.toPx()
                val caretBottom = shiftY + layout.size.height - 2.dp.toPx()
                val fade = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    MagnifierFadeFraction to Color.Black,
                    1f - MagnifierFadeFraction to Color.Black,
                    1f to Color.Transparent,
                )
                onDrawBehind {
                    translate(shiftX, shiftY) {
                        selection?.let { drawPath(it, textColor.copy(alpha = 0.3f)) }
                        drawText(layout)
                    }
                    drawLine(
                        textColor,
                        Offset(size.width / 2, caretTop),
                        Offset(size.width / 2, caretBottom),
                        strokeWidth = caretWidth,
                    )
                    drawRect(fade, blendMode = BlendMode.DstIn)
                }
            },
    )
}
