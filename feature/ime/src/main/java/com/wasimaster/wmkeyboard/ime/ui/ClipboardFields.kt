package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntities
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntity
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.clipboard.clipEditable
import com.wasimaster.wmkeyboard.core.clipboard.clipPreviewText
import com.wasimaster.wmkeyboard.core.clipboard.expiresAt
import com.wasimaster.wmkeyboard.core.clipboard.matchesQuery
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.settings.ClipGridColumnsRange
import com.wasimaster.wmkeyboard.core.settings.ClipTimeLabel
import com.wasimaster.wmkeyboard.core.settings.ClipboardSettings
import com.wasimaster.wmkeyboard.core.settings.ClipboardView
import com.wasimaster.wmkeyboard.core.settings.SensitiveClipHandling
import com.wasimaster.wmkeyboard.ime.ClipEdit
import com.wasimaster.wmkeyboard.ime.ClipUndo
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The clipboard panel's components, each in the cell its panel layout gives it
 * (issue #63): the search pill, the grid / list switch, the strip of fragments
 * lifted out of the clips, and the history itself — plus the clip editor,
 * which stands in for all of them while it is open.
 */

/**
 * The panel's own actions that reach the service through [ToolHoldCallbacks]
 * rather than as parameters of their own, because `KeyboardScreen`'s caller
 * sits against the JVM's 64K method-size ceiling.
 */
data class ClipboardPanelActions(
    /** Open this clip in the editor; the keys type into it until it closes. */
    val onEdit: (ClipItem) -> Unit = {},
    /** Save the editor's draft over its clip, and close it. */
    val onEditSave: () -> Unit = {},
    /** Close the editor, leaving the clip as it was. */
    val onEditCancel: () -> Unit = {},
    /** Flip the history between the two-column grid and the one-per-row list. */
    val onViewToggle: () -> Unit = {},
    /** The Undo bar's button: put back the clips just deleted (#327). */
    val onUndoDelete: () -> Unit = {},
)

/** Everything the clipboard components call back into the service with. */
@Immutable
internal class ClipboardFieldCallbacks(
    val onItem: (ClipItem) -> Unit,
    val onSticker: (ClipItem) -> Unit,
    val onPin: (ClipItem) -> Unit,
    val onDelete: (ClipItem) -> Unit,
    val onSearchToggle: () -> Unit,
    val onEntity: (ClipEntity) -> Unit,
    val actions: ClipboardPanelActions,
)

/** The derived views of the history the three cells share. */
@Stable
internal class ClipboardPanelSession(
    val shownItems: List<ClipItem>,
    val entities: List<ClipEntity>,
    /** The search pill is only offered once there is history to filter and the feature is on. */
    val showSearch: Boolean,
    val query: String,
    val gridState: LazyStaggeredGridState,
    /** One clip per row rather than two columns of cards. */
    val list: Boolean,
    /**
     * Each clip's number, by id, when numbering is on — its place in the whole
     * history, not in [shownItems], so a search never renumbers what it finds.
     * Empty when numbering is off.
     */
    val numbers: Map<Long, Int>,
)

@Composable
internal fun rememberClipboardPanelSession(state: KeyboardUiState): ClipboardPanelSession {
    val showSearch = state.settings.clipboard.search && state.clipboardItems.isNotEmpty()
    val query = state.clipboardQuery.trim()
    val shownItems = if (query.isEmpty()) {
        state.clipboardItems
    } else {
        state.clipboardItems.filter { it.matchesQuery(query) }
    }
    // Scanning every clip with three regexes is not free, so it happens once
    // per history change rather than on every recomposition.
    val phoneFormats = state.settings.clipboard.phoneFormats
    val phoneMasks = remember(phoneFormats) { PhoneFormats.parseAll(phoneFormats) }
    val allEntities = if (state.settings.clipboard.detectEntities) {
        remember(state.clipboardItems, phoneMasks) { ClipEntities.entitiesIn(state.clipboardItems, phoneMasks) }
    } else {
        emptyList()
    }
    // While searching the panel is only a couple of rows tall — the keys have
    // taken the rest — so the fragment strip stands down rather than eating one.
    val entities = if (state.clipboardSearchActive) {
        emptyList()
    } else {
        allEntities.filter { query.isEmpty() || it.value.contains(query, ignoreCase = true) }
    }
    val gridState = rememberLazyStaggeredGridState()
    val list = state.settings.clipboard.view == ClipboardView.LIST
    val showNumbers = state.settings.clipboard.showNumbers
    val numbers = remember(state.clipboardItems, showNumbers) {
        if (showNumbers) clipNumbers(state.clipboardItems) else emptyMap()
    }
    return remember(shownItems, entities, showSearch, query, gridState, list, numbers) {
        ClipboardPanelSession(shownItems, entities, showSearch, query, gridState, list, numbers)
    }
}

/** The clipboard component for [kind]; a kind of another panel draws nothing. */
@Composable
internal fun ClipboardField(
    kind: PanelFieldKind,
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    when (kind) {
        PanelFieldKind.CLIPBOARD_SEARCH -> ClipboardSearchFieldCell(state, session, callbacks)
        PanelFieldKind.CLIPBOARD_ENTITIES -> ClipboardEntitiesField(state, session, callbacks)
        PanelFieldKind.CLIPBOARD_LIST -> ClipboardListField(state, session, callbacks)
        PanelFieldKind.CLIPBOARD_VIEW -> ClipboardViewField(state, session, callbacks)
        else -> Unit
    }
}

/** The search pill, or an empty cell while there is nothing to filter. */
@Composable
private fun ClipboardSearchFieldCell(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    // Published even when hidden, at zero, so a stale count left behind cannot
    // let Tab land on a pill nothing is drawing.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.SEARCH,
        count = if (session.showSearch) 1 else 0,
        columns = 1,
        onActivate = { callbacks.onSearchToggle() },
    )
    if (!session.showSearch) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ClipboardSearchField(
            state = state,
            onToggle = callbacks.onSearchToggle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .focusRing(state.focusedIndex(FocusRegion.SEARCH) == 0, RoundedCornerShape(18.dp)),
        )
    }
}

