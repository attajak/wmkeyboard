package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperLanguages
import kotlinx.coroutines.launch

/**
 * The languages dictation listens for (#416), as a row on the Voice typing
 * screen. Empty follows the keyboard, which is how dictation has always
 * worked; the dialog picks one language to hold whatever the layout, or
 * several for a recognizer to tell apart.
 */
@Composable
internal fun VoiceLanguageRow(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val chosen = settings.watch { it.whisper.languages }
    var open by remember { mutableStateOf(false) }
    NavRow(
        R.string.voice_languages_title,
        subtitle = stringResource(R.string.voice_languages_subtitle),
        value = if (chosen.isEmpty()) {
            stringResource(R.string.voice_languages_follow)
        } else {
            chosen.joinToString(" + ") { LanguageRegistry.byId(it).englishName }
        },
    ) { open = true }
    if (open) {
        VoiceLanguagesDialog(
            chosen = chosen,
            keyboard = settings.value.enabledLanguages.map { it.id },
            onDismiss = { open = false },
        ) { ids ->
            scope.launch { repository.setVoiceLanguages(ids) }
            open = false
        }
    }
}

/**
 * Follow the keyboard, or tick languages. The keyboard's own languages come
 * first; after them, every other language speech recognition commonly
 * handles, which is Whisper's list as the nearest thing to a shared one. The
 * system recognizer may know more or fewer, and says so when a session
 * starts.
 */
@Composable
private fun VoiceLanguagesDialog(
    chosen: List<String>,
    keyboard: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val others = remember(keyboard) {
        WhisperLanguages.codes
            .mapNotNull(WhisperLanguages::languageIdFor)
            .filter { it !in keyboard }
            .distinct()
            .sortedBy { LanguageRegistry.byId(it).englishName }
    }
    var picked by remember { mutableStateOf(chosen) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_languages_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CaptionText(stringResource(R.string.voice_languages_dialog_body))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { picked = emptyList() },
                ) {
                    RadioButton(selected = picked.isEmpty(), onClick = { picked = emptyList() })
                    Text(stringResource(R.string.voice_languages_follow))
                }
                LanguageGroupLabel(stringResource(R.string.voice_languages_keyboard_group))
                for (id in keyboard) {
                    LanguageCheckRow(id, id in picked) { on -> picked = if (on) picked + id else picked - id }
                }
                LanguageGroupLabel(stringResource(R.string.voice_languages_other_group))
                for (id in others) {
                    LanguageCheckRow(id, id in picked) { on -> picked = if (on) picked + id else picked - id }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(picked) }) { Text(stringResource(CommonR.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun LanguageGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun LanguageCheckRow(id: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val language = LanguageRegistry.byId(id)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(
            if (language.displayName == language.englishName) {
                language.englishName
            } else {
                "${language.englishName} · ${language.displayName}"
            },
        )
    }
}
