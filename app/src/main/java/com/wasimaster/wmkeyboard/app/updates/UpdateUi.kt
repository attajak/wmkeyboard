package com.wasimaster.wmkeyboard.app.updates

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.MeteredBlockedDialog
import com.wasimaster.wmkeyboard.app.MeteredDownloadDialog
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.SettingsRowIcons
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.downloadDecisionNow
import com.wasimaster.wmkeyboard.app.isMeteredNow
import com.wasimaster.wmkeyboard.app.rememberDisclosedSpecialAccess
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision

/**
 * The update card on the settings home screen.
 *
 * Draws nothing unless there is something to act on, which is most of the
 * time: an up-to-date install sits on [UpdateState.UpToDate] and a build with
 * no update source behind it on [UpdateState.Unsupported]. A failed check
 * draws nothing either. The About row is where a retry belongs, not the front
 * page.
 */
@Composable
internal fun UpdateCard(settings: KeyboardSettings, modifier: Modifier = Modifier) {
    val updater = LocalAppUpdater.current
    val state by updater.state.collectAsStateWithLifecycle()
    when (val current = state) {
        is UpdateState.Available ->
            if (current.dismissed) Unit else AvailableCard(current, updater, settings, modifier)
        is UpdateState.Downloading -> DownloadingCard(current, updater, modifier)
        UpdateState.Downloaded -> ReadyCard(updater, modifier)
        UpdateState.Installing -> InstallingCard(updater, modifier)
        UpdateState.Unsupported,
        UpdateState.Idle,
        UpdateState.Checking,
        is UpdateState.UpToDate,
        is UpdateState.Failed,
        -> Unit
    }
}

/**
 * "Updated to 0.5.3", once, after this app has replaced itself.
 *
 * The reason it exists is the quiet install: from Android 12 an update this
 * app installs can finish with no screen of its own, so without this the whole
 * thing would happen with no sign that anything had. Reads and clears in one
 * go, so it appears in exactly one composition of one launch.
 */
@Composable
internal fun UpdatedCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val justUpdated = remember {
        UpdatePrefs(context).takeJustUpdated(BuildConfig.VERSION_CODE, System.currentTimeMillis())
    }
    var showing by remember { mutableStateOf(justUpdated) }
    if (!showing) return
    UpdateCardFrame(
        title = stringResource(R.string.update_card_updated_title, BuildConfig.VERSION_NAME),
        body = stringResource(R.string.update_card_updated_body),
        modifier = modifier,
    ) {
        Button(onClick = { showing = false }) {
            Text(stringResource(R.string.update_action_dismiss))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = {
                uriHandler.openUri(GithubReleases.releasePage("v" + BuildConfig.VERSION_NAME))
            },
        ) {
            Text(stringResource(R.string.update_action_notes))
        }
    }
}

/**
 * The offer, put in front of the user once rather than waited for on a card
 * they may never scroll to.
 *
 * Hosted at the root of the settings activity rather than inside [UpdateCard],
 * which only composes on the home screen: a dialog that could only open on one
 * screen would open on whichever screen the user happened not to be on.
 */
@Composable
internal fun UpdatePromptDialog(settings: KeyboardSettings) {
    val updater = LocalAppUpdater.current
    val state by updater.state.collectAsStateWithLifecycle()
    val notes by updater.releaseNotes.collectAsStateWithLifecycle()
    val available = state as? UpdateState.Available ?: return
    if (!available.promptOpen) return
    val download = rememberUpdateDownloadRequest(updater, available.sizeBytes, settings)
    var showNotes by remember(available.versionCode) { mutableStateOf(false) }
    if (showNotes) {
        ReleaseNotesDialog(
            version = available.versionName,
            notes = notes,
            onDismiss = { showNotes = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = updater::dismiss,
        title = { Text(stringResource(R.string.update_card_available_title)) },
        text = { Text(availableBody(available, updater)) },
        confirmButton = {
            TextButton(onClick = download) { Text(startActionLabel(updater)) }
        },
        dismissButton = {
            Row {
                // The notes are their own dialog rather than more text in this
                // one: an offer the user has to read past to reach the button
                // is a worse offer.
                if (updater.supportsNotes) {
                    TextButton(
                        onClick = {
                            showNotes = true
                            updater.loadNotes()
                        },
                    ) {
                        Text(stringResource(R.string.update_action_notes))
                    }
                }
                TextButton(onClick = updater::dismiss) {
                    Text(stringResource(R.string.update_action_later))
                }
            }
        },
    )
}

@Composable
private fun AvailableCard(
    state: UpdateState.Available,
    updater: AppUpdater,
    settings: KeyboardSettings,
    modifier: Modifier = Modifier,
) {
    val notes by updater.releaseNotes.collectAsStateWithLifecycle()
    var showNotes by remember(state.versionCode) { mutableStateOf(false) }
    val download = rememberUpdateDownloadRequest(updater, state.sizeBytes, settings)
    if (showNotes) {
        ReleaseNotesDialog(
            version = state.versionName,
            notes = notes,
            onDismiss = { showNotes = false },
        )
    }
    UpdateCardFrame(
        title = stringResource(R.string.update_card_available_title),
        body = availableBody(state, updater),
        modifier = modifier,
    ) {
        Button(onClick = download) { Text(startActionLabel(updater)) }
        // An urgent release gets no "Not now": the button would write a snooze
        // that [UpdatePolicy] ignores for that release anyway, so it would be
        // a control that does nothing.
        if (!state.immediate) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = updater::dismiss) {
                Text(stringResource(R.string.update_action_later))
            }
        }
        // Gated on the updater rather than on there being a page to link to:
        // F-Droid has the page and no notes behind it. See [AppUpdater.supportsNotes].
        if (updater.supportsNotes) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    showNotes = true
                    updater.loadNotes()
                },
            ) {
                Text(stringResource(R.string.update_action_notes))
            }
        }
    }
}

