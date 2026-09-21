package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.lock.AppLockTargets
import com.wasimaster.wmkeyboard.core.aihistory.AiHistoryEntry
import com.wasimaster.wmkeyboard.core.aihistory.AiHistoryStore
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.aichat.AiChatController
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * What the AI tool was asked and what it answered, when the user has turned the
 * history on. Everything here is on the device: the screen reads the same file
 * the keyboard writes.
 *
 * Built on the same shape as the debug log screen: the store is not a flow, so
 * a revision counter re-reads it after every change, and the list is a
 * LazyColumn of a fixed height outside any settings group, because a lazy list
 * inside a scrolling column has no height to work with.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiHistoryScreen(repository: SettingsRepository, settings: KeyboardSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var actionFilter by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // Its own instance, deliberately: the keyboard holds another one in the
    // same process, and each re-reads the file before it writes.
    val store = remember { AiHistoryStore(File(context.filesDir, AiHistoryStore.FILE_PATH)) }
    val entries by produceState(emptyList<AiHistoryEntry>(), revision) {
        value = withContext(Dispatchers.IO) {
            store.reload()
            store.items()
        }
    }

    val actionNames = remember(entries) {
        entries.map { it.actionId to it.actionName }.distinct().sortedBy { it.second }
    }
    val shown = remember(entries, query, actionFilter) {
        val needle = query.trim().lowercase()
        entries.filter { entry ->
            (actionFilter == null || entry.actionId == actionFilter) &&
                (
                    needle.isEmpty() ||
                        entry.input.lowercase().contains(needle) ||
                        entry.output.lowercase().contains(needle) ||
                        entry.model.lowercase().contains(needle)
                    )
        }
    }

    if (!settings.ai.historyEnabled) {
        CaptionText(stringResource(R.string.toolai_ai_history_off_body))
    }
    SettingsGroup {
        item {
            ToggleSetting(
                R.string.toolai_ai_history_title,
                stringResource(R.string.toolai_ai_history_subtitle),
                settings.ai.historyEnabled,
                default = SettingsDefaults.ai.historyEnabled,
            ) { on ->
                scope.launch {
                    repository.setAiHistoryEnabled(on)
                    if (!on) {
                        // An off switch that leaves the log on disk is the
                        // version of this that becomes a privacy complaint.
                        withContext(Dispatchers.IO) { store.deleteStorage() }
                        revision++
                    }
                }
            }
        }
        item(visible = settings.ai.historyEnabled) {
            SliderSetting(
                R.string.toolai_ai_history_max_title,
                subtitle = stringResource(R.string.toolai_ai_history_max_subtitle),
                value = settings.ai.historyMax.toFloat(),
                range = AiHistoryStore.MIN_MAX_ITEMS.toFloat()..
                    AiHistoryStore.MAX_ITEMS_CEILING.toFloat(),
                display = { it.toInt().toString() },
                default = SettingsDefaults.ai.historyMax.toFloat(),
            ) { picked ->
                scope.launch {
                    repository.setAiHistoryMax(picked.toInt())
                    withContext(Dispatchers.IO) {
                        store.reload()
                        store.trimTo(picked.toInt())
                        store.save()
                    }
                    revision++
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.toolai_models_group_title)) {
        item {
            val charsFormat = stringResource(R.string.values_number)
            SliderSetting(
                R.string.toolai_continue_context_title,
                subtitle = stringResource(R.string.toolai_continue_context_subtitle),
                value = settings.ai.beforeCursorChars.toFloat(),
                range = 500f..32_000f,
                display = { charsFormat.format((it / 500f).roundToInt() * 500) },
                info = stringResource(R.string.toolai_continue_context_info),
                default = SettingsDefaults.ai.beforeCursorChars.toFloat(),
            ) { picked ->
                scope.launch {
                    repository.setAiBeforeCursorChars((picked / 500f).roundToInt() * 500)
                }
            }
        }
    }

    // Chat transcripts. They persisted with no switch and no bulk delete while
    // the one-shot history above had both, which is the wrong way round: a
    // conversation is the longer and more revealing record of the two.
    SettingsGroup(stringResource(R.string.toolai_chats_group_title)) {
        item {
            ToggleSetting(
                R.string.toolai_keep_chats_title,
                stringResource(R.string.toolai_keep_chats_subtitle),
                settings.ai.keepChats,
                info = stringResource(R.string.toolai_keep_chats_info),
                default = SettingsDefaults.ai.keepChats,
            ) { on ->
                scope.launch {
                    repository.setAiKeepChats(on)
                    // Applied straight away, which also deletes the file when
                    // switching off — the same contract as the log above.
                    AiChatController.applyPersistSetting(context, on)
                }
            }
        }
        item {
            ActionRow(
                title = R.string.toolai_delete_chats_title,
                subtitle = stringResource(R.string.toolai_delete_chats_subtitle),
                action = stringResource(CommonR.string.common_delete),
                confirm = stringResource(R.string.toolai_delete_chats_confirm),
                lock = AppLockTargets["action_delete_ai_chats"],
            ) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val chats = AiChatController.store(context)
                        chats.clear()
                        chats.save()
                    }
                    AiChatController.bumpStoreVersion()
                }
            }
        }
    }

    if (entries.isNotEmpty()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.toolai_ai_history_search_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (actionNames.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                FilterChip(
                    selected = actionFilter == null,
                    onClick = { actionFilter = null },
                    label = { Text(stringResource(R.string.toolai_ai_history_all_actions)) },
                )
                // Only the actions that are actually in the data, so the row
                // stays short as the list ages.
                for ((id, label) in actionNames) {
                    FilterChip(
                        selected = actionFilter == id,
                        onClick = { actionFilter = if (actionFilter == id) null else id },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        }
    }

    when {
        entries.isEmpty() && settings.ai.historyEnabled ->
            CaptionText(stringResource(R.string.toolai_ai_history_empty))
        shown.isEmpty() && entries.isNotEmpty() ->
            CaptionText(stringResource(R.string.toolai_ai_history_filter_empty))
        else -> LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .padding(horizontal = 16.dp),
        ) {
            items(shown, key = { it.id }) { entry ->
                AiHistoryRow(
                    entry = entry,
                    expanded = expanded == entry.id,
                    onToggle = { expanded = if (expanded == entry.id) null else entry.id },
                    onCopyInput = { copyToClipboard(context, entry.input) },
                    onCopyOutput = { copyToClipboard(context, entry.output) },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                store.reload()
                                store.delete(entry.id)
                                store.save()
                            }
                            revision++
                        }
                    },
                )
            }
        }
    }

    if (entries.isNotEmpty()) {
        Row(modifier = Modifier.padding(16.dp)) {
            Button(onClick = { confirmClear = true }) {
                Text(stringResource(R.string.toolai_ai_history_clear_action))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.toolai_ai_history_clear_action)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.toolai_ai_history_clear_body,
                        entries.size,
                        entries.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { store.deleteStorage() }
                        revision++
                    }
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/**
 * One run.
 *
 * Collapsed rows show a short lead-in rather than the whole text behind a line
 * limit: Compose lays the whole string out either way, and a hundred rows of
 * four thousand characters is a visible stall on the way into the screen.
 */
@Composable
private fun AiHistoryRow(
    entry: AiHistoryEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopyInput: () -> Unit,
    onCopyOutput: () -> Unit,
    onDelete: () -> Unit,
) {
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    WmRow(
        title = entry.actionName,
        subtitle = timeFormat.format(Date(entry.timestamp)) + "  " + entry.model,
        supporting = {
            Column {
                Text(
                    entry.input.preview(expanded),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (entry.failed) entry.error else entry.output.preview(expanded),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded) {
                    Spacer(Modifier.height(4.dp))
                    Row {
                        TextButton(onClick = onCopyInput) {
                            Text(stringResource(R.string.toolai_ai_history_copy_input))
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onCopyOutput, enabled = entry.output.isNotEmpty()) {
                            Text(stringResource(R.string.toolai_ai_history_copy_output))
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) {
                            Text(stringResource(CommonR.string.common_delete))
                        }
                    }
                }
            }
        },
        onClick = onToggle,
    )
}

/** Length of the lead-in a collapsed row draws. See [AiHistoryRow]. */
private const val PREVIEW_CHARS = 200

private fun String.preview(expanded: Boolean): String =
    if (expanded || length <= PREVIEW_CHARS) this else take(PREVIEW_CHARS) + "…"

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("", text))
}
