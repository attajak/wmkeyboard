package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupCrypto
import com.wasimaster.wmkeyboard.core.settings.BackupInstall
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sink.AutoBackupNaming
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * One automatic backup as the restore list shows it.
 *
 * [device] is already the sentence: "This device", the name another phone
 * wrote into the file name, or the words for a backup from before names
 * carried one.
 */
private data class RemoteBackup(
    val entry: SinkEntry,
    val device: String,
    val mine: Boolean,
    val takenAtMs: Long,
    val encrypted: Boolean,
)

private sealed interface RemoteStage {
    data object Loading : RemoteStage
    data class Listed(val backups: List<RemoteBackup>) : RemoteStage
    data object Downloading : RemoteStage
    data class Failed(val message: String) : RemoteStage
    data class Ready(val uri: Uri, val file: File) : RemoteStage
}

/**
 * Restores one of the automatic backups from the destination they went to.
 *
 * The only way back for a Google Drive backup: `appDataFolder` is invisible
 * to every app but this one, so there is no file for the ordinary import to
 * pick. It is also quicker for the others than finding the file in another
 * app's folder by hand.
 *
 * The list is every device's backups, not just this one's, because the
 * obvious reason to open it is a new phone. Picking one downloads it to the
 * cache and hands it to [ImportFileDialog], which is the confirmation, the
 * section summary and the merge every other import goes through. An
 * encrypted backup is opened with the stored passphrase first; only when that
 * does not open it does the dialog ask.
 */
@Composable
internal fun RemoteRestoreDialog(
    repository: SettingsRepository,
    auto: AutoBackupSettings,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<RemoteStage>(RemoteStage.Loading) }

    LaunchedEffect(Unit) { stage = list(context, auto) }

    when (val current = stage) {
        RemoteStage.Loading, RemoteStage.Downloading -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.backup_remote_restore_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(
                        stringResource(
                            if (current == RemoteStage.Loading) {
                                R.string.backup_remote_restore_loading
                            } else {
                                R.string.backup_remote_restore_downloading
                            },
                        ),
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )

        is RemoteStage.Failed -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.backup_remote_restore_title)) },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )

        is RemoteStage.Listed -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.backup_remote_restore_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.backup_remote_restore_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(current.backups, key = { it.entry.id }) { backup ->
                            RemoteBackupRow(context, backup) {
                                stage = RemoteStage.Downloading
                                scope.launch { stage = download(context, auto, backup) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )

        is RemoteStage.Ready -> ImportFileDialog(repository, current.uri) {
            current.file.delete()
            onClose()
        }
    }
}

@Composable
private fun RemoteBackupRow(context: Context, backup: RemoteBackup, onClick: () -> Unit) {
    val taken = DateUtils.formatDateTime(
        context,
        backup.takenAtMs,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )
    val size = backup.entry.sizeBytes.takeIf { it >= 0 }
        ?.let { Formatter.formatShortFileSize(context, it) }
    ListItem(
        headlineContent = { Text(backup.device) },
        supportingContent = {
            Text(
                if (size != null) {
                    stringResource(R.string.backup_remote_restore_entry, taken, size)
                } else {
                    taken
                },
            )
        },
        trailingContent = if (backup.encrypted) {
            {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = stringResource(R.string.backup_remote_restore_encrypted),
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** Every automatic backup at the destination, newest first. */
private suspend fun list(context: Context, auto: AutoBackupSettings): RemoteStage {
    val sink = AutoBackupRunner.sinkFor(context, auto)
        ?: return RemoteStage.Failed(errorText(context, SinkError.NOT_CONFIGURED, auto))
    val entries = sink.list().getOrElse { failure ->
        return RemoteStage.Failed(errorText(context, (failure as? BackupSinkException)?.reason, auto))
    }
    if (entries.isEmpty()) {
        return RemoteStage.Failed(context.getString(R.string.backup_remote_restore_empty))
    }
    val me = BackupInstall.id(context)
    val backups = entries.map { entry ->
        val parsed = AutoBackupNaming.parse(entry.name)
        val mine = parsed?.installId == me
        val device = parsed?.device
        RemoteBackup(
            entry = entry,
            device = when {
                mine -> context.getString(R.string.backup_remote_restore_this_device)
                device != null -> device.replace('-', ' ')
                else -> context.getString(R.string.backup_remote_restore_unknown_device)
            },
            mine = mine,
            takenAtMs = parsed?.stampMs ?: entry.modifiedAtMs,
            encrypted = parsed?.encrypted ?: false,
        )
    }
    return RemoteStage.Listed(backups.sortedByDescending { it.takenAtMs })
}

/**
 * Downloads [backup] into the cache for [ImportFileDialog] to open.
 *
 * An encrypted one is tried against the stored passphrase here, and written
 * out as plain text when that works, so the dialog shows its ordinary summary
 * instead of asking for something the app already has. When it does not work
 * (another phone's backup, or a passphrase changed since), the ciphertext is
 * written as it came and the dialog asks.
 */
private suspend fun download(
    context: Context,
    auto: AutoBackupSettings,
    backup: RemoteBackup,
): RemoteStage {
    val failed = RemoteStage.Failed(context.getString(R.string.backup_remote_restore_failed))
    val sink = AutoBackupRunner.sinkFor(context, auto) ?: return failed
    return withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = sink.read(backup.entry).getOrThrow().use { it.readBytes() }
            val dir = File(context.cacheDir, "remote-restore").apply {
                deleteRecursively()
                mkdirs()
            }
            val opened = if (BackupCrypto.looksEncrypted(bytes) && auto.passphrase.isNotEmpty()) {
                (
                    BackupCrypto.decrypt(bytes.inputStream(), auto.passphrase.toCharArray())
                        as? BackupCrypto.DecryptResult.Ok
                    )?.text
            } else {
                null
            }
            val file = if (opened != null) {
                File(dir, "restore.wmconfig.json").apply { writeText(opened) }
            } else {
                File(dir, backup.entry.name).apply { writeBytes(bytes) }
            }
            RemoteStage.Ready(Uri.fromFile(file), file) as RemoteStage
        }.getOrElse { failed }
    }
}

private fun errorText(context: Context, reason: SinkError?, auto: AutoBackupSettings): String =
    autoBackupErrorText(context, (reason ?: SinkError.IO).name, auto.destination)
        ?: context.getString(R.string.backup_auto_error_io)
