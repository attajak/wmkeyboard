package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.LauncherIconShape
import com.wasimaster.wmkeyboard.ime.AppCatalog
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LauncherActivity
import com.wasimaster.wmkeyboard.ime.LauncherApp
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/**
 * The app-launcher panel's callbacks, bundled into one object rather than
 * seven parameters on [KeyboardScreen]: that function's argument list already
 * flirts with the JVM's 64K method-size ceiling at its call site, and the
 * launcher was the straw that crossed it.
 */
@androidx.compose.runtime.Immutable
class LauncherPanelCallbacks(
    val onAppTap: (LauncherApp) -> Unit = {},
    val onOpenDetail: (LauncherApp) -> Unit = {},
    val onActivityTap: (LauncherActivity) -> Unit = {},
    val onPinToggle: (String) -> Unit = {},
    val onAppInfo: (String) -> Unit = {},
    val onDetailClose: () -> Unit = {},
    val onHideToggle: (String) -> Unit = {},
    /** Resolves an app's icon through the service's LRU cache, off-main. */
    val iconFor: suspend (LauncherApp) -> ImageBitmap? = { null },
)

/**
 * The app-launcher tool: a searchable grid of every launchable app, a
 * pinned/recents row, and (long-press, from either) a per-app page carrying
 * the pin, hide and App-info actions plus the activities inside the app. The
 * service owns the catalog and the launches; this panel renders
 * [KeyboardUiState.launcherApps]/[KeyboardUiState.launcherDetail]
 * and reports taps.
 *
 * [iconFor] resolves one app's icon off the main thread through the service's
 * LRU cache — cells draw a neutral placeholder until theirs lands, so a cold
 * open never decodes a hundred adaptive icons on the composition clock.
 */
@Composable
internal fun AppLauncherPanel(
    state: KeyboardUiState,
    callbacks: LauncherPanelCallbacks,
    onQueryTap: () -> Unit,
) {
    val detail = state.launcherDetail
    if (detail != null) {
        LauncherDetail(
            state, detail.app, callbacks.iconFor,
            callbacks.onActivityTap, callbacks.onPinToggle, callbacks.onAppInfo,
            callbacks.onHideToggle,
        )
        return
    }
    LauncherGrid(
        state, callbacks.iconFor, callbacks.onAppTap,
        callbacks.onOpenDetail, onQueryTap,
    )
}

