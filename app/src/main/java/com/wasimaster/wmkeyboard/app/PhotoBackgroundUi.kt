package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.theme.PhotoAttribution
import com.wasimaster.wmkeyboard.core.tools.PhotoItem
import com.wasimaster.wmkeyboard.core.tools.PhotoLinks
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import com.wasimaster.wmkeyboard.common.R as CommonR

/** Which of a theme's two background slots a photo is headed for. */
enum class BackgroundSlot { PORTRAIT, LANDSCAPE, BOTH;

    /** The route argument, which is data and never a string resource. */
    val arg: String get() = name.lowercase()

    companion object {
        fun of(arg: String?): BackgroundSlot =
            entries.firstOrNull { it.arg == arg } ?: PORTRAIT
    }
}

// ---- routes -----------------------------------------------------------

internal const val PHOTO_HUB_ROUTE = "photos"
internal const val PHOTO_BROWSE_ROUTE = "photo_browse"
internal const val PHOTO_LIBRARY_ROUTE = "photo_library"
internal const val PHOTO_ROTATION_ROUTE = "photo_rotation"

internal fun photoBrowseRoute(themeId: String?, slot: BackgroundSlot = BackgroundSlot.PORTRAIT): String =
    "$PHOTO_BROWSE_ROUTE?theme=${themeId.orEmpty()}&slot=${slot.arg}"

internal fun photoLibraryRoute(themeId: String?, slot: BackgroundSlot = BackgroundSlot.PORTRAIT): String =
    "$PHOTO_LIBRARY_ROUTE?theme=${themeId.orEmpty()}&slot=${slot.arg}"

internal const val PHOTO_DETAIL_ROUTE = "photo_detail"

/**
 * The photo whose page is open.
 *
 * A photo is a dozen fields including several URLs, and route arguments are
 * strings in a URL of their own — so it is handed over here instead of being
 * encoded and parsed back. Process-level rather than a saved-state handle
 * because this app carries no view models; if the process dies on the back
 * stack the page simply reopens empty, which is what the null branch draws.
 */
internal object PhotoSelection {
    @Volatile
    var current: PhotoItem? = null
}

// ---- shared pieces ----------------------------------------------------

/**
 * One photo in the grid.
 *
 * The thumbnail is loaded straight from the service's own CDN rather than
 * copied anywhere first. That is not a shortcut: it is how both services count
 * views and credit the photographer for them.
 *
 * The tile is painted in the photo's average colour before the image arrives,
 * so a grid never flashes grey, and the cell aspect is fixed so it never
 * reflows as images land.
 */
