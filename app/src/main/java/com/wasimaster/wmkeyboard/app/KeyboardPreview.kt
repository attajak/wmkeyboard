package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardAlt
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The keyboard test on every settings screen (#412): a small button that opens
 * a text field docked above the real keyboard, so a change can be tried on the
 * screen it was made on instead of by leaving for another app.
 *
 * One instance for the whole graph, published by the nav host, so the field and
 * what was typed into it survive moving between screens: every [WmScreen] frame
 * draws its own copy of the button and the field, and they all show this state.
 * Null when the user has switched the button off (Accessibility › Settings
 * app), and in the list pane of the two-pane layout while the pane beside it
 * has a screen of its own — one button on the window, not two.
 */
@Stable
internal class KeyboardPreviewState {
    /** Whether the field is up. */
    var open by mutableStateOf(false)

    /** What the user has typed into it. */
    var text by mutableStateOf("")

    /**
     * How many screens are drawing the field right now. Two while a screen
     * pushes over another, none once the user has left for a screen that has
     * no frame of its own (a code editor, search) or that hides the button
     * (the theme editor) — and then the field is closed rather than left to
     * jump back up, keyboard and all, on the way back.
     */
    var hosts by mutableIntStateOf(0)
}

internal val LocalKeyboardPreview = compositionLocalOf<KeyboardPreviewState?> { null }

/**
 * Closes [state]'s field once no screen has drawn it for a moment. Run once by
 * the nav host. The wait covers a screen change, where the frame being left can
 * let go of the field a frame before the arriving one picks it up — with
 * transitions off, in the very same frame.
 */
@Composable
internal fun CloseOrphanedKeyboardPreview(state: KeyboardPreviewState) {
    LaunchedEffect(state) {
        snapshotFlow { state.open && state.hosts == 0 }.collectLatest { orphaned ->
            if (!orphaned) return@collectLatest
            delay(OrphanGraceMs)
            state.open = false
        }
    }
}

private const val OrphanGraceMs = 400L

/**
 * The button: a keyboard while the field is closed, the keyboard put away while
 * it is open. Small and in the secondary colour, so on a screen that has an
 * "add" button of its own the two read as one primary action and one aside.
 */
@Composable
internal fun KeyboardPreviewFab(state: KeyboardPreviewState) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    SmallFloatingActionButton(
        onClick = {
            if (state.open) {
                // Put away first: taking the field out of the tree drops its
                // input session too, but not always the keyboard with it.
                keyboard?.hide()
                focus.clearFocus()
            }
            state.open = !state.open
        },
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        // Every screen draws its own, so without a shared key the button of
        // the screen being left blinks out as the next one's appears.
        modifier = Modifier.wmSharedElement(PreviewFabKey),
    ) {
        if (state.open) {
            Icon(Icons.Outlined.KeyboardHide, stringResource(R.string.keyboard_preview_close))
        } else {
            Icon(Icons.Outlined.Keyboard, stringResource(R.string.keyboard_preview_open))
        }
    }
}

private const val PreviewFabKey = "wm.fab.keyboard_preview"

/**
 * The field, in the frame's bottom bar: the frame then pads the body by its
 * height and floats the buttons above it, so nothing on the screen ends up
 * behind it or behind the keyboard under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeyboardPreviewBar(state: KeyboardPreviewState) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    DisposableEffect(state) {
        state.hosts++
        onDispose { state.hosts-- }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // Closes with the keyboard: put away with back, or with the keyboard's own
    // hide key, the field has nothing left to show. Only once the keyboard has
    // been seen up, or the field would close on the frame it opens. Where the
    // window does not hand its insets down (Android 14 and older, which pan the
    // window instead) this never reads true, and the button closes the field.
    val imeVisible = WindowInsets.isImeVisible
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) seen = true else if (seen) state.open = false
    }
    // Typing into somebody else's keyboard tests nothing. Read again whenever
    // the keyboard comes or goes: the picker is a system dialog, so nothing
    // else here hears that the user switched.
    val ours = remember(imeVisible) { imeSelected(context) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.text,
                onValueChange = { state.text = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                placeholder = { Text(stringResource(R.string.keyboard_preview_hint)) },
                maxLines = 4,
                trailingIcon = if (state.text.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { state.text = "" }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.keyboard_preview_clear))
                        }
                    }
                },
            )
            if (!ours) {
                IconButton(
                    onClick = {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    },
                ) {
                    Icon(Icons.Outlined.KeyboardAlt, stringResource(R.string.keyboard_preview_switch))
                }
            }
        }
    }
}