@Composable
private fun DownloadingCard(
    state: UpdateState.Downloading,
    updater: AppUpdater,
    modifier: Modifier = Modifier,
) {
    val fraction = state.fraction
    UpdateCardFrame(
        title = stringResource(R.string.update_card_downloading_title),
        // Nothing to say yet while the download is only queued: there are no
        // byte counts until it starts, and "0 B of 0 B" is worse than silence.
        body = state.progressText(),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            // Only where this app is the one downloading. A store's download
            // is the store's to stop.
            if (updater.ownsDownload) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = updater::cancel) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            }
        }
    }
}

@Composable
private fun ReadyCard(updater: AppUpdater, modifier: Modifier = Modifier) {
    val grant = updater.installGrant
    val requestGrant = rememberDisclosedSpecialAccess(SpecialAccess.INSTALL_UPDATES)
    UpdateCardFrame(
        title = stringResource(R.string.update_card_ready_title),
        // The warning is the point of this card. Installing kills the process
        // the keyboard runs in, and on a build that installs for itself Android
        // may not show a screen of its own at all, so this sentence is the only
        // warning the user gets. See [AppUpdater].
        body = stringResource(
            if (updater.ownsDownload) {
                R.string.update_card_ready_body_self
            } else {
                R.string.update_card_ready_body
            },
        ),
        modifier = modifier,
    ) {
        Button(
            onClick = {
                // Records the press either way. When Android still has to be
                // told this app may install, the disclosure goes first and the
                // install picks up on the way back.
                updater.install()
                if (grant != null) requestGrant()
            },
        ) {
            Text(stringResource(R.string.update_action_install))
        }
    }
}

@Composable
private fun InstallingCard(updater: AppUpdater, modifier: Modifier = Modifier) {
    UpdateCardFrame(
        title = stringResource(R.string.update_card_installing_title),
        body = stringResource(
            if (updater.ownsDownload) {
                R.string.update_card_installing_body_self
            } else {
                R.string.update_card_installing_body
            },
        ),
        modifier = modifier,
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The shape every update card shares: a title, a line of prose, then controls.
 *
 * [busy] draws an endless bar under the words, for the wait that has no size to
 * measure — fetching the release notes is the only one. A download's own bar is
 * a control, and lives with the controls.
 */
@Composable
private fun UpdateCardFrame(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    controls: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Row { controls() }
        }
    }
}

/**
 * Starting a download, with the user's data saving setting in the way.
 *
 * Only where this app does the downloading: a store runs its own data policy,
 * and asking twice about one download would be this app second-guessing it.
 * The check itself is never gated, being a few kilobytes of JSON.
 */
@Composable
private fun rememberUpdateDownloadRequest(
    updater: AppUpdater,
    sizeBytes: Long,
    settings: KeyboardSettings,
): () -> Unit {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    if (blocked) MeteredBlockedDialog { blocked = false }
    if (confirming) {
        val size = Formatter.formatShortFileSize(context, sizeBytes)
        MeteredDownloadDialog(
            detail = stringResource(R.string.update_metered_body, size),
            onConfirm = {
                confirming = false
                updater.start()
            },
            onDismiss = { confirming = false },
        )
    }
    return {
        if (!updater.ownsDownload) {
            updater.start()
        } else {
            when (downloadDecisionNow(context, settings)) {
                MeteredDecision.BLOCKED -> blocked = true
                MeteredDecision.ASK -> confirming = true
                MeteredDecision.ALLOWED ->
                    if (isMeteredNow(context)) confirming = true else updater.start()
            }
        }
    }
}

