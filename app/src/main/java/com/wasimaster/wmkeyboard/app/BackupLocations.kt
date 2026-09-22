package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.drive.driveAuthorizer
import com.wasimaster.wmkeyboard.app.oauth.BackupOAuth
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupScheduler
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.LocationStatus
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.signsIn
import com.wasimaster.wmkeyboard.core.settings.sink.BackupClients
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.S3Sink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A destination's brand-ish colour, for its tile. Muted where there is no brand. */
internal val BackupDestination.accent: Color
    get() = when (this) {
        BackupDestination.FOLDER -> Color(0xFFF9A825)
        BackupDestination.WEBDAV -> Color(0xFF00897B)
        BackupDestination.S3 -> Color(0xFFEF6C00)
        BackupDestination.FTP -> Color(0xFF546E7A)
        BackupDestination.DRIVE -> Color(0xFF1E8E3E)
        BackupDestination.DROPBOX -> Color(0xFF0061FE)
        BackupDestination.ONEDRIVE -> Color(0xFF0078D4)
    }

/** The destination's own name: "Dropbox", "WebDAV", "Folder". */
internal fun destinationName(context: Context, type: BackupDestination): String = context.getString(
    when (type) {
        BackupDestination.FOLDER -> R.string.backup_auto_dest_folder
        BackupDestination.WEBDAV -> R.string.backup_auto_dest_webdav
        BackupDestination.S3 -> R.string.backup_auto_dest_s3
        BackupDestination.FTP -> R.string.backup_auto_dest_ftp
        BackupDestination.DRIVE -> R.string.backup_auto_dest_drive
        BackupDestination.DROPBOX -> R.string.backup_auto_dest_dropbox
        BackupDestination.ONEDRIVE -> R.string.backup_auto_dest_onedrive
    },
)

/** What the user called a location, or its destination's name. */
internal fun BackupLocation.title(context: Context): String = name.ifEmpty { destinationName(context, type) }

/**
 * The line that tells two locations of one kind apart: the folder, the
 * server, the bucket. Null where there is nothing to say (one account per
 * service), and where the title already says it.
 */
internal fun BackupLocation.detail(context: Context): String? = when (type) {
    // A tree URI's last segment is "primary:Download/Backups", or on some
    // providers a raw path. Either way the person wants the folder, not the
    // volume's mount point.
    BackupDestination.FOLDER -> folderUri.takeIf { it.isNotEmpty() }?.let {
        Uri.decode(it.substringAfterLast('/')).substringAfterLast(':')
            .removePrefix("/storage/emulated/0/").removePrefix("/sdcard/").ifEmpty { null }
    }
    BackupDestination.WEBDAV -> webDavUrl.takeIf { it.isNotEmpty() }?.let {
        runCatching { Uri.parse(it).host }.getOrNull() ?: it
    }
    BackupDestination.S3 -> s3.bucket.ifEmpty { null }
    BackupDestination.FTP -> ftp.host.ifEmpty { null }
    else -> null
}?.takeIf { name.isEmpty() || it != name } ?: if (name.isNotEmpty()) destinationName(context, type) else null

/** The destinations this build can reach, in the order the add sheet lists them. */
internal fun availableDestinations(): List<BackupDestination> = buildList {
    add(BackupDestination.FOLDER)
    if (driveAuthorizer().available) add(BackupDestination.DRIVE)
    if (BackupClients.dropboxAvailable) add(BackupDestination.DROPBOX)
    if (BackupClients.oneDriveAvailable) add(BackupDestination.ONEDRIVE)
    add(BackupDestination.WEBDAV)
    add(BackupDestination.S3)
    add(BackupDestination.FTP)
}

/** An example name for [type], under the optional Name field. */
private fun nameHintRes(type: BackupDestination): Int = when (type) {
    BackupDestination.FOLDER -> R.string.backup_location_name_hint_folder
    BackupDestination.WEBDAV -> R.string.backup_location_name_hint_webdav
    BackupDestination.S3 -> R.string.backup_location_name_hint_s3
    BackupDestination.FTP -> R.string.backup_location_name_hint_ftp
    BackupDestination.DRIVE -> R.string.backup_location_name_hint_drive
    BackupDestination.DROPBOX -> R.string.backup_location_name_hint_dropbox
    BackupDestination.ONEDRIVE -> R.string.backup_location_name_hint_onedrive
}

/** What a location that cannot be tried yet is missing, in the words for its kind. */
internal fun needsSetupRes(type: BackupDestination): Int = when {
    type == BackupDestination.FOLDER -> R.string.backup_auto_enabled_needs_folder
    type.signsIn -> R.string.backup_auto_enabled_needs_sign_in
    else -> R.string.backup_auto_enabled_needs_details
}

