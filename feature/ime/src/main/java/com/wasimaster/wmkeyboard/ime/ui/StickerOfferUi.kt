package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.settings.StickerSuggestStyle
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.StickerOffer
import com.wasimaster.wmkeyboard.ime.StickerOfferItem

/**
 * What the stickers offered while typing reach (#329). Rides [ToolHoldCallbacks]
 * for the reason its other passengers do: the caller cannot afford another
 * parameter.
 */
data class StickerOfferCallbacks(
    /** A sticker was tapped: send it, taking its trigger back out if the setting says so. */
    val onPick: (StickerOfferItem) -> Unit = {},
    /** The chip or the "more" button: open the tray with every match. */
    val onExpand: () -> Unit = {},
    /** The tray's ✕, or a hold on the strip's thumbnails: not these, not for this text. */
    val onDismiss: () -> Unit = {},
)

/** Height of the tray row: a sticker at a size you can tell apart, and a little air. */
private val StickerTrayHeight = 64.dp
private val StickerTrayCell = 56.dp

/** Thumbnails the strip style shows before the rest wait behind "+N". */
private const val StripThumbs = 3
private val StripThumb = 34.dp

private const val TrayMotionMs = 140

/**
 * Whether the tray is up: for an emoji picked in the emoji panel, while that
 * panel is open; for typed text, on the keys, always in the tray style and in
 * the other two once their chip has opened it.
 */
internal fun stickerTrayShows(state: KeyboardUiState): Boolean {
    val offer = state.stickerOffer ?: return false
    return when {
        offer.inEmojiPanel -> state.panel == PanelMode.EMOJI
        state.panel != PanelMode.NONE -> false
        state.settings.gif.stickerSuggestStyle == StickerSuggestStyle.TRAY -> true
        else -> offer.expanded
    }
}

/** Whether the suggestion strip draws the offer: the two narrow styles, on the keys. */
internal fun stickerStripShows(state: KeyboardUiState): Boolean {
    val offer = state.stickerOffer ?: return false
    return !offer.inEmojiPanel && state.panel == PanelMode.NONE &&
        state.settings.gif.stickerSuggestStyle != StickerSuggestStyle.TRAY
}

/**
 * The tray, as its own row of the bar stack ([com.wasimaster.wmkeyboard.core.settings.BarRow.STICKERS]):
 * on top by default, wherever Rows & bars puts it otherwise. It grows in
 * and out the way the selection macro row does, inside a docked frame that
 * holds still (see [RevealingBarRow]), so a match arriving resizes the window
 * once rather than on every frame of the move.
 *
 * The offer it last drew is kept for the way out: the service drops the offer
 * in the same update that hides the row, and a row that empties as it starts
 * to shrink would collapse in one step instead of sliding away.
 */
@Composable
internal fun ColumnScope.StickerTrayRow(state: KeyboardUiState, callbacks: StickerOfferCallbacks) {
    val visible = stickerTrayShows(state)
    val last = remember { arrayOfNulls<StickerOffer>(1) }
    if (visible) last[0] = state.stickerOffer
    val motion = !state.settings.reduceMotion
    RevealingBarRow(
        visible = visible,
        enter = if (motion) {
            expandVertically(tween(TrayMotionMs)) + fadeIn(tween(TrayMotionMs))
        } else {
            EnterTransition.None
        },
        exit = if (motion) {
            shrinkVertically(tween(TrayMotionMs)) + fadeOut(tween(TrayMotionMs))
        } else {
            ExitTransition.None
        },
    ) {
        last[0]?.let { StickerOfferTray(it, callbacks) }
    }
}

@Composable
private fun StickerOfferTray(offer: StickerOffer, callbacks: StickerOfferCallbacks) {
    val kb = LocalKbTheme.current
    val loader = rememberMediaImageLoader()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StickerTrayHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(offer.stickers, key = { it.item.id }) { pick ->
                StickerThumb(
                    pick = pick,
                    loader = loader,
                    size = StickerTrayCell,
                    onClick = { callbacks.onPick(pick) },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(onClick = callbacks.onDismiss)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_sticker_offer_dismiss_desc, offer.trigger),
                tint = kb.toolbarIcon,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The offer on the suggestion strip, for the two styles that put it there. It
 * shares the row with the word candidates rather than taking it: the word
 * that asked for the stickers may just as well be the start of a sentence.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StickerStripOffer(
    offer: StickerOffer,
    style: StickerSuggestStyle,
    callbacks: StickerOfferCallbacks,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val loader = rememberMediaImageLoader()
    val dismissLabel = stringResource(R.string.ime_sticker_offer_dismiss_desc, offer.trigger)
    if (style == StickerSuggestStyle.STRIP) {
        Row(
            modifier = modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (pick in offer.stickers.take(StripThumbs)) {
                StickerThumb(
                    pick = pick,
                    loader = loader,
                    size = StripThumb,
                    onClick = { callbacks.onPick(pick) },
                    onLongClick = callbacks.onDismiss,
                    longClickLabel = dismissLabel,
                )
            }
            val rest = offer.stickers.size - StripThumbs
            if (rest > 0 && !offer.expanded) {
                OfferPill(onClick = callbacks.onExpand, onLongClick = callbacks.onDismiss, dismissLabel = dismissLabel) {
                    Text(
                        text = stringResource(R.string.ime_sticker_offer_more, rest),
                        color = kb.accent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        return
    }
    OfferPill(
        onClick = callbacks.onExpand,
        onLongClick = callbacks.onDismiss,
        dismissLabel = dismissLabel,
        modifier = modifier,
    ) {
        offer.stickers.firstOrNull()?.let { first ->
            AsyncImage(
                model = first.item.previewUrl,
                contentDescription = null,
                imageLoader = loader,
                modifier = Modifier.size(24.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            text = pluralStringResource(R.plurals.ime_sticker_offer_count, offer.stickers.size, offer.stickers.size),
            color = kb.accent,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** A strip chip in the house style ([StripIconChip]'s tint and border), holding whatever it is given. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OfferPill(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dismissLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val shape = kb.chipShape()
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp, horizontal = 4.dp)
            .clip(shape)
            .background(kb.accent.copy(alpha = if (kb.dark) 0.20f else 0.11f))
            .border(1.dp, kb.accent.copy(alpha = 0.32f), shape)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = dismissLabel,
                onLongClick = {
                    feedback()
                    onLongClick()
                },
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerThumb(
    pick: StickerOfferItem,
    loader: ImageLoader,
    size: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val title = pick.item.title
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = pick.item.previewUrl,
            contentDescription = if (title.isBlank()) {
                stringResource(R.string.ime_sticker_offer_item_untitled_desc)
            } else {
                stringResource(R.string.ime_sticker_offer_item_desc, title)
            },
            imageLoader = loader,
            modifier = Modifier
                .size(size)
                .padding(2.dp),
            contentScale = ContentScale.Fit,
        )
    }
}
