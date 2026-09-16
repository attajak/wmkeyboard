package com.wasimaster.wmkeyboard.app

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.plugins.PluginSnapshot
import com.wasimaster.wmkeyboard.core.plugins.SnapshotReason
import com.wasimaster.wmkeyboard.core.ui.ScrollRailBox
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import androidx.compose.foundation.lazy.rememberLazyListState

/** The draft's kept versions, newest first. A tap compares one with the code now. */
@Composable
internal fun VersionsDialog(versions: List<PluginSnapshot>, onOpen: (PluginSnapshot) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val list = rememberLazyListState()
    val rail = rememberScrollRailState(list)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_ide_versions_title)) },
        text = {
            if (versions.isEmpty()) {
                Text(stringResource(R.string.plugin_ide_versions_empty))
            } else {
                ScrollRailBox(state = rail, modifier = Modifier.heightIn(max = 380.dp)) { rows ->
                    LazyColumn(modifier = rows, state = list) {
                        items(versions, key = { it.snapshotId }) { version ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpen(version) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    DateUtils.getRelativeTimeSpanString(version.at, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(reasonLabel(version.reason)) + "\n" +
                                        context.resources.getQuantityString(R.plurals.plugin_ide_version_lines, version.lines, version.lines),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (version.note.isNotBlank()) {
                                    Text(version.note, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) } },
    )
}

/**
 * One version against the code now, as restoring it would change the code:
 * lines marked `+` come back and lines marked `-` go. Unchanged stretches fold
 * into a count. [rows] is null while the comparison is worked out.
 */
@Composable
internal fun VersionDiffDialog(version: PluginSnapshot, rows: List<DiffRow>?, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = rememberCodeColors()
    val changed = rows?.any { it is DiffRow.Line && it.line.kind != DiffKind.SAME } == true
    val diffList = rememberLazyListState()
    val diffRail = rememberScrollRailState(diffList)
    val when_ = DateUtils.getRelativeTimeSpanString(version.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_ide_version_diff_title, when_)) },
        text = {
            Column {
                when {
                    rows == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    !changed -> Text(stringResource(R.string.plugin_ide_version_same_as_now))
                    else -> {
                        Text(
                            stringResource(R.string.plugin_ide_version_diff_caption),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        ScrollRailBox(
                            state = diffRail,
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .fillMaxWidth()
                                .background(colors.background),
                            // The code's own background, not the dialog's: the
                            // fade has to disappear into what it sits on.
                            fadeColor = colors.background,
                        ) { lines ->
                            LazyColumn(modifier = lines.padding(8.dp), state = diffList) {
                                items(rows) { row ->
                                    when (row) {
                                        is DiffRow.Unchanged -> Text(
                                            context.resources.getQuantityString(R.plurals.plugin_ide_version_unchanged_lines, row.count, row.count),
                                            fontSize = 11.sp,
                                            fontStyle = FontStyle.Italic,
                                            color = colors.gutterText,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                        is DiffRow.Line -> Text(
                                            when (row.line.kind) {
                                                DiffKind.SAME -> "  "
                                                DiffKind.ADDED -> "+ "
                                                DiffKind.REMOVED -> "- "
                                            } + row.line.text,
                                            fontFamily = CodeFontFamily,
                                            fontSize = 12.sp,
                                            color = when (row.line.kind) {
                                                DiffKind.SAME -> colors.text
                                                DiffKind.ADDED -> colors.function
                                                DiffKind.REMOVED -> colors.problem
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRestore, enabled = changed) { Text(stringResource(R.string.plugin_ide_version_restore_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

@StringRes
private fun reasonLabel(wire: String): Int = when (SnapshotReason.parse(wire)) {
    SnapshotReason.MANUAL, null -> R.string.plugin_ide_version_reason_manual
    SnapshotReason.PERIODIC -> R.string.plugin_ide_version_reason_periodic
    SnapshotReason.BEFORE_RESTORE -> R.string.plugin_ide_version_reason_before_restore
    SnapshotReason.PUBLISH -> R.string.plugin_ide_version_reason_publish
    SnapshotReason.IMPORT -> R.string.plugin_ide_version_reason_import
}