/** What the button that starts an update says, which depends on where it goes. */
@Composable
private fun startActionLabel(updater: AppUpdater): String = when {
    updater.startsExternally ->
        stringResource(R.string.update_action_open_source, stringResource(updater.sourceNameRes))
    updater.ownsDownload -> stringResource(R.string.update_action_download)
    else -> stringResource(R.string.update_action_update)
}

/** The sentence describing what is on offer, with a version when there is one. */
@Composable
private fun availableBody(state: UpdateState.Available, updater: AppUpdater): String {
    val source = stringResource(updater.sourceNameRes)
    return when {
        state.immediate -> stringResource(R.string.update_card_urgent_body, source)
        state.versionName.isNullOrBlank() ->
            stringResource(R.string.update_card_available_body, source)
        else -> stringResource(
            R.string.update_card_available_body_versioned,
            state.versionName,
            source,
        )
    }
}

/** "12 MB of 30 MB", or null while the size is still unknown. */
@Composable
private fun UpdateState.Downloading.progressText(): String? {
    if (totalBytes <= 0L) return null
    val context = LocalContext.current
    return stringResource(
        R.string.update_progress_body,
        Formatter.formatShortFileSize(context, bytesDownloaded),
        Formatter.formatShortFileSize(context, totalBytes),
    )
}

/**
 * The Updates group on the About screen: one row that always says where things
 * stand, and the settings that decide what arrives uninvited.
 *
 * The row's action follows its state rather than always meaning "check". A row
 * that reads "Install the update" and then only re-checks for one would be
 * lying about what pressing it does.
 */
@Composable
internal fun UpdateSettings(settings: KeyboardSettings) {
    val updater = LocalAppUpdater.current
    val state by updater.state.collectAsStateWithLifecycle()
    if (state is UpdateState.Unsupported) return

    // These settings live in SharedPreferences, not in the settings DataStore,
    // so they are not snapshot state and a write would not recompose anything
    // on its own. Each switch keeps its own copy and writes through.
    val autoPrompt = remember { mutableStateOf(updater.autoPrompt) }
    val prereleases = remember { mutableStateOf(updater.includePrereleases) }

    SettingsGroup(stringResource(R.string.update_section_title)) {
        item { UpdateRow(state, updater, settings) }
        (state as? UpdateState.Available)?.releaseUrl?.let { url ->
            item { ReleasePageRow(url) }
        }
        item {
            ToggleSetting(
                R.string.update_row_prompts_title,
                stringResource(R.string.update_row_prompts_subtitle),
                checked = autoPrompt.value,
                default = UpdatePrefs.DEFAULT_AUTO_PROMPT,
                onChange = {
                    autoPrompt.value = it
                    updater.autoPrompt = it
                },
            )
        }
        if (updater.supportsPrereleases) {
            item {
                ToggleSetting(
                    R.string.update_row_prereleases_title,
                    stringResource(R.string.update_row_prereleases_subtitle),
                    checked = prereleases.value,
                    default = UpdatePrefs.DEFAULT_PRERELEASES,
                    onChange = {
                        prereleases.value = it
                        updater.includePrereleases = it
                    },
                )
            }
        }
    }
}

@Composable
private fun UpdateRow(state: UpdateState, updater: AppUpdater, settings: KeyboardSettings) {
    val context = LocalContext.current
    when (state) {
        is UpdateState.Available -> AvailableRow(state, updater, settings, context)
        // Nothing to press while the download runs, so this is a plain row
        // rather than a [NavRow]: a chevron would promise an action that is
        // not there.
        is UpdateState.Downloading -> ProgressRow(
            R.string.update_row_downloading_title,
            state.progressText(),
            state.fraction,
        )
        UpdateState.Downloaded -> InstallRow(updater)
        // An install reports no progress of its own, so the bar runs
        // indeterminate: the row's job here is to say the app is working, not
        // to claim it knows how far along Android is.
        UpdateState.Installing -> ProgressRow(R.string.update_row_installing_title, null, null)
        // Every quiet state, where the row's job is to offer a check and to
        // report the last one.
        UpdateState.Unsupported,
        UpdateState.Idle,
        UpdateState.Checking,
        is UpdateState.UpToDate,
        is UpdateState.Failed,
        -> NavRow(
            R.string.update_row_check_title,
            subtitle = stringResource(state.checkSubtitle()),
            onClick = { updater.check(userAsked = true) },
        )
    }
}