/**
 * The Locations card on the Backup & restore screen: one row per location,
 * with how its last backup went, and a row that adds another.
 */
@Composable
internal fun LocationsGroup(
    repository: SettingsRepository,
    auto: AutoBackupSettings,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BackupLocation?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<BackupLocation?>(null) }
    var removing by remember { mutableStateOf<BackupLocation?>(null) }

    fun resync() = scope.launch {
        AutoBackupScheduler.sync(context, repository.settings.first().autoBackup)
    }

    // How a browser sign-in ended, said here rather than in the sheet that
    // started it: the sheet may be closed by the time the browser hands back,
    // and a result nobody consumed would surface much later, out of place.
    val signIn by BackupOAuth.result.collectAsStateWithLifecycle()
    LaunchedEffect(signIn) {
        val delivered = signIn ?: return@LaunchedEffect
        BackupOAuth.consume()
        when (delivered.outcome) {
            BackupOAuth.Outcome.SIGNED_IN -> Unit
            BackupOAuth.Outcome.CANCELLED -> onMessage(context.getString(R.string.backup_auto_oauth_cancelled))
            BackupOAuth.Outcome.FAILED -> onMessage(context.getString(R.string.backup_auto_oauth_failed))
        }
    }

    SettingsGroup(
        stringResource(R.string.backup_locations_title),
        info = stringResource(R.string.backup_locations_info),
    ) {
        for (location in auto.locations) {
            item {
                LocationRow(
                    location = location,
                    syncs = auto.sync.enabled && location.id in auto.sync.locationIds,
                    status = auto.locationStatus[location.id] ?: LocationStatus(),
                    onOpen = {
                        editingIsNew = false
                        editing = location
                    },
                    onRestore = { restoring = location },
                    onRemove = { removing = location },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.backup_location_add),
                subtitle = stringResource(R.string.backup_location_add_subtitle),
                icon = Icons.Outlined.Add,
                onClick = { adding = true },
            )
        }
    }

    if (adding) {
        LocationTypeSheet(
            onPick = { type ->
                adding = false
                editingIsNew = true
                editing = BackupLocation(id = BackupLocation.newId(), type = type)
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { location ->
        LocationEditorSheet(
            repository = repository,
            initial = location,
            isNew = editingIsNew,
            live = auto.locations.firstOrNull { it.id == location.id },
            all = auto.locations,
            onMessage = onMessage,
            onDismiss = {
                editing = null
                resync()
            },
        )
    }
    restoring?.let { location ->
        RemoteRestoreDialog(repository, auto, location) { restoring = null }
    }
    removing?.let { location ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.backup_location_remove_title, location.title(context))) },
            text = { Text(stringResource(R.string.backup_location_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        repository.removeBackupLocation(location.id)
                        repository.setSyncLocation(location.id, on = false)
                        releaseFolderGrant(context, location, auto.locations)
                        resync()
                    }
                }) { Text(stringResource(R.string.backup_location_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
    }
}

/**
 * Gives back the folder grant a removed Folder location held, unless another
 * location still points at the same folder. Android caps how many grants an
 * app may keep, and one nobody uses counts against it forever.
 */
private fun releaseFolderGrant(context: Context, removed: BackupLocation, all: List<BackupLocation>) {
    if (removed.type != BackupDestination.FOLDER) return
    releaseFolderUri(context, removed.folderUri, all.filter { it.id != removed.id })
}

/** Gives back the grant on [uri], unless one of [others] still uses that folder. */
private fun releaseFolderUri(context: Context, uri: String, others: List<BackupLocation>) {
    if (uri.isEmpty() || others.any { it.folderUri == uri }) return
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(uri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}

/** One location: its tile, what it is, how its last backup went, and a menu. */
@Composable
private fun LocationRow(
    location: BackupLocation,
    syncs: Boolean,
    status: LocationStatus,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val title = location.title(context)
    val detail = location.detail(context)
    val (backupState, problem) = when {
        !location.enabled -> stringResource(R.string.backup_location_paused) to false
        !location.configured -> stringResource(needsSetupRes(location.type)) to true
        !location.backup -> stringResource(R.string.backup_location_not_backed_up) to false
        status.backupError.isNotEmpty() ->
            (autoBackupErrorText(context, status.backupError, location.type) ?: "") to true
        status.backupAtMs > 0 -> stringResource(
            R.string.backup_location_last_backup,
            DateUtils.getRelativeTimeSpanString(status.backupAtMs).toString(),
        ) to false
        else -> stringResource(R.string.backup_location_never) to false
    }
    val state = if (syncs && location.configured) {
        stringResource(R.string.backup_location_state_syncs, backupState)
    } else {
        backupState
    }
    var menu by remember { mutableStateOf(false) }
    WmRow(
        title = title,
        leading = {
            WmIconTile(
                icon = destinationIcon(location.type),
                accent = if (location.enabled) location.type.accent else MaterialTheme.colorScheme.outline,
            )
        },
        supporting = {
            Column {
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (problem) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp).padding(end = 2.dp),
                        )
                    }
                    Text(
                        state,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problem) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.backup_location_more, title),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.backup_location_restore, title)) },
                        enabled = location.configured,
                        onClick = {
                            menu = false
                            onRestore()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.backup_location_remove)) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        },
        onClick = onOpen,
    )
}

