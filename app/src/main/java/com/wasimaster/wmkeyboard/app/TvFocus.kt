package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * What a television remote is pointing at, in the settings app.
 *
 * Every row here is an ordinary `Modifier.clickable`, which is already
 * focusable and already moves with the D-pad — the platform was routing arrow
 * keys to it all along. What it had no way to do was *say so*: the app's
 * indication is a ripple, a ripple is a touch response, and a D-pad press
 * produces no touch. So focus moved down a list of identical-looking rows with
 * nothing on screen changing, which reads exactly like a remote that does not
 * work.
 *
 * Installed as `LocalIndication` for the whole settings app on a television
 * only, so a phone keeps the ripple it has always had — and keeps it unchanged,
 * rather than getting a ripple plus a focus outline it can never show.
 *
 * Drawn as a border with a faint fill, which is the same pair the keyboard's
 * own hardware focus ring uses (`ime/ui/FocusRing.kt`). Two devices, one idea
 * of what "the current one" looks like.
 */
internal class TvFocusIndication(private val color: Color) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvFocusNode(interactionSource, color)

    // IndicationNodeFactory is compared by value: an equal instance must not
    // count as a new indication, or every recomposition that rebuilds the
    // theme's colours would detach and re-create a node per clickable row.
    override fun equals(other: Any?): Boolean = other is TvFocusIndication && other.color == color

    override fun hashCode(): Int = color.hashCode()
}

private class TvFocusNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
) : Modifier.Node(), DrawModifierNode {

    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            // Nested focus counts, so that a row holding a focusable control
            // does not flicker as focus moves between the two: the ring is up
            // while anything inside this node has it.
            var held = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> held++
                    is FocusInteraction.Unfocus -> held--
                    else -> return@collect
                }
                val now = held > 0
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val stroke = FocusStrokeDp.dp.toPx()
        val radius = CornerRadius(FocusRadiusDp.dp.toPx())
        drawRoundRect(color = color.copy(alpha = 0.16f), cornerRadius = radius)
        drawRoundRect(
            color = color,
            cornerRadius = radius,
            // Inset by half the stroke so the outline lands inside the row
            // rather than half-clipped by whatever draws next to it.
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
            size = androidx.compose.ui.geometry.Size(
                size.width - stroke,
                size.height - stroke,
            ),
            style = Stroke(width = stroke),
        )
    }
}

private const val FocusStrokeDp = 2f

/** The settings rows' own corner radius, so the ring follows the card it is on. */
private const val FocusRadiusDp = 16f