@Composable
internal fun PhotoTile(
    photo: PhotoItem,
    saved: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Applied to the image itself rather than to the tile around it, which is
     * where a flight has to be tagged: the tile fixes its own aspect ratio and
     * a shared element is re-measured at the animated bounds, so a box
     * insisting on its ratio mid-flight would fight them. Empty on a grid that
     * opens nothing.
     */
    imageModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val description = remember(photo.key) {
        val photographer = photo.photographer.ifBlank { "" }
        if (photo.altText.isNotBlank()) {
            context.getString(R.string.photo_tile_desc, photo.altText, photographer)
        } else {
            context.getString(R.string.photo_tile_fallback_desc, photographer)
        }
    }
    Box(
        modifier = modifier
            .aspectRatio(TILE_ASPECT)
            .clip(RoundedCornerShape(12.dp))
            .background(parseHexColor(photo.avgColor) ?: MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { },
    ) {
        AsyncImage(
            model = photo.thumbUrl,
            contentDescription = description,
            imageLoader = rememberMediaImageLoader(),
            modifier = Modifier.matchParentSize().then(imageModifier),
            contentScale = ContentScale.Crop,
        )
        KeyboardBandGuide(Modifier.matchParentSize())
        CreditOverlay(
            photographer = photo.photographer,
            modifier = Modifier.align(Alignment.BottomStart),
        )
        if (saved) {
            Icon(
                Icons.Outlined.Bookmark,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
    }
}

/**
 * Dims the parts of a photo the keyboard crops away, leaving the strip it
 * actually uses bright.
 *
 * Accurate rather than decorative: it matches the cropper's own default
 * aspect, so what stands out in the grid is what ends up behind the keys.
 */
@Composable
internal fun KeyboardBandGuide(modifier: Modifier = Modifier) {
    Box(modifier) {
        // The band is centred, so the two dimmed strips are the same height.
        val scrim = Color.Black.copy(alpha = 0.28f)
        Column(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(BAND_EDGE_WEIGHT)
                    .background(scrim),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(BAND_CENTRE_WEIGHT),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(BAND_EDGE_WEIGHT)
                    .background(scrim),
            )
        }
    }
}

/**
 * The photographer's name over the bottom of a tile.
 *
 * Fixed white on a dark gradient rather than following the app's colours: this
 * sits on a photograph, not on a surface, and has to stay legible over both a
 * snowfield and a night sky. The text is hidden from screen readers because
 * the tile's own description already names the photographer.
 */
@Composable
private fun CreditOverlay(photographer: String, modifier: Modifier = Modifier) {
    if (photographer.isBlank()) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f),
                ),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = photographer,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** The credit line, with the link back both services require. */
@Composable
internal fun PhotoCreditRow(credit: PhotoAttribution, onOpen: (String) -> Unit) {
    val source = PhotoLinks.sourceOf(credit.provider)
    val serviceName = source?.let { stringResource(PhotoLinks.displayNameRes(it)) } ?: credit.provider
    val target = credit.photographerUrl.ifBlank { credit.photoUrl }
    WmRow(
        title = stringResource(R.string.photo_credit_line, credit.photographer, serviceName),
        icon = Icons.AutoMirrored.Outlined.OpenInNew,
        enabled = target.isNotBlank(),
        onClick = {
            // Tagged here rather than where it was stored: the referral slug is
            // a build constant, so a theme that arrived from another build
            // should carry this build's.
            onOpen(source?.let { PhotoLinks.credited(target, it) } ?: target)
        },
    )
}

/** The settings-side equivalent of the keyboard panels' notice card. */
@Composable
internal fun PhotoNotice(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** The footer under an infinite grid: loading, a retry, or the end. */
@Composable
internal fun PhotoGridFooter(
    loadingMore: Boolean,
    endReached: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(
                    stringResource(R.string.photo_loading_more_progress),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(CommonR.string.common_retry))
                }
            }
            endReached -> Text(
                stringResource(R.string.photo_end_of_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Asks for the next page as the grid nears its end.
 *
 * The check runs in a `derivedStateOf` so scrolling does not recompose the
 * screen on every frame. [enabled] must be false while a page is already in
 * flight and after a failure: without that, a failing endpoint is asked again
 * on every fling, which against a fifty-an-hour budget empties it in seconds.
 */
@Composable
internal fun LoadMoreOnScrollEnd(
    state: LazyGridState,
    enabled: Boolean,
    buffer: Int = LOAD_MORE_BUFFER,
    onLoadMore: () -> Unit,
) {
    val atEnd by remember(state, buffer) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - buffer
        }
    }
    LaunchedEffect(atEnd, enabled) {
        if (atEnd && enabled) onLoadMore()
    }
}

/** `#RRGGBB` from a service, or null when it sent something unexpected. */
internal fun parseHexColor(hex: String): Color? {
    val cleaned = hex.removePrefix("#").trim()
    if (cleaned.length != 6) return null
    val value = cleaned.toLongOrNull(radix = 16) ?: return null
    return Color(0xFF000000L.or(value).toInt())
}

/** A keyboard is a wide, short strip, so photos are shown in that shape. */
private const val TILE_ASPECT = 3f / 2f

/** Weights that leave a centred 2.4:1 band, matching the cropper's default. */
private const val BAND_CENTRE_WEIGHT = 1f
private const val BAND_EDGE_WEIGHT = 0.28f

private const val LOAD_MORE_BUFFER = 6
