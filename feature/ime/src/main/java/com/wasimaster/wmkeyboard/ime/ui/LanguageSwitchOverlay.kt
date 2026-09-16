package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState

/**
 * The list a physical keyboard's language switch shows: every enabled layout,
 * with the one the release would land on highlighted. Also up, briefly, after
 * the dedicated language key has already switched — same list, the highlight
 * just reports what happened rather than what is about to.
 *
 * Modeled on [ToolPickerOverlay]: floated over the keys, never a panel of its
 * own. Rows are tappable and there is a close button, because every key that
 * drives this list needs the keyboard that opened it — unplug it, or put it
 * down, and the overlay must still have a way out under a finger.
 */
@Composable
internal fun LanguageSwitchOverlay(
    state: KeyboardUiState,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.languageSwitch
    AnimatedVisibility(visible = session != null, modifier = modifier) {
        // AnimatedVisibility keeps composing this while it fades out, after the
        // state is already null — remember the last real session for the exit.
        val shown = session ?: return@AnimatedVisibility
        val kb = LocalKbTheme.current
        val scroll = rememberScrollState()
        val rail = rememberScrollRailState(scroll)
        Column(
            modifier = Modifier
                .padding(6.dp)
                .widthIn(min = 180.dp, max = 260.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                .border(1.dp, kb.accent, RoundedCornerShape(14.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, bottom = 2.dp),
            ) {
                Text(
                    stringResource(R.string.ime_language_switch_title),
                    color = kb.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Outlined.Close,
                    contentDescription =
                        stringResource(R.string.ime_language_switch_close_desc),
                    tint = kb.secondaryText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onDismiss)
                        .padding(4.dp)
                        .size(16.dp),
                )
            }
            ScrollRail(
                state = rail,
                modifier = Modifier.heightIn(max = 180.dp),
                fadeColor = kb.popup,
                colors = kbRailColors(kb),
            ) {
                shown.layoutIds.forEachIndexed { index, layoutId ->
                    val highlighted = index == shown.candidate
                    Text(
                        layoutSwitchLabel(
                            layoutId = layoutId,
                            enabledLayoutIds = state.settings.enabledLayoutIds,
                            customLayouts = state.settings.customLayouts,
                            mode = state.settings.layoutBehavior.spacebarDisplay,
                        ),
                        color = if (highlighted) kb.accent else kb.popupText,
                        fontSize = 13.sp,
                        fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (highlighted) kb.pressedKey else Color.Transparent)
                            .clickable { onPick(layoutId) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            // Keep the highlight on screen with many layouts enabled: rows are
            // a fixed height, so its offset is a plain multiple of the index.
            LaunchedEffect(shown.candidate) {
                val rowPx = if (shown.layoutIds.isEmpty()) {
                    0
                } else {
                    scroll.maxValue / shown.layoutIds.size + 1
                }
                scroll.animateScrollTo((shown.candidate * rowPx - rowPx).coerceAtLeast(0))
            }
        }
    }
}
