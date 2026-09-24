package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.bottomPaddingOr

/**
 * How the keyboard asks the window it lives in to colour the system
 * navigation bar, and to pick icons that stay visible on it.
 *
 * Why this exists at all, when [NavigationBarBackground] already paints the
 * band: it only paints on Android 15 and up. From 15 the IME window is laid
 * out edge to edge, `WindowInsets.navigationBars` inside it is a real inset,
 * and the board — gradient, image and all — runs under the bar. Below 15 the
 * IME window stops above the bar, that inset is zero, nothing of ours is drawn
 * there, and the bar takes the window's own `navigationBarColor`. Left unset
 * that is the platform default, which on some light-mode OEM builds is opaque
 * white under a coloured keyboard (issue #255).
 *
 * The default implementation does nothing, which is what the settings app's
 * theme previews want: they run in an Activity whose own window owns the bar.
 */
fun interface SystemNavBarPainter {
    /**
     * @param color the opaque colour to paint the bar, or null to hand it back
     *   to whatever the window had before the keyboard touched it.
     */
    fun paint(color: Color?)
}

/** The painter the docked keyboard drives; see [SystemNavBarPainter]. */
val LocalSystemNavBarPainter = staticCompositionLocalOf { SystemNavBarPainter { } }

/**
 * Relative luminance at or above which the bar counts as light, so the
 * system's hide-keyboard and language-switch icons have to be drawn dark.
 */
private const val LIGHT_NAV_BAR_LUMINANCE = 0.5f

/**
 * True when [color] needs dark icons drawn on it — the value
 * `WindowInsetsControllerCompat.isAppearanceLightNavigationBars` takes, whose
 * "light" means the bar, not the icons.
 */
fun navigationBarWantsDarkIcons(color: Color): Boolean =
    color.luminance() >= LIGHT_NAV_BAR_LUMINANCE

/**
 * The one flat colour that best stands in for the bottom edge of the board.
 *
 * A gradient's last stop is that edge for the default top-left to
 * bottom-right sweep, so it beats the flat board colour a gradient theme also
 * carries. A theme that names [KbTheme.navigationBar] has said outright what
 * the band should be and wins over both.
 *
 * The result is forced opaque: the system re-enables its own contrast scrim
 * over a translucent navigation-bar colour, which would wash out whatever we
 * asked for. A theme's translucent band is composited over the board first, so
 * it still reads as the tint it was written to be.
 */
fun navigationBandColor(kb: KbTheme): Color {
    val board = kb.boardGradient?.colors?.lastOrNull()?.let { Color(it.toInt()) } ?: kb.board
    val opaqueBoard = board.compositeOver(kb.board.copy(alpha = 1f))
    val band = kb.navigationBar ?: opaqueBoard
    return band.compositeOver(opaqueBoard).copy(alpha = 1f)
}

/**
 * Keeps the system navigation bar in [kb]'s colour for as long as the docked
 * keyboard is on screen, and hands it back on the way out — floating mode and
 * the collapsed voice bar do not call this, and neither should own the bar.
 */
@Composable
fun SystemNavigationBarColor(kb: KbTheme) {
    val painter = LocalSystemNavBarPainter.current
    val color = navigationBandColor(kb)
    // Two effects rather than one keyed DisposableEffect: that would hand the
    // bar back to the default and re-take it on every theme change, and the
    // frame in between flashes the platform colour.
    DisposableEffect(painter) {
        onDispose { painter.paint(null) }
    }
    LaunchedEffect(painter, color) {
        painter.paint(color)
    }
}

/**
 * True when the window's bottom edge carries a gesture handle rather than a
 * row of navigation buttons: the navigation bar has height there, and none of
 * it is tappable. False below Android 15 inside the keyboard, whose window
 * stops above the bar and sees no inset at all, and for a three-button bar
 * turned into a side rail in landscape.
 */
@Composable
fun gestureBarAtBottom(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.navigationBars.getBottom(density) > 0 &&
        WindowInsets.tappableElement.getBottom(density) == 0
}

/** The bottom padding [settings] asks for here, the automatic one while unset (#343). */
@Composable
fun bottomPaddingDp(settings: KeyboardSettings): Int =
    settings.bottomPaddingOr(gestureBarAtBottom())
