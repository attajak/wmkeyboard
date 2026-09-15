package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.WordCardAction

/**
 * The word card's spelling editor (#138), drawn in the suggestion strip's own
 * row while the card is respelling a word.
 *
 * The card itself is a window over the whole keyboard, so it cannot be typed
 * into; it steps aside and this bar takes its place, exactly as the AI Custom
 * instruction collapses its panel to leave the key rows free. Every keystroke
 * goes into [KeyboardUiState.wordSpell], never into the app behind the
 * keyboard — see `keysTakenByKeyboard`.
 *
 * Tick applies the respelling and brings the card back on the new word; cross
 * leaves the spelling as it was.
 *
 * The draft has a caret and a selection of its own (#204), drawn here and kept
 * by the service: a tap puts the caret, a drag selects, a hold selects the
 * whole word. The spacebar scrub, shift over a selection and a glide all act
 * on them, which is what makes this edit like a text field without being one
 * the IME would have to raise.
 */
@Composable
internal fun WordSpellBar(
    state: KeyboardUiState,
    onAction: (WordCardAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spell = state.wordSpell ?: return
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight(state.settings).coerceAtLeast(SPELL_BAR_MIN_HEIGHT))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(kb.suggestionText.copy(alpha = 0.10f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (spell.draft.isEmpty()) {
                // An empty draft cannot be applied, so the bar says what the
                // keys are for rather than showing a blank box.
                Text(
                    text = stringResource(R.string.ime_word_spell_hint, spell.word),
                    style = MaterialTheme.typography.bodyMedium,
                    color = kb.suggestionText.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                SpellDraft(
                    draft = spell.draft,
                    cursor = spell.cursor,
                    selectionStart = spell.selectionStart,
                    selectionEnd = spell.selectionEnd,
                    textColor = kb.suggestionText,
                    accent = kb.accent,
                    onSelect = { start, end -> onAction(WordCardAction.SelectSpelling(start, end)) },
                )
            }
        }
        SpellBarButton(
            icon = Icons.Outlined.Close,
            description = stringResource(R.string.ime_word_spell_cancel_desc),
            enabled = true,
            tint = kb.suggestionText,
        ) {
            feedback()
            onAction(WordCardAction.CancelSpelling)
        }
        SpellBarButton(
            icon = Icons.Outlined.Check,
            description = stringResource(R.string.ime_word_spell_apply_desc),
            // A respelling to nothing would delete the word by the back door,
            // and one that changes nothing has nothing to apply.
            enabled = spell.draft.isNotBlank() && spell.draft != spell.word,
            tint = kb.accent,
        ) {
            feedback()
            onAction(WordCardAction.CommitSpelling)
        }
    }
}

/**
 * The draft with its selection shaded and, when nothing is selected, its
 * caret drawn. Touches answer in draft offsets through [onSelect], anchor
 * first; the service owns the result, so what is drawn is always what the
 * next key will edit.
 */
@Composable
private fun SpellDraft(
    draft: String,
    cursor: Int,
    selectionStart: Int,
    selectionEnd: Int,
    textColor: Color,
    accent: Color,
    onSelect: (start: Int, end: Int) -> Unit,
) {
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val select by rememberUpdatedState(onSelect)
    val length by rememberUpdatedState(draft.length)
    val shade = accent.copy(alpha = 0.32f)
    val text = remember(draft, selectionStart, selectionEnd, shade) {
        buildAnnotatedString {
            append(draft)
            if (selectionStart != selectionEnd) {
                addStyle(SpanStyle(background = shade), selectionStart, selectionEnd)
            }
        }
    }
    fun offsetAt(position: Offset): Int =
        layout.value?.getOffsetForPosition(position)?.coerceIn(0, length) ?: length
    BasicText(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium, color = textColor),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout.value = it },
        modifier = Modifier
            // The whole box width, so a tap past the last letter lands the
            // caret at the end instead of missing the text.
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offsetAt(it).let { at -> select(at, at) } },
                    // Holding is the quick way to the one thing a word bar
                    // is ever selected for: all of it, ready for shift.
                    onLongPress = { select(0, length) },
                )
            }
            .pointerInput(Unit) {
                var anchor = 0
                detectDragGestures(
                    onDragStart = { anchor = offsetAt(it); select(anchor, anchor) },
                    onDrag = { change, _ ->
                        change.consume()
                        select(anchor, offsetAt(change.position))
                    },
                )
            }
            .drawWithContent {
                drawContent()
                if (selectionStart != selectionEnd) return@drawWithContent
                val laid = layout.value ?: return@drawWithContent
                val at = cursor.coerceIn(0, laid.layoutInput.text.length)
                val rect = laid.getCursorRect(at)
                drawRect(
                    color = accent,
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(CARET_WIDTH.toPx(), rect.height),
                )
            },
    )
}

/** One of the bar's two round buttons. */
@Composable
private fun SpellBarButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The shortest the bar may be. The strip's own height is what it normally
 * takes, but that height is zero with the toolbar turned off — and the bar
 * has to be readable wherever the card can be opened from.
 */
private val SPELL_BAR_MIN_HEIGHT = 40.dp

private val CARET_WIDTH = 2.dp
