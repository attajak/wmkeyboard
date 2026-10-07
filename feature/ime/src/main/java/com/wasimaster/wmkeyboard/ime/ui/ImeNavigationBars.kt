package com.wasimaster.wmkeyboard.ime.ui

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.view.WindowInsetsCompat

/**
 * The navigation bar's insets as the keyboard window last dispatched them to
 * the keyboard's frame, kept apart from Compose's own
 * `WindowInsets.navigationBars` (issues #463, #468).
 *
 * Compose's insets listener stopped taking new insets after the keyboard was
 * switched away and back, and the bottom row then sat on the gesture handle
 * and the system's keyboard buttons. Holding back the insets animations that
 * leave it waiting (see [StableMeasureFrame]) was not enough on every phone.
 * So the keyboard's frame records the insets the view hierarchy is handed,
 * which no listener state can get stuck in front of, and asks for a fresh
 * dispatch when it is attached or the window comes back on screen.
 *
 * Only the dispatched insets, the same ones a View with fitsSystemWindows
 * pads by (HeliBoard's keyboard view): the window's raw `rootWindowInsets`
 * count a bar that something above the keyboard has already padded for, and
 * reading those gave a row-high gap under the space bar (#569).
 *
 * Plain pixels, like the platform's own insets.
 */
@Stable
class ImeNavigationBars {
    var left by mutableIntStateOf(0)
        private set
    var top by mutableIntStateOf(0)
        private set
    var right by mutableIntStateOf(0)
        private set
    var bottom by mutableIntStateOf(0)
        private set

    /** Takes the navigation bar out of [insets]. */
    fun update(insets: android.view.WindowInsets, view: View) {
        val bars = WindowInsetsCompat.toWindowInsetsCompat(insets, view)
            .getInsets(WindowInsetsCompat.Type.navigationBars())
        left = bars.left
        top = bars.top
        right = bars.right
        bottom = bars.bottom
    }
}

/** The keyboard window's [ImeNavigationBars]; null outside it (the settings app's previews). */
val LocalImeNavigationBars = staticCompositionLocalOf<ImeNavigationBars?> { null }

/**
 * The navigation bar insets to keep the keys clear of: the keyboard window's
 * own record where there is one, Compose's everywhere else.
 */
@Composable
fun navigationBarInsets(): WindowInsets {
    val bars = LocalImeNavigationBars.current ?: return WindowInsets.navigationBars
    val left = bars.left
    val top = bars.top
    val right = bars.right
    val bottom = bars.bottom
    return remember(left, top, right, bottom) { WindowInsets(left, top, right, bottom) }
}
