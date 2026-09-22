package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.ime.R

/** The global Symbol row page, which a held entry's menu opens when no mode owns the row. */
internal const val SYMBOL_ROW_SETTINGS_ROUTE = "rows/symbol"

/**
 * What the menu on a held symbol row entry can ask of the service (#323):
 * the same idea as the held-word menu on the suggestion strip, for the row's
 * characters and snippets.
 */
sealed interface SymbolRowAction {
    /**
     * Take [entry] out of the set [setId]. [index] is where the row drew it,
     * so a set holding the same text twice loses the one that was held.
     */
    data class RemoveEntry(val setId: String, val index: Int, val entry: String) : SymbolRowAction

    /** Switch the symbol row off for the mode [modeId], whatever sets it offers. */
    data class HideInMode(val modeId: String) : SymbolRowAction

    /** Delete the user's own set [setId]. The menu has already asked. */
    data class DeleteSet(val setId: String) : SymbolRowAction

    /** Open the settings that decide what the row is showing right now. */
    data object OpenSettings : SymbolRowAction
}

/**
 * The symbol row's two ways back to the service: the picker chip's set pick,
 * and the held entry's menu.
 *
 * One bundle for the same reason as [SuggestionHoldCallbacks]: the caller of
 * [KeyboardScreen] sits against the JVM's 64K method ceiling, so the menu
 * arrived as a change of this parameter's type rather than as a new one.
 */
@Immutable
class SymbolRowCallbacks(
    /** The picker chip switched the visible set. */
    val onSetSelect: (setId: String) -> Unit = {},
    /** An item of a held entry's menu was pressed. */
    val onAction: (SymbolRowAction) -> Unit = {},
)

/**
 * Asks before a held entry's menu deletes a whole symbol set. An in-window
 * [Popup] over a scrim, like the word card, because the keyboard's window
 * cannot host a Compose dialog.
 */
@Composable
internal fun SymbolSetDeleteConfirm(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    Popup(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = kb.menuShape(),
                color = kb.popup,
                border = kb.popupSurfaceBorder(),
                shadowElevation = elevationFor(kb.menuShapeKind, 8.dp),
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(0.86f)
                    // Taps inside the card must not reach the scrim.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 6.dp)) {
                    Text(
                        text = stringResource(R.string.ime_symbol_row_delete_confirm_title, name),
                        style = MaterialTheme.typography.titleMedium,
                        color = kb.popupText,
                    )
                    Text(
                        text = stringResource(R.string.ime_symbol_row_delete_confirm_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = kb.popupText.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(CommonR.string.common_cancel), color = kb.popupText)
                        }
                        TextButton(onClick = onConfirm) {
                            Text(
                                stringResource(CommonR.string.common_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
