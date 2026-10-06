package com.wasimaster.wmkeyboard.app

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * Tells the window not to pan for the keyboard while the calling screen is on
 * screen, for a screen that makes room for the keyboard itself with
 * `imePadding()` (a chat composer, a code editor, the keyboard test field).
 *
 * MainActivity leaves its soft input mode unset, which on a focused field means
 * the system pans the whole window up until the field clears the keyboard.
 * Every settings screen with a text field in a scrolling list relies on that.
 * A screen that also pads itself by the keyboard's inset gets both: the field
 * rises by the keyboard's height twice and ends up under the status bar with an
 * empty screen below it. While such a screen is shown the window is set to
 * adjustResize, which from Android 15 (edge to edge) means neither pan nor
 * resize, only the insets the screen already answers.
 *
 * Counted per window, so two such screens overlapping during a transition hand
 * over cleanly, and the mode the window had before comes back once the last of
 * them is gone.
 */
@Composable
internal fun ResizeForKeyboard() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        SoftInputResize.acquire(window)
        onDispose { SoftInputResize.release(window) }
    }
}

private object SoftInputResize {
    private class Held(val before: Int, var count: Int)

    private val held = WeakHashMap<Window, Held>()

    fun acquire(window: Window) {
        val entry = held[window]
        if (entry != null) {
            entry.count++
            return
        }
        val before = window.attributes.softInputMode
        held[window] = Held(before, 1)
        window.setSoftInputMode(
            (before and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    fun release(window: Window) {
        val entry = held[window] ?: return
        if (--entry.count > 0) return
        held.remove(window)
        window.setSoftInputMode(entry.before)
    }
}
