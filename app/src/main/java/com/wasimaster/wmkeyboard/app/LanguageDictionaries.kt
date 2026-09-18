package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryEntry
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackDownloadManager
import com.wasimaster.wmkeyboard.core.dictionaries.WordlistDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictDownloadManager
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPacks
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.keywordsEnabledFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Dictionaries group on a language's screen: one row each for what the
 * language can read (its word list, its emoji keywords, its word pairs), and a
 * row to the user's own imported lists.
 *
 * Every row works the same way. Something on the device has a checkbox, which
 * is the "use it" switch, and a Delete. Something that is not has a Download.
 * The word list asks which size (and, for Portuguese, which variant) in a
 * dialog, because that is the one choice a download here has. The heading
 * carries a Download all while anything is still missing, and loses it once
 * nothing is.
 *
 * A row the language has nothing for is left out, rather than shown to say so.
 */
@Composable
internal fun DictionariesGroup(
    lang: LanguageDef,
    settings: KeyboardSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val langId = lang.id
    val notifyDownload = rememberDownloadNotifier()

    val wordlists = remember(langId) { DictionaryCatalog.forLanguage(langId) }
    val emojiEntry = remember(langId) { EmojiDictCatalog.forLanguage(langId) }
    val pairsEntry = remember(langId) { NgramPackCatalog.forLanguage(langId) }

    val wordStates by WordlistDownloadManager.states.collectAsState()
    val emojiStates by EmojiDictDownloadManager.states.collectAsState()
    val pairStates by NgramPackDownloadManager.states.collectAsState()
    LaunchedEffect(langId) {
        WordlistDownloadManager.refresh(filesDir)
        EmojiDictDownloadManager.refresh(filesDir)
        NgramPackDownloadManager.refresh(filesDir)
    }

    // Portuguese has two lists in one slot: the row speaks for whichever is
    // doing something, else whichever is on the device, else the default one.
    val wordEntry = wordlists.firstOrNull { wordStates[it.id].isActive() }
        ?: wordlists.firstOrNull { wordStates[it.id] is WordlistDownloadManager.DownloadStatus.Downloaded }
        ?: wordlists.firstOrNull { it.id == langId }
        ?: wordlists.firstOrNull()
    val wordStatus = wordEntry?.let { wordStates[it.id] }
        ?: WordlistDownloadManager.DownloadStatus.NotDownloaded
    val emojiStatus = emojiEntry?.let { emojiStates[it.languageId] }
        ?: EmojiDictDownloadManager.DownloadStatus.NotDownloaded
    val pairStatus = pairsEntry?.let { pairStates[it.languageId] }
        ?: NgramPackDownloadManager.DownloadStatus.NotDownloaded
    val emojiImported = remember(langId, emojiStatus) {
        EmojiKeywordPacks.packs(filesDir, langId).isNotEmpty()
    }

    val size = settings.appUi.defaultWordlistSize
    // Only what is neither on the device nor on its way: a second press, or a
    // press while one row is already downloading, must not fetch it twice.
    val missing = LanguageData(
        wordlist = wordEntry?.takeIf { wordStatus.isMissing() },
        emojiDict = emojiEntry?.takeIf { emojiStatus.isMissing() },
        ngram = pairsEntry?.takeIf { pairStatus.isMissing() },
        bytes = 0L,
    ).let { it.copy(bytes = it.approxBytes(size)) }

    var dialogOpen by remember { mutableStateOf(false) }
    var confirmMetered by remember { mutableStateOf(false) }
    var blockedMetered by remember { mutableStateOf(false) }
    val downloadDecision = rememberDownloadDecision(settings)
    val downloadAll = { startLanguageDataDownload(context, missing, notifyDownload, size) }

    SettingsGroup(
        stringResource(R.string.languages_dictionaries_title),
        info = stringResource(R.string.languages_dictionaries_info),
        action = if (missing.isEmpty) {
            null
        } else {
            {
                TextButton(
                    onClick = {
                        when (downloadDecision()) {
                            MeteredDecision.ASK -> confirmMetered = true
                            MeteredDecision.BLOCKED -> blockedMetered = true
                            MeteredDecision.ALLOWED -> downloadAll()
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(
                            R.string.languages_dictionaries_download_all_action,
                            formatBytes(missing.bytes),
                        ),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        },
    ) {
        if (wordEntry != null || lang.bundledDictionary) {
            item {
                WordListRow(
                    lang = lang,
                    entry = wordEntry,
                    status = wordStatus,
                    checked = settings.suggestionStrip.shippedDictionaryEnabledFor(langId),
                    onChecked = { scope.launch { repository.setShippedDictionaryEnabled(langId, it) } },
                    onDownload = { dialogOpen = true },
                )
            }
        }
        if (emojiEntry != null || emojiImported) {
            item {
                EmojiKeywordsRow(
                    langId = langId,
                    status = emojiStatus,
                    available = emojiEntry != null,
                    imported = emojiImported,
                    checked = settings.emoji.keywordsEnabledFor(langId),
                    onChecked = { scope.launch { repository.setEmojiKeywordsEnabled(langId, it) } },
                )
            }
        }
        if (pairsEntry != null) {
            item {
                WordPairsRow(
                    langId = langId,
                    approxBytes = pairsEntry.approxGzBytes,
                    status = pairStatus,
                    checked = settings.suggestionStrip.wordPairsEnabledFor(langId),
                    onChecked = { scope.launch { repository.setWordPairsEnabled(langId, it) } },
                )
            }
        }
        item {
            NavRow(
                R.string.languages_custom_dictionaries_title,
                stringResource(R.string.languages_custom_dictionaries_subtitle),
                route = "customdictionaries",
            ) { onNavigate("customdictionaries") }
        }
    }

    if (dialogOpen && wordlists.isNotEmpty()) {
        WordListDownloadDialog(
            entries = wordlists,
            initial = wordEntry ?: wordlists.first(),
            initialSize = size,
            pairsBytes = pairsEntry?.takeIf { pairStatus.isMissing() }?.approxGzBytes,
            onDismiss = { dialogOpen = false },
            onDownload = { entry, chosen ->
                dialogOpen = false
                scope.launch { repository.setDefaultWordlistSize(chosen) }
                startLanguageDataDownload(
                    context,
                    LanguageData(
                        wordlist = entry,
                        emojiDict = null,
                        ngram = pairsEntry?.takeIf { pairStatus.isMissing() },
                        bytes = 0L,
                    ),
                    notifyDownload,
                    chosen,
                )
            },
        )
    }
    if (confirmMetered) {
        MeteredDownloadDialog(
            detail = stringResource(R.string.languages_metered_confirm_body, formatBytes(missing.bytes)),
            onConfirm = {
                confirmMetered = false
                downloadAll()
            },
            onDismiss = { confirmMetered = false },
        )
    }
    if (blockedMetered) MeteredBlockedDialog { blockedMetered = false }
}

private fun WordlistDownloadManager.DownloadStatus?.isActive(): Boolean =
    this is WordlistDownloadManager.DownloadStatus.Downloading ||
        this == WordlistDownloadManager.DownloadStatus.Processing

private fun WordlistDownloadManager.DownloadStatus.isMissing(): Boolean =
    this is WordlistDownloadManager.DownloadStatus.NotDownloaded ||
        this is WordlistDownloadManager.DownloadStatus.Failed

private fun EmojiDictDownloadManager.DownloadStatus.isMissing(): Boolean =
    this is EmojiDictDownloadManager.DownloadStatus.NotDownloaded ||
        this is EmojiDictDownloadManager.DownloadStatus.Failed

private fun NgramPackDownloadManager.DownloadStatus.isMissing(): Boolean =
    this == NgramPackDownloadManager.DownloadStatus.NotDownloaded ||
        this == NgramPackDownloadManager.DownloadStatus.Failed

/**
 * One row of the group: the checkbox when [checked] is not null (the item is
 * on the device), the title and [supporting] line, and [trailing] actions. A
 * press anywhere on a row with a checkbox flips it, the way a settings row
 * with a switch does.
 */
@Composable
private fun DictionaryItemRow(
    title: String,
    supporting: String,
    checked: Boolean?,
    onChecked: (Boolean) -> Unit,
    error: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    WmRow(
        title = title,
        leading = checked?.let { { Checkbox(checked = it, onCheckedChange = null) } },
        supporting = {
            Text(
                supporting,
                color = if (error) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        trailing = trailing,
        onClick = checked?.let { { onChecked(!it) } },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun WordListRow(
    lang: LanguageDef,
    entry: DictionaryEntry?,
    status: WordlistDownloadManager.DownloadStatus,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onDownload: () -> Unit,
) {
    val filesDir = LocalContext.current.filesDir
    val onDevice = lang.bundledDictionary || status is WordlistDownloadManager.DownloadStatus.Downloaded
    val title = entry?.variantRes
        ?.takeIf { status is WordlistDownloadManager.DownloadStatus.Downloaded || status.isActive() }
        ?.let { stringResource(R.string.languages_words_title_variant, stringResource(it)) }
        ?: stringResource(R.string.languages_words_title)
    val supporting = when (status) {
        is WordlistDownloadManager.DownloadStatus.Downloaded -> pluralStringResource(
            R.plurals.languages_wordlist_downloaded,
            status.wordCount,
            status.wordCount,
            formatBytes(status.sizeBytes),
        )
        WordlistDownloadManager.DownloadStatus.Processing ->
            stringResource(R.string.languages_wordlist_preparing)
        is WordlistDownloadManager.DownloadStatus.Downloading ->
            stringResource(R.string.languages_wordlist_downloaded_bytes, formatBytes(status.bytes))
        is WordlistDownloadManager.DownloadStatus.Failed ->
            if (status.messageArg.isEmpty()) stringResource(status.messageRes)
            else stringResource(status.messageRes, status.messageArg)
        WordlistDownloadManager.DownloadStatus.NotDownloaded -> when {
            lang.bundledDictionary && entry != null -> stringResource(R.string.languages_words_builtin_more)
            lang.bundledDictionary -> stringResource(R.string.languages_words_builtin)
            else -> stringResource(R.string.languages_words_missing)
        }
    }
    DictionaryItemRow(
        title = title,
        supporting = supporting,
        checked = if (onDevice) checked else null,
        onChecked = onChecked,
        error = status is WordlistDownloadManager.DownloadStatus.Failed,
    ) {
        when (status) {
            is WordlistDownloadManager.DownloadStatus.Downloaded ->
                if (entry != null) {
                    IconButton(onClick = { WordlistDownloadManager.delete(filesDir, entry) }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.languages_wordlist_delete_desc),
                        )
                    }
                }
            is WordlistDownloadManager.DownloadStatus.Downloading,
            WordlistDownloadManager.DownloadStatus.Processing,
            -> IconButton(onClick = { WordlistDownloadManager.cancel() }) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.languages_cancel_download_desc),
                )
            }
            WordlistDownloadManager.DownloadStatus.NotDownloaded,
            is WordlistDownloadManager.DownloadStatus.Failed,
            -> if (entry != null) {
                TextButton(onClick = onDownload, enabled = !WordlistDownloadManager.isBusy) {
                    Text(
                        stringResource(
                            if (status is WordlistDownloadManager.DownloadStatus.Failed) CommonR.string.common_retry
                            else CommonR.string.common_download,
                        ),
                    )
                }
            }
        }
    }
    // Indeterminate on purpose: a capped download stops early, so
    // bytes-of-total would count to a total it never reaches.
    if (status.isActive()) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmojiKeywordsRow(
    langId: String,
    status: EmojiDictDownloadManager.DownloadStatus,
    available: Boolean,
    imported: Boolean,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val notifyDownload = rememberDownloadNotifier()
    val downloadName = stringResource(
        R.string.notify_download_emoji_names,
        LanguageRegistry.byId(langId).displayName,
    )
    val entry = EmojiDictCatalog.forLanguage(langId)
    val downloaded = status is EmojiDictDownloadManager.DownloadStatus.Downloaded
    val supporting = when (status) {
        is EmojiDictDownloadManager.DownloadStatus.Downloaded -> pluralStringResource(
            R.plurals.languages_emoji_dict_downloaded,
            status.emojiCount,
            status.emojiCount,
            formatBytes(status.sizeBytes),
        )
        EmojiDictDownloadManager.DownloadStatus.Queued -> stringResource(R.string.languages_download_queued)
        is EmojiDictDownloadManager.DownloadStatus.Downloading ->
            stringResource(CommonR.string.common_downloading)
        is EmojiDictDownloadManager.DownloadStatus.Failed ->
            if (status.messageArg.isEmpty()) stringResource(status.messageRes)
            else stringResource(status.messageRes, status.messageArg)
        EmojiDictDownloadManager.DownloadStatus.NotDownloaded -> when {
            entry != null -> pluralStringResource(
                R.plurals.languages_emoji_dict_download_size,
                entry.emojiCount,
                entry.emojiCount,
                formatBytes(entry.approxGzBytes),
            )
            else -> stringResource(R.string.languages_emoji_keywords_imported)
        }
    }
    DictionaryItemRow(
        title = stringResource(R.string.languages_emoji_keywords_title),
        supporting = supporting,
        checked = if (downloaded || imported) checked else null,
        onChecked = onChecked,
        error = status is EmojiDictDownloadManager.DownloadStatus.Failed,
    ) {
        when (status) {
            is EmojiDictDownloadManager.DownloadStatus.Downloaded ->
                IconButton(onClick = { EmojiDictDownloadManager.delete(filesDir, langId) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.languages_emoji_dict_delete_desc),
                    )
                }
            EmojiDictDownloadManager.DownloadStatus.Queued,
            is EmojiDictDownloadManager.DownloadStatus.Downloading,
            -> IconButton(onClick = { EmojiDictDownloadManager.cancel(langId) }) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.languages_cancel_download_desc),
                )
            }
            EmojiDictDownloadManager.DownloadStatus.NotDownloaded,
            is EmojiDictDownloadManager.DownloadStatus.Failed,
            -> if (available && entry != null) {
                TextButton(
                    onClick = {
                        EmojiDictDownloadManager.start(filesDir, entry)
                        notifyDownload(langId, downloadName, DownloadProgressFlows.emojiDict(context, langId))
                    },
                ) {
                    Text(
                        stringResource(
                            if (status is EmojiDictDownloadManager.DownloadStatus.Failed) CommonR.string.common_retry
                            else CommonR.string.common_download,
                        ),
                    )
                }
            }
        }
    }
    val downloading = status as? EmojiDictDownloadManager.DownloadStatus.Downloading
    if (downloading != null) {
        LinearProgressIndicator(
            progress = {
                if (downloading.totalBytes > 0) {
                    (downloading.bytes.toFloat() / downloading.totalBytes).coerceIn(0f, 1f)
                } else {
                    0f
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WordPairsRow(
    langId: String,
    approxBytes: Long,
    status: NgramPackDownloadManager.DownloadStatus,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val filesDir = LocalContext.current.filesDir
    val supporting = when (status) {
        is NgramPackDownloadManager.DownloadStatus.Downloaded ->
            stringResource(R.string.languages_word_pairs_downloaded, formatBytes(status.sizeBytes))
        NgramPackDownloadManager.DownloadStatus.Downloading -> stringResource(CommonR.string.common_downloading)
        NgramPackDownloadManager.DownloadStatus.Failed -> stringResource(R.string.languages_word_pairs_failed)
        NgramPackDownloadManager.DownloadStatus.NotDownloaded ->
            stringResource(R.string.languages_word_pairs_download_size, formatBytes(approxBytes))
    }
    DictionaryItemRow(
        title = stringResource(R.string.languages_word_pairs_title),
        supporting = supporting,
        checked = if (status is NgramPackDownloadManager.DownloadStatus.Downloaded) checked else null,
        onChecked = onChecked,
        error = status == NgramPackDownloadManager.DownloadStatus.Failed,
    ) {
        when (status) {
            is NgramPackDownloadManager.DownloadStatus.Downloaded ->
                IconButton(onClick = { NgramPackDownloadManager.delete(filesDir, langId) }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.languages_word_pairs_delete_desc),
                    )
                }
            // No cancel: the pack compiles in one blocking pass that a
            // cancel cannot stop, and a button that does nothing is worse
            // than none. It is a megabyte or two.
            NgramPackDownloadManager.DownloadStatus.Downloading -> Unit
            NgramPackDownloadManager.DownloadStatus.NotDownloaded,
            NgramPackDownloadManager.DownloadStatus.Failed,
            -> TextButton(onClick = { NgramPackDownloadManager.start(filesDir, langId) }) {
                Text(
                    stringResource(
                        if (status == NgramPackDownloadManager.DownloadStatus.Failed) CommonR.string.common_retry
                        else CommonR.string.common_download,
                    ),
                )
            }
        }
    }
    if (status == NgramPackDownloadManager.DownloadStatus.Downloading) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * What Download on the word list asks: which size, and for a language with
 * more than one list, which variant. Each size says how many words it keeps
 * and roughly what it costs to fetch. [pairsBytes] is the word-pair data that
 * comes along with it, when the language has some that is not on the device.
 */
@Composable
private fun WordListDownloadDialog(
    entries: List<DictionaryEntry>,
    initial: DictionaryEntry,
    initialSize: DictionaryCatalog.DictionarySize,
    pairsBytes: Long?,
    onDismiss: () -> Unit,
    onDownload: (DictionaryEntry, DictionaryCatalog.DictionarySize) -> Unit,
) {
    var entry by remember { mutableStateOf(initial) }
    // Tiers past the end of a short list all keep the same words, so only the
    // first one that reaches the whole list is worth offering.
    val sizes = remember(entry) {
        DictionaryCatalog.DictionarySize.entries.distinctBy { DictionaryCatalog.wordCap(entry, it) }
    }
    var size by remember { mutableStateOf(initialSize) }
    val chosen = sizes.firstOrNull { DictionaryCatalog.wordCap(entry, it) == DictionaryCatalog.wordCap(entry, size) }
        ?: sizes.last()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.languages_words_download_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (entries.size > 1) {
                    DialogLabel(stringResource(R.string.languages_words_download_variant_label))
                    for (option in entries) {
                        ChoiceLine(
                            selected = option == entry,
                            label = option.variantRes?.let { stringResource(it) }
                                ?: LanguageRegistry.byId(option.languageId).displayName,
                        ) { entry = option }
                    }
                    DialogLabel(stringResource(R.string.languages_words_download_size_label))
                }
                for (option in sizes) {
                    val words = DictionaryCatalog.wordCap(entry, option)
                    ChoiceLine(
                        selected = option == chosen,
                        label = stringResource(option.labelRes),
                        detail = pluralStringResource(
                            R.plurals.languages_words_download_size_detail,
                            words,
                            words,
                            formatBytes(entryBytes(entry, option)),
                        ),
                    ) { size = option }
                }
                if (pairsBytes != null) {
                    Text(
                        stringResource(R.string.languages_words_download_pairs_note, formatBytes(pairsBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(entry, chosen) }) {
                Text(stringResource(CommonR.string.common_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun DialogLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChoiceLine(selected: Boolean, label: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