/** The fragments strip — codes, numbers, links — or an empty cell when there are none. */
@Composable
private fun ClipboardEntitiesField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val entities = session.entities
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CHIPS,
        count = entities.size,
        columns = entities.size.coerceAtLeast(1),
        onActivate = { index -> entities.getOrNull(index)?.let(callbacks.onEntity) },
    )
    if (entities.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))) {
        ClipEntityStrip(
            entities = entities,
            focused = state.focusedIndex(FocusRegion.CHIPS),
            onPaste = callbacks.onEntity,
        )
    }
}

/**
 * The history, with the Undo bar over its bottom edge after a delete. The bar
 * sits on the history rather than in a cell of its own so it is there however
 * the panel layout is arranged, and in the search panel too, and it outlives
 * the last clip: deleting that one is when the empty placeholder shows.
 */
@Composable
private fun ClipboardListField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ClipboardHistory(state, session, callbacks)
        ClipUndoBar(
            undo = state.clipboardUndo,
            reduceMotion = state.settings.reduceMotion,
            onUndo = callbacks.actions.onUndoDelete,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * "Clip deleted · Undo", in the keyboard's popup style. A snackbar in all but
 * name: Compose's own needs a Scaffold, and a keyboard panel has none.
 *
 * It keeps the count it last showed while it slides away, so the text does
 * not blank out under the exit animation once the service clears the state.
 */
@Composable
private fun ClipUndoBar(
    undo: ClipUndo?,
    reduceMotion: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastCount by remember { mutableIntStateOf(1) }
    if (undo != null) lastCount = undo.items.size
    AnimatedVisibility(
        visible = undo != null,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(0)) else slideInVertically(tween(180)) { it } + fadeIn(tween(180)),
        exit = if (reduceMotion) fadeOut(tween(0)) else slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
    ) {
        val kb = LocalKbTheme.current
        Surface(
            shape = kb.menuShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 6.dp),
            modifier = Modifier
                .widthIn(max = 420.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pluralStringResource(R.plurals.ime_clip_deleted, lastCount, lastCount),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = kb.popupText,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onUndo) {
                    Text(
                        stringResource(R.string.ime_clip_undo_delete),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * The history — two columns of cards packed independently, or one clip per
 * row — with the empty and no-match placeholders where the clips would be.
 *
 * Both views are the same lazy grid with a different column count, so the
 * switch keeps the scroll position, the swipe-to-delete and the item
 * animations, and the focus ring moves the way the clips are drawn.
 */
@Composable
private fun ClipboardHistory(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val shownItems = session.shownItems
    val clipboard = state.settings.clipboard
    val columns = if (session.list) 1 else clipboard.gridColumns.coerceIn(ClipGridColumnsRange)
    // 0 is the view's own: six lines on a card, three in a row, where a list
    // is for scanning many clips.
    val lines = clipboard.previewLines.takeIf { it > 0 } ?: if (session.list) 3 else 6
    // The clock the time labels read. Ticks only while they are shown, and
    // only twice a minute: they count in minutes.
    val timeLabel = clipboard.timeLabel
    // With the swipe off (#344), the hold popup carries the delete instead.
    val swipe = clipboard.swipeToDelete
    val now by produceState(System.currentTimeMillis(), timeLabel) {
        if (timeLabel == ClipTimeLabel.OFF) return@produceState
        while (true) {
            delay(ClipTimeTickMs)
            value = System.currentTimeMillis()
        }
    }
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        count = shownItems.size,
        columns = columns,
        onActivate = { index -> shownItems.getOrNull(index)?.let(callbacks.onItem) },
    )
    if (state.clipboardItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.ime_clipboard_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (shownItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.ime_clipboard_no_match, session.query),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val focused = state.focusedIndex()
    val gridState = session.gridState
    ScrollFocusIntoView(focused) { gridState.animateScrollToItem(it) }
    // Staggered, not a fixed grid: each column packs independently, so a tall
    // image sits next to two or three stacked text clips.
    LazyVerticalStaggeredGrid(
        state = gridState,
        columns = StaggeredGridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        // Room under the last clips for the Undo bar while it is up, so the
        // bottom row can still be scrolled clear of it.
        contentPadding = PaddingValues(
            start = 6.dp,
            top = 6.dp,
            end = 6.dp,
            bottom = if (state.clipboardUndo != null) UndoBarClearance else 6.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalItemSpacing = 6.dp,
    ) {
        itemsIndexed(shownItems, key = { _, item -> item.id }) { index, item ->
            // Deleting fades the card out and slides the survivors up into the
            // gap; pinning re-sorts the list, so the card glides to the front.
            // Under Reduce motion the list simply redraws in its new order.
            SwipeToDeleteCard(
                onDelete = { callbacks.onDelete(item) },
                enabled = swipe,
                modifier = if (state.settings.reduceMotion) {
                    Modifier
                } else {
                    Modifier.animateItem(
                        fadeInSpec = tween(160),
                        placementSpec = spring(
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        ),
                        fadeOutSpec = tween(140),
                    )
                },
            ) {
                val number = session.numbers[item.id]
                val time = clipTimeText(item, timeLabel, clipboard, now)
                if (session.list) {
                    ClipRow(item, number, lines, time, focused = index == focused, holdDelete = !swipe, callbacks)
                } else {
                    ClipCard(item, number, lines, time, focused = index == focused, holdDelete = !swipe, callbacks)
                }
            }
        }
    }
}

/**
 * The panel while its search bar is capturing the keys: the pill and a couple
 * of rows of matches, the key rows returning underneath. The layout's own grid
 * stands down until the search is closed. Unchanged from before the panel
 * became a layout.
 */
@Composable
internal fun ClipboardSearchPanel(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
    modifier: Modifier = Modifier,
) {
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.SEARCH,
        count = 1,
        columns = 1,
        onActivate = { callbacks.onSearchToggle() },
    )
    // No fragment strip or view switch while searching; publish their regions
    // empty so a stale count cannot let Tab land on something nothing is drawing.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.CHIPS,
        count = 0,
        columns = 1,
        onActivate = {},
    )
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = 0,
        columns = 1,
        onActivate = {},
    )
    Column(modifier = modifier) {
        ClipboardSearchField(
            state = state,
            onToggle = callbacks.onSearchToggle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp)
                .focusRing(state.focusedIndex(FocusRegion.SEARCH) == 0, RoundedCornerShape(18.dp)),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ClipboardListField(state, session, callbacks)
        }
    }
}

/**
 * Each clip's number by id: its place in [items], counted from 1. The history
 * as the panel orders it, so the numbers read down the page in order, and a
 * filter that hides some clips leaves the rest with the numbers they had.
 */
internal fun clipNumbers(items: List<ClipItem>): Map<Long, Int> =
    items.withIndex().associate { (index, item) -> item.id to index + 1 }

/**
 * The tap, the hold and the focus ring every clip shares, card or row.
 * Composable only because [focusRing] reads the theme.
 */
@Composable
private fun Modifier.clipSurface(
    kb: KbTheme,
    item: ClipItem,
    focused: Boolean,
    callbacks: ClipboardFieldCallbacks,
    onHold: () -> Unit,
): Modifier {
    val shape = kb.cardShape()
    return this
        .clip(shape)
        .background(kb.chip)
        .chipBorder(kb, shape)
        .focusRing(focused, shape)
        .pointerInput(item.id) {
            detectTapGestures(
                onTap = { callbacks.onItem(item) },
                onLongPress = { onHold() },
            )
        }
}

/**
 * The press-and-hold popup for a clip, with the actions its kind allows, and a
 * Delete when [holdDelete] (the swipe that would otherwise delete is off).
 */
@Composable
private fun ClipHoldPopup(
    item: ClipItem,
    holdDelete: Boolean,
    callbacks: ClipboardFieldCallbacks,
    onDismiss: () -> Unit,
) {
    ClipInfoPopup(
        item,
        onSendSticker = if (item.kind == ClipKind.IMAGE) {
            { callbacks.onSticker(item); onDismiss() }
        } else null,
        onEdit = if (item.clipEditable) {
            { onDismiss(); callbacks.actions.onEdit(item) }
        } else null,
        onDelete = if (holdDelete) {
            { onDismiss(); callbacks.onDelete(item) }
        } else null,
        onDismiss = onDismiss,
    )
}

/** A clip's body by kind; [maxLines] bounds plain text, the one body that can run on. */
@Composable
private fun ClipBody(item: ClipItem, maxLines: Int) {
    when {
        // A masked secret outranks every other body: the point is that its
        // content is not on screen.
        item.sensitive && item.kind.isTextual -> ClipSensitiveBody(item)
        item.kind == ClipKind.IMAGE -> ClipThumbnail(item)
        item.kind == ClipKind.VIDEO -> ClipVideoBody(item)
        item.kind == ClipKind.FILE || item.kind == ClipKind.FOLDER -> ClipFileBody(item)
        item.kind == ClipKind.LINK -> ClipLinkBody(item)
        // Cut before layout: a paragraph is measured whole however few lines
        // are drawn, and one clip can be a whole document.
        else -> Text(
            text = remember(item.text, maxLines) { clipPreviewText(item.text, maxLines) },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The pin and delete circles, in that order. */
@Composable
private fun ClipActions(item: ClipItem, callbacks: ClipboardFieldCallbacks) {
    ClipActionCircle(
        icon = if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
        description = stringResource(if (item.pinned) R.string.ime_clip_unpin else R.string.ime_clip_pin),
        tint = if (item.pinned) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) { callbacks.onPin(item) }
    ClipActionCircle(
        icon = Icons.Outlined.Delete,
        description = stringResource(CommonR.string.common_delete),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    ) { callbacks.onDelete(item) }
}

/**
 * A clip's number, as a small filled tag. Filled with the accent so it reads
 * as a label the keyboard put there and never as part of the clip's text.
 */
@Composable
private fun ClipNumberBadge(number: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.ime_clip_number_desc, number)
    Box(
        modifier = modifier
            .heightIn(min = 20.dp)
            .widthIn(min = 20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * One history card: body by kind, then the number, the pin and the delete
 * circle along the bottom. The number rides the row the circles already
 * take, so turning numbering on never makes a card taller.
 */
@Composable
private fun ClipCard(
    item: ClipItem,
    number: Int?,
    lines: Int,
    time: String?,
    focused: Boolean,
    holdDelete: Boolean,
    callbacks: ClipboardFieldCallbacks,
) {
    var showInfo by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clipSurface(LocalKbTheme.current, item, focused, callbacks) { showInfo = true }
            // An image card insets less: the picture is the content.
            .padding(if (item.kind == ClipKind.IMAGE || item.kind == ClipKind.VIDEO) 5.dp else 10.dp),
    ) {
        if (showInfo) ClipHoldPopup(item, holdDelete, callbacks) { showInfo = false }
        ClipBody(item, maxLines = lines)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (number != null) ClipNumberBadge(number)
            if (item.kind == ClipKind.HTML) {
                Text(
                    stringResource(R.string.ime_clip_type_rich_text),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (time != null) ClipTimeText(time, Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            ClipActions(item, callbacks)
        }
    }
}

/**
 * One history row of the list view: the number, the clip across the full
 * width, and the pin and delete circles at the end. Text gets three lines
 * rather than a card's six — a list is for scanning many clips — and a
 * picture is a thumbnail at the start of the row rather than the whole row.
 */
@Composable
private fun ClipRow(
    item: ClipItem,
    number: Int?,
    lines: Int,
    time: String?,
    focused: Boolean,
    holdDelete: Boolean,
    callbacks: ClipboardFieldCallbacks,
) {
    var showInfo by remember { mutableStateOf(false) }
    val visual = item.kind == ClipKind.IMAGE || item.kind == ClipKind.VIDEO
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clipSurface(LocalKbTheme.current, item, focused, callbacks) { showInfo = true }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showInfo) ClipHoldPopup(item, holdDelete, callbacks) { showInfo = false }
        if (number != null) ClipNumberBadge(number)
        if (visual) {
            // Width-bound, so the picture keeps its own shape at thumbnail
            // size, and height-capped so a portrait shot stays a row.
            Box(
                modifier = Modifier
                    .width(ListThumbnailWidth)
                    .heightIn(max = ListThumbnailMaxHeight)
                    .clip(RoundedCornerShape(8.dp)),
            ) { ClipBody(item, maxLines = lines) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (item.kind == ClipKind.IMAGE) R.string.ime_clip_type_image else R.string.ime_clip_type_video,
                    ),
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (time != null) ClipTimeText(time, Modifier.padding(top = 2.dp))
            }
        } else {
            Column(modifier = Modifier.weight(1f)) {
                ClipBody(item, maxLines = lines)
                if (item.kind == ClipKind.HTML) {
                    Text(
                        stringResource(R.string.ime_clip_type_rich_text),
                        fontSize = 10.sp,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (time != null) ClipTimeText(time, Modifier.padding(top = 2.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ClipActions(item, callbacks) }
    }
}

/** A clip's time label, in the rich-text tag's small muted type. */
@Composable
private fun ClipTimeText(time: String, modifier: Modifier = Modifier) {
    Text(
        time,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The time [item] shows under [label] at [now], or null for none: how long ago
 * it was copied, or how long it has left by the same rule the store prunes by
 * ([expiresAt]). A pinned clip, or one history keeps forever, has no time left
 * to show.
 */
@Composable
private fun clipTimeText(item: ClipItem, label: ClipTimeLabel, clipboard: ClipboardSettings, now: Long): String? =
    when (label) {
        ClipTimeLabel.OFF -> null
        ClipTimeLabel.COPIED -> android.text.format.DateUtils.getRelativeTimeSpanString(
            item.timestamp,
            now,
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
        ClipTimeLabel.EXPIRES -> {
            val sensitiveLeash = if (clipboard.sensitiveHandling == SensitiveClipHandling.SHORT_LIVED) {
                clipboard.sensitiveExpiryMinutes * MinuteMs
            } else {
                0L
            }
            item.expiresAt(clipboard.expiryHours * 60 * MinuteMs, sensitiveLeash)?.let { at ->
                val minutes = ((at - now).coerceAtLeast(0L) + MinuteMs - 1) / MinuteMs
                when {
                    minutes < 60 -> minutes.coerceAtLeast(1L).toInt().let {
                        pluralStringResource(R.plurals.ime_clip_left_minutes, it, it)
                    }
                    minutes < 48 * 60 -> (minutes / 60).toInt().let {
                        pluralStringResource(R.plurals.ime_clip_left_hours, it, it)
                    }
                    else -> (minutes / (24 * 60)).toInt().let {
                        pluralStringResource(R.plurals.ime_clip_left_days, it, it)
                    }
                }
            }
        }
    }

private const val MinuteMs = 60_000L

/** How often the time labels are brought up to date while the panel is open. */
private const val ClipTimeTickMs = 30_000L

/** The history's bottom padding while the Undo bar covers its last few dp. */
private val UndoBarClearance = 64.dp

/** How big a picture is drawn in a list row. */
private val ListThumbnailWidth = 96.dp
private val ListThumbnailMaxHeight = 80.dp

/**
 * The grid / list switch. Its icon is the view a tap switches *to*, the way a
 * toolbar's view toggle reads, and it stands down with the history: with no
 * clips there is nothing to lay out either way.
 */
@Composable
private fun ClipboardViewField(
    state: KeyboardUiState,
    session: ClipboardPanelSession,
    callbacks: ClipboardFieldCallbacks,
) {
    val shown = state.clipboardItems.isNotEmpty()
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = if (shown) 1 else 0,
        columns = 1,
        onActivate = { callbacks.actions.onViewToggle() },
    )
    if (!shown) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ClipboardViewToggle(
            list = session.list,
            focused = state.focusedIndex(FocusRegion.ACTIONS) == 0,
            onToggle = callbacks.actions.onViewToggle,
        )
    }
}

/** The switch itself, a square in the search pill's own card style. */
@Composable
internal fun ClipboardViewToggle(
    list: Boolean,
    focused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Box(
        modifier = modifier
            // Square, and no taller than the pill beside it: a strip row is
            // shorter than 36 dp at small key heights, and the cell wins.
            .heightIn(max = 36.dp)
            .fillMaxHeight()
            .aspectRatio(1f)
            .clip(shape)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (list) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
            contentDescription = stringResource(
                if (list) R.string.ime_clipboard_view_grid_desc else R.string.ime_clipboard_view_list_desc,
            ),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The clip editor: a dialog in the panel's place, the keys back beneath it.
 *
 * Not a real text field — an input method has none of its own to raise — so
 * the keys type into the service's draft the way they type into the search
 * pill, and this draws that draft with its caret. Enter is a line break;
 * Save and Cancel are the dialog's own, and Back cancels too.
 */
@Composable
internal fun ClipEditDialog(
    state: KeyboardUiState,
    edit: ClipEdit,
    actions: ClipboardPanelActions,
    modifier: Modifier = Modifier,
) {
    // Nothing else in the panel is on screen, so no other region may keep a
    // ring on a card or a chip that is not drawn.
    for (region in listOf(FocusRegion.SEARCH, FocusRegion.CHIPS, FocusRegion.RESULTS)) {
        PanelFocusTarget(panel = PanelMode.CLIPBOARD, region = region, count = 0, columns = 1, onActivate = {})
    }
    // Cancel and Save, for a physical keyboard, whose Enter is a line break here.
    PanelFocusTarget(
        panel = PanelMode.CLIPBOARD,
        region = FocusRegion.ACTIONS,
        count = 2,
        columns = 2,
        onActivate = { index -> if (index == 0) actions.onEditCancel() else actions.onEditSave() },
    )
    val focused = state.focusedIndex(FocusRegion.ACTIONS)
    val kb = LocalKbTheme.current
    Box(modifier = modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Surface(
            shape = kb.menuShape(),
            color = kb.popup,
            border = kb.popupSurfaceBorder(),
            shadowElevation = elevationFor(kb.menuShapeKind, 4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = kb.popupText.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.ime_clip_edit_title),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            color = kb.popupText,
                        )
                        // Only once the draft has moved: until then nothing is lost.
                        if (edit.rich && edit.draft.trim() != edit.original) {
                            Text(
                                stringResource(R.string.ime_clip_edit_rich_note),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = kb.popupText.copy(alpha = 0.6f),
                            )
                        }
                    }
                    TextButton(
                        onClick = actions.onEditCancel,
                        modifier = Modifier.focusRing(focused == 0, RoundedCornerShape(18.dp)),
                    ) { Text(stringResource(CommonR.string.common_cancel)) }
                    TextButton(
                        onClick = actions.onEditSave,
                        enabled = edit.draft.isNotBlank(),
                        modifier = Modifier.focusRing(focused == 1, RoundedCornerShape(18.dp)),
                    ) { Text(stringResource(R.string.ime_clip_edit_save), fontWeight = FontWeight.SemiBold) }
                }
                val fieldShape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(fieldShape)
                        .background(kb.chip)
                        .chipBorder(kb, fieldShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    ClipEditText(
                        text = edit.draft,
                        placeholder = stringResource(R.string.ime_clip_edit_placeholder),
                        textColor = MaterialTheme.colorScheme.onSurface,
                        placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The draft with its caret, wrapped over as many lines as it takes and
 * scrolled to keep the caret in sight. A tap puts the caret under the finger.
 *
 * The one-line [SearchQueryText] asks the layout for an x; this asks it for
 * the caret's whole rectangle, which is what finds the right line. Both only
 * ever ask a layout of *this* text, never one a frame stale (#252).
 *
 * Shared with the AI panel's chat composer (#280), the keyboard's other
 * free-text field. That one passes a [modifier] that wraps its lines up to a
 * cap, where the clip editor fills the room it is given.
 */
@Composable
internal fun ClipEditText(
    text: String,
    placeholder: String,
    textColor: Color,
    placeholderColor: Color,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val handle = LocalCaptureCaret.current
    val caret = handle.at.coerceIn(0, text.length)
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val fontSize = 14.sp
    Box(modifier = modifier.verticalScroll(scroll)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(text) {
                    detectTapGestures { position ->
                        layout?.takeIf { it.layoutInput.text.text == text }
                            ?.let { handle.onCaretTap(it.getOffsetForPosition(position)) }
                    }
                },
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = fontSize,
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth(),
            )
            if (text.isEmpty()) {
                Text(
                    text = placeholder,
                    color = placeholderColor,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            val result = layout?.takeIf { it.layoutInput.text.text == text }
            if (result != null) {
                val rect = result.getCursorRect(caret.coerceIn(0, result.layoutInput.text.length))
                Box(
                    Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) },
                ) {
                    EditCaret(
                        color = textColor,
                        height = with(density) { rect.height.toDp() },
                        restartKey = caret to text,
                    )
                }
                // Keep the caret's line in view: typing at the end of a long
                // clip, or a newline, would otherwise run off the bottom.
                LaunchedEffect(rect.top, rect.bottom, scroll.viewportSize, scroll.maxValue) {
                    val viewport = scroll.viewportSize
                    if (viewport <= 0) return@LaunchedEffect
                    val margin = with(density) { 4.dp.toPx() }
                    val want = when {
                        rect.top - margin < scroll.value -> rect.top - margin
                        rect.bottom + margin > scroll.value + viewport -> rect.bottom + margin - viewport
                        else -> return@LaunchedEffect
                    }
                    scroll.scrollTo(want.roundToInt().coerceIn(0, scroll.maxValue))
                }
            }
        }
    }
}

/** The editor's blinking bar; held solid under reduce motion, like the search caret. */
@Composable
private fun EditCaret(color: Color, height: androidx.compose.ui.unit.Dp, restartKey: Any?) {
    val reduceMotion = LocalKbTheme.current.reduceMotion
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(restartKey, reduceMotion) {
        visible = true
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            delay(500)
            visible = !visible
        }
    }
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .height(height)
            .background(if (visible) color else Color.Transparent),
    )
}
