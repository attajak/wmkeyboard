package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.FindOption
import com.wasimaster.wmkeyboard.ime.FindReplaceField
import com.wasimaster.wmkeyboard.ime.FindReplaceUi
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/** What the Find and replace panel hands back; one bundle, see [ToolHoldCallbacks]. */
data class FindReplaceCallbacks(
    val onFocusField: (FindReplaceField) -> Unit = {},
    val onToggle: (FindOption) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onReplace: () -> Unit = {},
    val onReplaceAll: () -> Unit = {},
    val onUndo: () -> Unit = {},
)

/** The panel's height: the same as a media panel with its search box open, so the window does not jump between them. */
internal val FindReplacePanelHeight = 132.dp

/**
 * Find and replace over the focused field.
 *
 * Two fake fields (the keys route into whichever is focused, so the platform
 * draws no caret of its own), the toggles, and the buttons. The current match
 * is kept selected in the field itself, which is where the user sees it; the
 * count line says which one of how many. Nothing here reads the field: the
 * service extracts it, matches on a background thread and publishes the
 * ranges.
 */
@Composable
internal fun FindReplacePanel(state: KeyboardUiState, callbacks: FindReplaceCallbacks) {
    val fr = state.findReplace ?: return
    val kb = LocalKbTheme.current
    val chipShape = RoundedCornerShape(12.dp)
    val focusedField = state.focusedIndex(FocusRegion.SEARCH)
    val focusedToggle = state.focusedIndex(FocusRegion.CHIPS)
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val canStep = fr.matches.isNotEmpty() && fr.addressable
    val canReplace = fr.current >= 0 && fr.addressable
    val canReplaceAll = fr.matches.isNotEmpty() && !fr.truncated

    PanelFocusTarget(panel = PanelMode.FIND_REPLACE, region = FocusRegion.SEARCH, count = 2, columns = 2) { index ->
        callbacks.onFocusField(if (index == 0) FindReplaceField.FIND else FindReplaceField.REPLACE)
    }
    PanelFocusTarget(panel = PanelMode.FIND_REPLACE, region = FocusRegion.CHIPS, count = 3, columns = 3) { index ->
        callbacks.onToggle(FindOption.entries[index])
    }
    PanelFocusTarget(panel = PanelMode.FIND_REPLACE, region = FocusRegion.ACTIONS, count = 5, columns = 5) { index ->
        when (index) {
            0 -> if (canStep) callbacks.onPrevious()
            1 -> if (canStep) callbacks.onNext()
            2 -> if (canReplace) callbacks.onReplace()
            3 -> if (canReplaceAll) callbacks.onReplaceAll()
            else -> if (fr.undoDepth > 0) callbacks.onUndo()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(FindReplacePanelHeight)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QueryPill(
                text = fr.query,
                placeholder = stringResource(R.string.ime_find_hint),
                active = fr.focused == FindReplaceField.FIND,
                ringed = focusedField == 0,
                modifier = Modifier.weight(1f),
            ) { callbacks.onFocusField(FindReplaceField.FIND) }
            Spacer(Modifier.width(6.dp))
            ToolPanelChip(
                "‹",
                enabled = canStep,
                modifier = Modifier
                    .focusRing(focusedAction == 0, chipShape)
                    .semantics { contentDescription = "" },
            ) { callbacks.onPrevious() }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                "›",
                enabled = canStep,
                modifier = Modifier.focusRing(focusedAction == 1, chipShape),
            ) { callbacks.onNext() }
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    fr.searching -> stringResource(R.string.ime_find_searching)
                    fr.query.isEmpty() -> ""
                    fr.matches.isEmpty() -> stringResource(R.string.ime_find_no_matches)
                    fr.current >= 0 -> stringResource(R.string.ime_find_count, fr.current + 1, fr.matches.size)
                    else -> stringResource(R.string.ime_find_count_none_selected, fr.matches.size)
                },
                color = kb.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QueryPill(
                text = fr.replacement,
                placeholder = stringResource(R.string.ime_replace_hint),
                active = fr.focused == FindReplaceField.REPLACE,
                ringed = focusedField == 1,
                modifier = Modifier.weight(1f),
            ) { callbacks.onFocusField(FindReplaceField.REPLACE) }
            Spacer(Modifier.width(6.dp))
            ToolPanelChip(
                stringResource(R.string.ime_find_replace_action),
                enabled = canReplace,
                modifier = Modifier.focusRing(focusedAction == 2, chipShape),
            ) { callbacks.onReplace() }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                stringResource(R.string.ime_find_replace_all_action),
                enabled = canReplaceAll,
                modifier = Modifier.focusRing(focusedAction == 3, chipShape),
            ) { callbacks.onReplaceAll() }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                stringResource(com.wasimaster.wmkeyboard.common.R.string.common_undo),
                enabled = fr.undoDepth > 0,
                modifier = Modifier.focusRing(focusedAction == 4, chipShape),
            ) { callbacks.onUndo() }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Toggle(R.string.ime_find_case_label, R.string.ime_find_case_desc, fr.caseSensitive, focusedToggle == 0) {
                callbacks.onToggle(FindOption.CASE)
            }
            Spacer(Modifier.width(4.dp))
            Toggle(R.string.ime_find_whole_word_label, R.string.ime_find_whole_word_desc, fr.wholeWord, focusedToggle == 1) {
                callbacks.onToggle(FindOption.WHOLE_WORD)
            }
            Spacer(Modifier.width(4.dp))
            Toggle(R.string.ime_find_regex_label, R.string.ime_find_regex_desc, fr.regex, focusedToggle == 2) {
                callbacks.onToggle(FindOption.REGEX)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = fr.error ?: if (fr.truncated) stringResource(R.string.ime_find_too_many, fr.matches.size) else "",
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun QueryPill(
    text: String,
    placeholder: String,
    active: Boolean,
    ringed: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(kb.chip)
            .focusRing(ringed, shape)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = text.replace("\n", " "),
            placeholder = placeholder,
            active = active,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Toggle(labelRes: Int, descRes: Int, on: Boolean, ringed: Boolean, onClick: () -> Unit) {
    val description = stringResource(descRes)
    ToolPanelChip(
        stringResource(labelRes),
        selected = on,
        modifier = Modifier
            .focusRing(ringed, RoundedCornerShape(12.dp))
            .semantics { contentDescription = description },
        onClick = onClick,
    )
}