/** The glyph each destination is drawn with, the same one the picker uses. */
internal fun destinationIcon(type: BackupDestination) =
    ChoiceOptionIcons[type] ?: Icons.Outlined.Add

/**
 * "What kind of location?" as a sheet of places, each in its own colour with
 * what it needs. Not a [ChoiceSheet]: that draws radio buttons, which read as
 * a setting with one current answer, and nothing here is selected yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationTypeSheet(onPick: (BackupDestination) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.backup_location_type_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                stringResource(R.string.backup_location_type_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            for (type in availableDestinations()) {
                WmRow(
                    title = destinationName(context, type),
                    subtitle = stringResource(destinationDescRes(type)),
                    leading = { WmIconTile(icon = destinationIcon(type), accent = type.accent) },
                    onClick = { onPick(type) },
                )
            }
        }
    }
}

/**
 * Setting up or changing one location, in a sheet over the Backup screen.
 *
 * Typed fields edit a draft that is saved by Save, so a half-typed address is
 * never tried by a backup that happens to run meanwhile. The two things that
 * are not typed save themselves: a sign-in (Dropbox, OneDrive) stores its
 * token when the browser hands back, and a folder grant is taken when the
 * picker returns. [live] is the stored copy, for those.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationEditorSheet(
    repository: SettingsRepository,
    initial: BackupLocation,
    isNew: Boolean,
    live: BackupLocation?,
    all: List<BackupLocation>,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    val others = all.filter { it.id != initial.id }
    // Closed without saving: a folder picked here and not kept by anything
    // gives its grant back. The saved folder, if any, stays granted.
    val cancel = {
        if (draft.folderUri != (live?.folderUri ?: "")) releaseFolderUri(context, draft.folderUri, all)
        onDismiss()
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    // The stored token wins over the draft's: it is what the sign-in wrote.
    val current = draft.copy(refreshToken = live?.refreshToken ?: draft.refreshToken)

    fun save(then: () -> Unit = {}) = scope.launch {
        repository.upsertBackupLocation(current)
        // A folder replaced by another gives its grant back.
        val before = live?.folderUri.orEmpty()
        if (before != current.folderUri) releaseFolderUri(context, before, others + current)
        then()
    }

    ModalBottomSheet(
        onDismissRequest = cancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                WmIconTile(icon = destinationIcon(draft.type), accent = draft.type.accent)
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(destinationName(context, draft.type), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(destinationDescRes(draft.type)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StoredTextField(
                label = stringResource(R.string.backup_location_name_label),
                value = draft.name,
                supporting = stringResource(nameHintRes(draft.type)),
            ) { draft = draft.copy(name = it) }

            when (draft.type) {
                BackupDestination.FOLDER -> FolderPart(draft) { uri ->
                    draft = draft.copy(folderUri = uri)
                    testResult = null
                }
                BackupDestination.WEBDAV -> WebDavPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.S3 -> S3Part(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.FTP -> FtpPart(draft) {
                    draft = it
                    testResult = null
                }
                BackupDestination.DROPBOX, BackupDestination.ONEDRIVE ->
                    SignInPart(repository, current, onMessage)
                BackupDestination.DRIVE -> DrivePart(onMessage)
            }

            // Test: the readiness call a backup makes first, with what is on
            // screen now. The cheapest way to find a typo in a host name is
            // before the first night's backup fails on it.
            if (current.configured) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    OutlinedButton(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                val sink = AutoBackupRunner.sinkFor(context, current)
                                val failure = sink?.readiness()?.exceptionOrNull()
                                    ?: if (sink == null) BackupSinkException(SinkError.NOT_CONFIGURED) else null
                                testing = false
                                testResult = if (failure == null) {
                                    true to context.getString(R.string.backup_location_test_ok)
                                } else {
                                    val reason = (failure as? BackupSinkException)?.reason ?: SinkError.IO
                                    false to (
                                        autoBackupErrorText(context, reason.name, current.type)
                                            ?: context.getString(R.string.backup_auto_error_io)
                                        )
                                }
                            }
                        },
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.backup_location_testing))
                        } else {
                            Text(stringResource(R.string.backup_location_test))
                        }
                    }
                }
                testResult?.let { (ok, text) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) {
                        Icon(
                            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                TextButton(onClick = cancel) { Text(stringResource(CommonR.string.common_cancel)) }
                Button(onClick = { save(onDismiss) }) {
                    Text(stringResource(if (isNew) CommonR.string.common_add else CommonR.string.common_save))
                }
            }
        }
    }
}

/** The folder a Folder location writes to, and the button that picks it. */
@Composable
private fun FolderPart(draft: BackupLocation, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val taken = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (taken) onPicked(uri.toString())
    }
    WmRow(
        title = stringResource(R.string.backup_auto_folder_title),
        subtitle = draft.detail(context) ?: stringResource(R.string.backup_auto_folder_none),
        icon = SettingsRowIcons[R.string.backup_auto_folder_title],
        onClick = { launcher.launch(null) },
    )
    FilledTonalButton(
        onClick = { launcher.launch(null) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(
                if (draft.folderUri.isEmpty()) {
                    R.string.backup_location_folder_choose
                } else {
                    R.string.backup_location_folder_change
                },
            ),
        )
    }
}