@Composable
private fun LauncherGrid(
    state: KeyboardUiState,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onAppTap: (LauncherApp) -> Unit,
    onOpenDetail: (LauncherApp) -> Unit,
    onQueryTap: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val launcher = state.settings.launcher
    val query = state.mediaQuery
    val hidden = launcher.hidden.toSet()
    val iconShape = launcherIconShape(launcher.iconShape)
    // Hidden apps drop out of the browsing grid only. A search still finds
    // them, dimmed, so hiding never makes an app unreachable from here.
    val apps = AppCatalog.filterApps(state.launcherApps, query)
        .let { found -> if (query.isEmpty()) found.filter { it.packageName !in hidden } else found }
    val sorted = AppCatalog.sortApps(
        apps,
        recentFirst = launcher.sortOrder ==
            com.wasimaster.wmkeyboard.core.settings.AppSortOrder.RECENT_FIRST,
        pinned = launcher.pinned,
        recents = launcher.recents,
    )
    // Pinned first, then recent — deduplicated so a pinned app never shows
    // twice in the shortcut row. Hidden while searching: the grid is the answer.
    val shortcuts = if (query.isEmpty() && !state.mediaSearchActive) {
        val byPackage = state.launcherApps.associateBy { it.packageName }
        (launcher.pinned + if (launcher.recentsEnabled) launcher.recents else emptyList())
            .distinct()
            .filter { it !in hidden }
            .mapNotNull { byPackage[it] }
    } else {
        emptyList()
    }

    PanelFocusTarget(PanelMode.APP_LAUNCHER, 1, 1, FocusRegion.SEARCH) { onQueryTap() }
    PanelFocusTarget(PanelMode.APP_LAUNCHER, shortcuts.size, shortcuts.size, FocusRegion.CHIPS) {
        shortcuts.getOrNull(it)?.let(onAppTap)
    }
    // An Adaptive grid's true column count is a layout fact; six is close
    // enough for arrow-key math on every phone width this ships on. A fixed
    // count is exact.
    val fixedColumns = launcher.gridColumns
        .takeIf { it != com.wasimaster.wmkeyboard.core.settings.LauncherToolSettings.AUTO_COLUMNS }
    PanelFocusTarget(PanelMode.APP_LAUNCHER, sorted.size, fixedColumns ?: 6) {
        sorted.getOrNull(it)?.let(onAppTap)
    }

    when {
        state.launcherLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(
                    stringResource(R.string.ime_launcher_loading),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        sorted.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .alpha(0.4f),
                    tint = kb.secondaryText,
                )
                Text(
                    stringResource(R.string.ime_launcher_empty),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            if (shortcuts.isNotEmpty()) {
                Text(
                    stringResource(
                        if (launcher.pinned.isEmpty()) R.string.ime_launcher_recents_label
                        else R.string.ime_launcher_pinned_label,
                    ),
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp),
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowItemsIndexed(shortcuts, key = { _, app -> app.packageName }) { _, app ->
                        ShortcutCell(
                            app = app,
                            pinned = app.packageName in launcher.pinned,
                            shape = iconShape,
                            iconFor = iconFor,
                            onTap = { onAppTap(app) },
                            onLongPress = { onOpenDetail(app) },
                        )
                    }
                }
            }
            LazyVerticalGrid(
                // Auto keeps the 68 dp cell of the default 42 dp icon and
                // widens it with a bigger icon, so labels keep their room.
                columns = if (fixedColumns != null) {
                    GridCells.Fixed(fixedColumns)
                } else {
                    GridCells.Adaptive(minSize = maxOf(68, launcher.iconSizeDp + 26).dp)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
            ) {
                itemsIndexed(sorted, key = { _, app -> app.packageName }) { _, app ->
                    AppCell(
                        app = app,
                        showLabel = launcher.showLabels,
                        pinned = app.packageName in launcher.pinned,
                        hidden = app.packageName in hidden,
                        iconSizeDp = launcher.iconSizeDp,
                        shape = iconShape,
                        iconFor = iconFor,
                        onTap = { onAppTap(app) },
                        // One meaning for a hold everywhere in this panel: the
                        // app's page. Pinning and hiding both live there, and a
                        // hold that pinned instead left hiding with no door.
                        onLongPress = { onOpenDetail(app) },
                    )
                }
            }
        }
    }
}

private val RoundedIconShape = RoundedCornerShape(percent = 24)

/** The clip for [LauncherIconShape.SYSTEM] is none: the icon keeps its own mask. */
private fun launcherIconShape(shape: LauncherIconShape): Shape? = when (shape) {
    LauncherIconShape.CIRCLE -> CircleShape
    LauncherIconShape.ROUNDED -> RoundedIconShape
    LauncherIconShape.SYSTEM -> null
}