@Composable
private fun AvailableRow(
    state: UpdateState.Available,
    updater: AppUpdater,
    settings: KeyboardSettings,
    context: Context,
) {
    val download = rememberUpdateDownloadRequest(updater, state.sizeBytes, settings)
    if (updater.startsExternally) {
        NavRow(
            R.string.update_row_available_title_external,
            subtitle = stringResource(
                R.string.update_row_available_subtitle_external,
                state.versionName.orEmpty(),
                stringResource(updater.sourceNameRes),
            ),
            onClick = download,
        )
        return
    }
    val size = Formatter.formatShortFileSize(context, state.sizeBytes)
    NavRow(
        R.string.update_row_available_title,
        subtitle = if (state.sizeBytes > 0L) {
            stringResource(R.string.update_row_available_subtitle, state.versionCode, size)
        } else {
            stringResource(R.string.update_row_available_subtitle_plain, state.versionCode)
        },
        onClick = download,
    )
}

@Composable
private fun InstallRow(updater: AppUpdater) {
    val grant = updater.installGrant
    val requestGrant = rememberDisclosedSpecialAccess(SpecialAccess.INSTALL_UPDATES)
    NavRow(
        R.string.update_row_install_title,
        subtitle = stringResource(R.string.update_row_install_subtitle),
        onClick = {
            updater.install()
            if (grant != null) requestGrant()
        },
    )
}

@Composable
private fun ReleasePageRow(url: String) {
    val uriHandler = LocalUriHandler.current
    NavRow(
        R.string.update_row_release_page_title,
        subtitle = stringResource(R.string.update_row_release_page_subtitle),
        onClick = { uriHandler.openUri(url) },
    )
}

/**
 * A row that reports work in progress and cannot be pressed, with the bar its
 * words cannot draw.
 *
 * "12 MB of 30 MB" is only as informative as the arithmetic the reader does on
 * it, and while the total is unknown it says nothing at all. The bar under the
 * row is the same shape every other download in this app draws, so an update
 * in flight looks like a dictionary or a model in flight.
 *
 * [fraction] is null for work whose end is not known — an install, or a
 * download whose source never reported a size — and the bar then runs
 * indeterminate rather than sitting convincingly at zero.
 */
@Composable
private fun ProgressRow(@StringRes title: Int, subtitle: String?, fraction: Float?) {
    Column {
        WmRow(
            title = stringResource(title),
            subtitle = subtitle,
            icon = SettingsRowIcons[title],
            highlightKey = title,
        )
        if (fraction == null) {
            LinearProgressIndicator(modifier = ProgressBarModifier)
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = ProgressBarModifier)
        }
    }
}

/**
 * Where the progress bar sits under its row: inset to the row's own text
 * margin, with enough air under it to keep it off the next row's title.
 */
private val ProgressBarModifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 16.dp, vertical = 4.dp)

/** What the "Check for updates" row says under its name, for the quiet states. */
@StringRes
private fun UpdateState.checkSubtitle(): Int = when (this) {
    UpdateState.Checking -> R.string.update_row_checking_subtitle
    is UpdateState.UpToDate -> R.string.update_row_current_subtitle
    // A cancelled flow is the user's own answer, so the row goes back to its
    // resting words rather than reporting their decision back at them.
    is UpdateState.Failed -> if (cancelled) R.string.update_row_check_subtitle else reason.subtitle()
    UpdateState.Unsupported,
    UpdateState.Idle,
    UpdateState.Downloaded,
    UpdateState.Installing,
    is UpdateState.Available,
    is UpdateState.Downloading,
    -> R.string.update_row_check_subtitle
}

/**
 * One sentence per way an update can fail, because they ask for different
 * things: wait, make room, try again, or go and fetch it by hand.
 *
 * Internal rather than private because the notification the download posts
 * says the same sentences, and a shade that explained a failure differently
 * from the row behind it would be two answers to one question.
 */
@StringRes
internal fun UpdateFailure?.subtitle(): Int = when (this) {
    UpdateFailure.RATE_LIMITED -> R.string.update_row_failed_rate_limited_subtitle
    UpdateFailure.NO_ASSET -> R.string.update_row_failed_no_asset_subtitle
    UpdateFailure.NO_SPACE -> R.string.update_row_failed_no_space_subtitle
    UpdateFailure.CORRUPT -> R.string.update_row_failed_corrupt_subtitle
    UpdateFailure.SIGNATURE_MISMATCH -> R.string.update_row_failed_signature_subtitle
    UpdateFailure.INSTALL_BLOCKED -> R.string.update_row_failed_blocked_subtitle
    UpdateFailure.INSTALL_FAILED -> R.string.update_row_failed_install_subtitle
    UpdateFailure.NETWORK, null -> R.string.update_row_failed_subtitle
}
