package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.selection.SelectionKind
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * The selection macro bar: what the keyboard offers to do with what is
 * selected.
 *
 * Drawn either on a row of its own ([com.wasimaster.wmkeyboard.core.settings.BarRow.MACROS])
 * or over the suggestion strip, which is why it is a plain `RowScope`-free
 * composable taking the state and one callback. The two placements share every
 * pixel of it, including the case ladder below, so a user who moves the bar
 * from one to the other finds the same bar.
 */
@Composable
internal fun SelectionMacroBar(
    state: KeyboardUiState,
    onMacro: (SelectionMacro) -> Unit,
    onFancyStyle: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offer = state.selectionMacros ?: return
    // Format on plain prose opens the case ladder instead of rewriting
    // anything, so which face the bar is showing is composition state.
    //
    // Keyed on the *kind* and not on the offer itself. Every case chip rewrites
    // the selection, which republishes a new offer a frame later, so keying on
    // the offer would shut the ladder after each tap and make lower-then-Title
    // two trips instead of two taps. The kind survives a rewrite, and a
    // selection cleared altogether takes the whole bar out of the composition,
    // which is what resets this for the next one.
    var caseOpen by remember(offer.kind) { mutableStateOf(false) }
    // Fancy opens a ladder of its own, and this is the selection as it stood
    // before that ladder began rewriting it — null while the ladder is shut.
    //
    // Held rather than re-read from the offer for the reason the case ladder
    // does not need to: every chip restyles the *plain* text, so a second pick
    // replaces the first. Restyling already-styled text would do nothing at
    // all — the style tables are keyed by ASCII and 𝐛 is not a key in any of
    // them — which would read as a ladder that works once and then breaks.
    var fancySource by remember(offer.kind) { mutableStateOf<String?>(null) }
    val feedback = LocalKeyPressFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight(state.settings)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val styling = fancySource
        if (caseOpen || styling != null) {
            MacroBackButton(
                onClick = {
                    caseOpen = false
                    fancySource = null
                },
            )
        }
        if (styling != null) {
            FancyStyleLadder(
                modifier = Modifier.weight(1f),
                onPick = { id ->
                    feedback()
                    onFancyStyle(id, styling)
                },
            )
            return@Row
        }
        val macros = if (caseOpen) SelectionMacros.fixedCaseMacros else offer.macros
        LazyRow(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            lazyRowItems(macros, key = { it.name }) { macro ->
                MacroChip(
                    macro = macro,
                    onClick = {
                        feedback()
                        // The two taps the service never sees: on prose there
                        // is nothing to reformat and no one style to apply, so
                        // both of these chips are doors to a ladder rather than
                        // actions of their own.
                        if (macro == SelectionMacro.FORMAT && offer.kind == SelectionKind.TEXT) {
                            caseOpen = true
                        } else if (macro == SelectionMacro.FANCY) {
                            fancySource = offer.text
                        } else {
                            onMacro(macro)
                        }
                    },
                )
            }
        }
    }
}

/**
 * The ladder behind the Fancy chip: every style, written in itself.
 *
 * The same WYSIWYG chips the fancy layout's own strip uses, and for the same
 * reason — a style is a look, and its name is a poor description of one.
 *
 * Nothing is drawn as selected. The ladder asks what to make *this* selection,
 * which has nothing to do with the style the fancy keyboard is set to, and
 * marking one would suggest the two are the same setting.
 */
@Composable
private fun FancyStyleLadder(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    // Latin glyphs whatever the locale, so mirroring the ladder under RTL
    // would put the list order at odds with the layout — as on the fancy strip.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyRow(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            lazyRowItems(FancyStyles.all, key = { it.id }) { style ->
                Text(
                    text = style.sample,
                    modifier = Modifier
                        .clip(shape)
                        .background(kb.chipActive)
                        .chipBorder(kb, shape)
                        .clickable { onPick(style.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        // The samples are astral soup to TalkBack; speak the
                        // plain name instead.
                        .semantics { contentDescription = style.name },
                    fontSize = 14.sp,
                    color = kb.chipActiveText,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Back out of a ladder to the macros the selection was offered. */
@Composable
private fun MacroBackButton(onClick: () -> Unit) {
    val feedback = LocalKeyPressFeedback.current
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
        contentDescription = stringResource(R.string.ime_selection_macro_back_desc),
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                feedback()
                onClick()
            }
            .padding(6.dp)
            .size(20.dp),
        tint = LocalKbTheme.current.suggestionText,
    )
}

/**
 * One macro, drawn as its glyph and its word.
 *
 * The word is always there: the glyphs for Share, Open and Format are close
 * enough to each other that an icon-only bar would be a guessing game, and this
 * bar appears rarely enough that nobody builds muscle memory for it.
 */
@Composable
private fun MacroChip(macro: SelectionMacro, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val label = stringResource(macro.labelRes)
    val icon = macroIcon(macro)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(kb.chipActive)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = kb.chipActiveText,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            fontSize = 14.sp,
            color = kb.chipActiveText,
            maxLines = 1,
        )
    }
}

/**
 * The glyph for a macro, or null for the case options.
 *
 * The four case chips are written in the case they apply ("UPPER CASE",
 * "Sentence case"), which already shows what each one does; an icon beside
 * them would say it a second time in a worse language.
 */
private fun macroIcon(macro: SelectionMacro): ImageVector? = when (macro) {
    SelectionMacro.SELECT_ALL -> Icons.Outlined.SelectAll
    SelectionMacro.COPY -> Icons.Outlined.ContentCopy
    SelectionMacro.SHARE -> Icons.Outlined.Share
    SelectionMacro.FORMAT -> Icons.Outlined.TextFormat
    SelectionMacro.SEARCH -> Icons.Outlined.Search
    SelectionMacro.TRANSLATE -> Icons.Outlined.Translate
    SelectionMacro.CALL -> Icons.Outlined.Call
    SelectionMacro.SMS -> Icons.Outlined.Sms
    SelectionMacro.WHATSAPP -> Icons.Outlined.Chat
    SelectionMacro.EMAIL -> Icons.Outlined.Mail
    SelectionMacro.OPEN -> Icons.Outlined.OpenInNew
    SelectionMacro.QR -> Icons.Outlined.QrCode2
    // Not TextFormat, which Format already wears: the two sit side by side on
    // a plain-text selection, both open a ladder, and must not read as the
    // same chip twice.
    SelectionMacro.FANCY -> Icons.Outlined.AutoAwesome
    else -> null
}

/**
 * Whether the macro bar has anything to draw right now.
 *
 * Read by both hosts and by the strip, which has to know before it decides
 * which surface owns the row. The offer is only ever published while the
 * feature is on and something is selected, so this is the whole question.
 */
internal fun selectionMacroBarVisible(state: KeyboardUiState): Boolean =
    state.selectionMacros != null
