package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Composable
import com.wasimaster.wmkeyboard.core.ui.ScrollRailColors
import com.wasimaster.wmkeyboard.core.ui.scrollRailColors

/**
 * The scroll rail in the keyboard's own colours.
 *
 * The rail's defaults come from the app's Material scheme, which the keyboard
 * does not use: a popup over a black theme would get a rail tinted by whatever
 * the settings app is painted with. Every menu in the keyboard that caps a list
 * passes this instead.
 */
@Composable
internal fun kbRailColors(kb: KbTheme): ScrollRailColors = scrollRailColors(
    track = kb.secondaryText.copy(alpha = 0.25f),
    live = kb.accent.copy(alpha = 0.35f),
    thumb = kb.accent,
    pill = kb.popup,
    onPill = kb.popupText,
)
