package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R

/** What the find bar holds. Kept by the screen, so a query survives closing the bar and opening it again. */
@Stable
internal class CodeFindState {
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var options by mutableStateOf(CodeFindOptions())
    var replacing by mutableStateOf(false)

    /** The match that is showing, as an index into the matches. */
    var active by mutableIntStateOf(0)
}

private val PlainTextKeys = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    imeAction = ImeAction.Search,
)

/**
 * Find, and replace when asked, over a code field. The matches themselves are
 * drawn by the field under the text; this bar holds the query, the count, the
 * three search options and the replace row.
 */
@Composable
internal fun CodeFindBar(
    find: CodeFindState,
    matches: List<TextRange>,
    onStep: (Int) -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Esc from inside the bar. The screen closes it and gives the keys back to the code. */
    onEscape: () -> Unit = onClose,
    /** Screen commands pressed while the bar has the keys, such as Ctrl+G or Ctrl+S. */
    onCommand: (CodeCommand) -> Boolean = { false },
    /** Raised by one to put the caret back in the query, as Ctrl+F does while the bar is open. */
    focusRequests: Int = 0,
) {
    val invalid = find.options.regex && find.query.isNotEmpty() && findPattern(find.query, find.options) == null
    // The bar opens to be typed into, so the query takes the caret and the keyboard.
    val queryFocus = remember { FocusRequester() }
    val replaceFocus = remember { FocusRequester() }
    var inReplace by remember { mutableStateOf(false) }
    var replaceRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusRequests) { queryFocus.requestFocus() }
    LaunchedEffect(replaceRequests) {
        if (replaceRequests == 0) return@LaunchedEffect
        // A frame, so a replace field shown by this same press is there to take the caret.
        withFrameNanos { }
        runCatching { replaceFocus.requestFocus() }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // The find widget's own keys, as VS Code has them. Anything else the key table
            // gives to the screen is passed on, so Ctrl+G still works from in here.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent event.key == Key.Escape
                val ctrl = event.isCtrlPressed || event.isMetaPressed
                val shift = event.isShiftPressed
                val alt = event.isAltPressed
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                val option = alt && !ctrl && !shift
                when {
                    event.key == Key.Escape -> onEscape()
                    enter && ctrl && alt -> onReplaceAll()
                    enter && !ctrl && !alt -> if (inReplace && !shift) onReplace() else onStep(if (shift) -1 else 1)
                    option && event.key == Key.C -> find.options = find.options.copy(caseSensitive = !find.options.caseSensitive)
                    option && event.key == Key.W -> find.options = find.options.copy(wholeWord = !find.options.wholeWord)
                    option && event.key == Key.R -> find.options = find.options.copy(regex = !find.options.regex)
                    else -> when (val command = codeCommandFor(event.key, ctrl, shift, alt)) {
                        CodeCommand.FIND -> queryFocus.requestFocus()
                        CodeCommand.REPLACE -> {
                            find.replacing = true
                            replaceRequests++
                        }
                        CodeCommand.FIND_NEXT -> onStep(1)
                        CodeCommand.FIND_PREVIOUS -> onStep(-1)
                        null -> return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent command.scope == CodeCommand.Scope.SCREEN && onCommand(command)
                    }
                }
                true
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = find.query,
                onValueChange = {
                    find.query = it
                    find.active = 0
                },
                singleLine = true,
                isError = invalid,
                placeholder = { Text(stringResource(R.string.code_find_label)) },
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = PlainTextKeys,
                keyboardActions = KeyboardActions(onSearch = { onStep(1) }),
                modifier = Modifier.weight(1f).focusRequester(queryFocus),
            )
            val count = when {
                find.query.isEmpty() || invalid -> ""
                matches.isEmpty() -> stringResource(R.string.code_find_none)
                else -> stringResource(R.string.code_find_count, find.active.coerceIn(0, matches.lastIndex) + 1, matches.size)
            }
            Text(
                count,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            IconButton(onClick = { onStep(-1) }, enabled = matches.isNotEmpty()) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.code_find_previous_desc))
            }
            IconButton(onClick = { onStep(1) }, enabled = matches.isNotEmpty()) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.code_find_next_desc))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.code_find_close_desc))
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OptionChip("Aa", R.string.code_find_case_desc, find.options.caseSensitive) {
                find.options = find.options.copy(caseSensitive = it)
            }
            OptionChip("ab", R.string.code_find_word_desc, find.options.wholeWord) {
                find.options = find.options.copy(wholeWord = it)
            }
            OptionChip(".*", R.string.code_find_regex_desc, find.options.regex) {
                find.options = find.options.copy(regex = it)
            }
            FilterChip(
                selected = find.replacing,
                onClick = { find.replacing = !find.replacing },
                label = { Text(stringResource(R.string.code_replace_toggle)) },
            )
            if (invalid) {
                Text(
                    stringResource(R.string.code_find_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (find.replacing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = find.replacement,
                    onValueChange = { find.replacement = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.code_replace_label)) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = PlainTextKeys.copy(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f).focusRequester(replaceFocus).onFocusChanged { inReplace = it.isFocused },
                )
                TextButton(onClick = onReplace, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.code_replace_one)) }
                TextButton(onClick = onReplaceAll, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.code_replace_all)) }
            }
        }
    }
}

/** A search option drawn as code characters, read aloud by what it does. */
@Composable
private fun OptionChip(mark: String, @StringRes description: Int, selected: Boolean, onChange: (Boolean) -> Unit) {
    val words = stringResource(description)
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(mark, fontFamily = CodeFontFamily, modifier = Modifier.clearAndSetSemantics {}) },
        modifier = Modifier.semantics { contentDescription = words },
    )
}

/** The match to show first: the first at or after [caret], or the first of all. */
internal fun firstMatchFrom(matches: List<TextRange>, caret: Int): Int =
    matches.indexOfFirst { it.min >= caret }.let { if (it < 0) 0 else it }

/** Every one of [spans] renamed to [name] as one edit, the caret kept on the same name. */
internal fun renameEdit(text: String, spans: List<TextRange>, name: String, caret: Int): CodeTextEdit? {
    if (spans.isEmpty()) return null
    val sorted = spans.sortedBy { it.min }
    val changes = sorted.map { it to name }
    var shift = 0
    for (span in sorted) {
        when {
            span.max <= caret -> shift += name.length - span.length
            span.min < caret -> return mergeEdits(text, changes, TextRange(span.min + shift + (caret - span.min).coerceAtMost(name.length)))
            else -> break
        }
    }
    return mergeEdits(text, changes, TextRange(caret + shift))
}

/** The line a typed number names in a document of [lines] lines, read in any script's digits, or null. */
internal fun parseLineNumber(input: String, lines: Int): Int? {
    val digits = input.trim()
    if (digits.isEmpty() || digits.length > MAX_LINE_DIGITS || !digits.all(Character::isDigit)) return null
    val value = digits.fold(0) { total, digit -> total * 10 + Character.digit(digit, 10) }
    return value.takeIf { it in 1..lines }
}

private const val MAX_LINE_DIGITS = 9