@Composable
private fun WebDavPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    StoredTextField(
        label = stringResource(R.string.backup_auto_webdav_url_label),
        value = draft.webDavUrl,
        supporting = stringResource(R.string.backup_auto_webdav_url_hint),
        keyboardType = KeyboardType.Uri,
    ) { onChange(draft.copy(webDavUrl = it)) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_webdav_user_label),
        value = draft.webDavUser,
        supporting = "",
    ) { onChange(draft.copy(webDavUser = it)) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_webdav_password_label),
        value = draft.webDavPassword,
        supporting = stringResource(R.string.backup_auto_webdav_password_hint),
        password = true,
    ) { onChange(draft.copy(webDavPassword = it)) }
    if (draft.webDavUrl.isNotEmpty() && !draft.webDavUrl.trim().startsWith("https://", ignoreCase = true)) {
        StateBanner(stringResource(R.string.backup_auto_webdav_needs_https), tone = BannerTone.WARNING)
    }
}

@Composable
private fun S3Part(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val s3 = draft.s3
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_endpoint_label),
        value = s3.endpoint,
        supporting = stringResource(R.string.backup_auto_s3_endpoint_hint),
        keyboardType = KeyboardType.Uri,
    ) { onChange(draft.copy(s3 = s3.copy(endpoint = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_bucket_label),
        value = s3.bucket,
        supporting = "",
    ) { onChange(draft.copy(s3 = s3.copy(bucket = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_region_label),
        value = s3.region,
        supporting = stringResource(R.string.backup_auto_s3_region_hint),
    ) { onChange(draft.copy(s3 = s3.copy(region = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_prefix_label),
        value = s3.prefix,
        supporting = stringResource(R.string.backup_auto_s3_prefix_hint),
    ) { onChange(draft.copy(s3 = s3.copy(prefix = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_key_label),
        value = s3.accessKeyId,
        supporting = "",
    ) { onChange(draft.copy(s3 = s3.copy(accessKeyId = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_s3_secret_label),
        value = s3.secretAccessKey,
        supporting = "",
        password = true,
    ) { onChange(draft.copy(s3 = s3.copy(secretAccessKey = it))) }
    ToggleSetting(
        R.string.backup_auto_s3_path_style_title,
        stringResource(R.string.backup_auto_s3_path_style_subtitle),
        s3.pathStyle,
        default = SettingsDefaults.autoBackup.s3.pathStyle,
    ) { on -> onChange(draft.copy(s3 = s3.copy(pathStyle = on))) }
    if (S3Sink.isCleartext(s3.endpoint)) {
        StateBanner(stringResource(R.string.backup_auto_s3_cleartext), tone = BannerTone.WARNING)
    }
}

@Composable
private fun FtpPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val ftp = draft.ftp
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_host_label),
        value = ftp.host,
        supporting = "",
        keyboardType = KeyboardType.Uri,
    ) { onChange(draft.copy(ftp = ftp.copy(host = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_port_label),
        value = ftp.port.toString(),
        supporting = "",
        keyboardType = KeyboardType.Number,
    ) { entered -> entered.toIntOrNull()?.let { onChange(draft.copy(ftp = ftp.copy(port = it))) } }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_user_label),
        value = ftp.user,
        supporting = "",
    ) { onChange(draft.copy(ftp = ftp.copy(user = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_password_label),
        value = ftp.password,
        supporting = "",
        password = true,
    ) { onChange(draft.copy(ftp = ftp.copy(password = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_path_label),
        value = ftp.path,
        supporting = stringResource(R.string.backup_auto_ftp_path_hint),
    ) { onChange(draft.copy(ftp = ftp.copy(path = it))) }
    ToggleSetting(
        R.string.backup_auto_ftp_secure_title,
        stringResource(R.string.backup_auto_ftp_secure_subtitle),
        ftp.secure,
        default = SettingsDefaults.autoBackup.ftp.secure,
    ) { on -> onChange(draft.copy(ftp = ftp.copy(secure = on))) }
    if (!ftp.secure) {
        StateBanner(stringResource(R.string.backup_auto_ftp_cleartext), tone = BannerTone.WARNING)
    }
}

/**
 * Dropbox or OneDrive: who is signed in, a progress bar while the browser's
 * code is traded for a token, and the button.
 */
@Composable
private fun SignInPart(repository: SettingsRepository, location: BackupLocation, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exchanging by BackupOAuth.exchanging.collectAsStateWithLifecycle()
    val signingIn = exchanging == location.id
    val clientId = if (location.type == BackupDestination.DROPBOX) {
        BackupClients.dropboxClientId
    } else {
        BackupClients.oneDriveClientId
    }

    WmRow(
        title = stringResource(
            when {
                signingIn -> R.string.backup_auto_oauth_signing_in
                location.refreshToken.isNotEmpty() -> R.string.backup_auto_oauth_signed_in
                else -> R.string.backup_auto_oauth_signed_out
            },
        ),
        icon = SettingsRowIcons[R.string.backup_auto_dest_dropbox],
    )
    if (signingIn) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
    FilledTonalButton(
        enabled = !signingIn,
        onClick = {
            val activity = context.hostActivity() ?: return@FilledTonalButton
            if (location.refreshToken.isNotEmpty()) {
                scope.launch { repository.upsertBackupLocation(location.copy(refreshToken = "")) }
            } else if (!BackupOAuth.start(activity, location.type, clientId, location.id)) {
                onMessage(context.getString(R.string.backup_auto_oauth_no_browser))
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            stringResource(
                if (location.refreshToken.isNotEmpty()) {
                    R.string.backup_auto_oauth_sign_out
                } else {
                    R.string.backup_auto_oauth_sign_in
                },
            ),
        )
    }
    StateBanner(
        stringResource(
            if (location.type == BackupDestination.DROPBOX) {
                R.string.backup_auto_dropbox_info
            } else {
                R.string.backup_auto_onedrive_info
            },
        ),
    )
}

/** Google Drive: whether the app may use its hidden folder, and the button that asks. */
@Composable
private fun DrivePart(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = remember { driveAuthorizer() }
    var authorized by remember { mutableStateOf<Boolean?>(null) }
    var asking by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // Ask Google again: the result code alone says nothing reliable. No
        // grant after the consent screen closed is worth a sentence; see
        // BackupScreens history for the account picker that did nothing.
        scope.launch {
            val granted = authorizer.authorized(context)
            authorized = granted
            if (!granted) onMessage(context.getString(R.string.backup_auto_oauth_failed))
        }
    }
    LaunchedEffect(Unit) { authorized = authorizer.authorized(context) }

    WmRow(
        title = stringResource(
            when (authorized) {
                true -> R.string.backup_auto_drive_authorized
                false -> R.string.backup_auto_drive_not_authorized
                null -> R.string.backup_auto_drive_checking
            },
        ),
        icon = SettingsRowIcons[R.string.backup_auto_drive_title],
    )
    if (authorized != true) {
        FilledTonalButton(
            enabled = !asking,
            onClick = {
                val activity = context.hostActivity() ?: return@FilledTonalButton
                asking = true
                scope.launch {
                    val granted = authorizer.authorize(activity) { sender ->
                        consent.launch(IntentSenderRequest.Builder(sender).build())
                    }
                    asking = false
                    if (granted) authorized = true
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.backup_auto_drive_authorize)) }
    }
    StateBanner(stringResource(R.string.backup_auto_drive_info))
}
