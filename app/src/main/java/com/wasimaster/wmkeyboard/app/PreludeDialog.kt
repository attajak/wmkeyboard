package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.plugins.PluginPrelude
import com.wasimaster.wmkeyboard.core.ui.ScrollRailBox
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState

/** How many lines above the one that failed stay in view when the prelude opens. */
private const val LINES_ABOVE = 3

/**
 * The prelude every plugin runs first, read-only, with [line] marked. A failure
 * the runtime places inside it is not in the author's script, so the editor
 * never moves the caret for one; it shows the code the line belongs to instead.
 * The text is [PluginPrelude.SOURCE] itself, so it is always what actually ran.
 */
@Composable
internal fun PreludeDialog(line: Int, onDismiss: () -> Unit) {
    val lines = remember { PluginPrelude.SOURCE.split('\n') }
    val colors = rememberCodeColors()
    val list = rememberLazyListState()
    val rail = rememberScrollRailState(list)
    LaunchedEffect(line) { list.scrollToItem((line - 1 - LINES_ABOVE).coerceIn(0, maxOf(0, lines.lastIndex))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugin_ide_prelude_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.plugin_ide_prelude_caption),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ScrollRailBox(
                    state = rail,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .fillMaxWidth()
                        .background(colors.background),
                    // The code's own background, not the dialog's: the fade has
                    // to disappear into what it sits on.
                    fadeColor = colors.background,
                ) { code ->
                    LazyColumn(state = list, modifier = code.padding(vertical = 6.dp)) {
                        itemsIndexed(lines) { index, text ->
                            val number = index + 1
                            Row(
                                Modifier.fillMaxWidth().background(
                                    if (number == line) colors.activeLine else Color.Transparent,
                                ),
                            ) {
                                Text(
                                    number.toString(),
                                    fontFamily = CodeFontFamily,
                                    fontSize = 11.sp,
                                    color = if (number == line) colors.problem else colors.gutterText,
                                    modifier = Modifier.width(32.dp).padding(end = 6.dp),
                                )
                                Text(
                                    text,
                                    fontFamily = CodeFontFamily,
                                    fontSize = 12.sp,
                                    color = colors.text,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) } },
    )
}