/** One app's icon, resolved through the service cache, placeholder first. */
@Composable
private fun AppIcon(
    app: LauncherApp,
    sizeDp: Int,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
) {
    val kb = LocalKbTheme.current
    val icon by produceState<ImageBitmap?>(null, app.packageName, app.activityName) {
        value = iconFor(app)
    }
    // Capped at the size but square at any smaller width: eight columns of a
    // big icon on a narrow phone shrink the icon rather than stretching it
    // into an oval.
    val box = Modifier
        .sizeIn(maxWidth = sizeDp.dp, maxHeight = sizeDp.dp)
        .fillMaxWidth()
        .aspectRatio(1f)
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = null,
            modifier = if (shape != null) box.clip(shape) else box,
        )
    } else {
        val placeholder = shape ?: RoundedIconShape
        Box(
            modifier = box
                .clip(placeholder)
                .background(kb.chip, placeholder),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppCell(
    app: LauncherApp,
    showLabel: Boolean,
    pinned: Boolean,
    hidden: Boolean,
    iconSizeDp: Int,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Column(
        modifier = Modifier
            .alpha(if (hidden) 0.45f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(horizontal = 4.dp)) {
            AppIcon(app, sizeDp = iconSizeDp, shape = shape, iconFor = iconFor)
            if (pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = stringResource(R.string.ime_launcher_pinned_label),
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.TopEnd),
                    tint = kb.toolCircleActiveIcon,
                )
            }
            if (hidden) {
                Icon(
                    Icons.Outlined.VisibilityOff,
                    contentDescription = stringResource(R.string.ime_launcher_hidden_label),
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.BottomEnd),
                    tint = kb.secondaryText,
                )
            }
        }
        if (showLabel) {
            Text(
                app.label,
                color = kb.suggestionText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 3.dp, start = 2.dp, end = 2.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutCell(
    app: LauncherApp,
    pinned: Boolean,
    shape: Shape?,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            AppIcon(app, sizeDp = 36, shape = shape, iconFor = iconFor)
            if (pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = null,
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd),
                    tint = kb.toolCircleActiveIcon,
                )
            }
        }
        Text(
            app.label,
            color = kb.secondaryText,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(52.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The app's page: its header card (icon, label, package) with the pin, hide
 * and App-info actions, and — behind **Open screens inside apps** — every
 * activity it declares, exported first, the rest dimmed with a lock and shown
 * only behind a second setting. With the screens switch off the page is the
 * actions alone; it stays reachable either way, because hiding an app has no
 * other door.
 */
@Composable
private fun LauncherDetail(
    state: KeyboardUiState,
    app: LauncherApp,
    iconFor: suspend (LauncherApp) -> ImageBitmap?,
    onActivityTap: (LauncherActivity) -> Unit,
    onPinToggle: (String) -> Unit,
    onAppInfo: (String) -> Unit,
    onHideToggle: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val detail = state.launcherDetail ?: return
    val launcher = state.settings.launcher
    val shown = if (launcher.activityDrilldown) {
        detail.activities.filter { it.exported || launcher.showNonExported }
    } else {
        emptyList()
    }

    PanelFocusTarget(PanelMode.APP_LAUNCHER, shown.size, 1) {
        shown.getOrNull(it)?.let(onActivityTap)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                app, sizeDp = 40, shape = launcherIconShape(launcher.iconShape), iconFor = iconFor,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    app.label,
                    color = kb.suggestionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    app.packageName,
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val pinned = app.packageName in launcher.pinned
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = stringResource(
                    if (pinned) R.string.ime_launcher_unpin_action
                    else R.string.ime_launcher_pin_action,
                ),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onPinToggle(app.packageName) }
                    .padding(8.dp),
                tint = if (pinned) kb.toolCircleActiveIcon else kb.toolbarIcon,
            )
            val hidden = app.packageName in launcher.hidden
            Icon(
                if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = stringResource(
                    if (hidden) R.string.ime_launcher_unhide_action
                    else R.string.ime_launcher_hide_action,
                ),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onHideToggle(app.packageName) }
                    .padding(8.dp),
                tint = kb.toolbarIcon,
            )
            Icon(
                Icons.Outlined.Info,
                contentDescription = stringResource(R.string.ime_launcher_app_info_action),
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable { onAppInfo(app.packageName) }
                    .padding(8.dp),
                tint = kb.toolbarIcon,
            )
        }
        if (!launcher.activityDrilldown) return@Column
        Text(
            stringResource(R.string.ime_launcher_activities_label),
            color = kb.secondaryText,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
        )
        if (detail.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            return@Column
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
            rowItemsIndexed(shown, key = { _, a -> "${a.packageName}/${a.className}" }) { _, activity ->
                val launchable = activity.exported && activity.enabled
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onActivityTap(activity) }
                        .alpha(if (launchable) 1f else 0.45f)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            activity.label,
                            color = kb.suggestionText,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            activity.className,
                            color = kb.secondaryText,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!launchable) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription =
                                stringResource(R.string.ime_launcher_not_exported_label),
                            modifier = Modifier
                                .size(14.dp)
                                .padding(start = 2.dp),
                            tint = kb.secondaryText,
                        )
                    }
                    Spacer(Modifier.width(0.dp))
                }
            }
        }
    }
}
