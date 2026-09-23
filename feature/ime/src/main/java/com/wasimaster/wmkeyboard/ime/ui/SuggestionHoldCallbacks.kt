package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Immutable
import com.wasimaster.wmkeyboard.ime.SynonymAction
import com.wasimaster.wmkeyboard.ime.WordCardAction
import com.wasimaster.wmkeyboard.ime.WordMenuAction
import com.wasimaster.wmkeyboard.ime.WordMenuFacts

/**
 * Everything pressing and holding a word on the suggestion strip can ask of
 * the service (#99): the facts the menu needs to pick its items, the menu's
 * actions, and the word card's.
 *
 * One bundle rather than three callbacks because `ServiceKeyboardContent`
 * sits against the JVM's 64K method ceiling and cannot afford another
 * [KeyboardScreen] parameter — see [DeleteSwipeCallbacks] for the same
 * trick. It replaces the single `onSuggestionHold` lambda that was the
 * gesture's whole API when the menu had one item.
 */
@Immutable
class SuggestionHoldCallbacks(
    /**
     * Answers, on the main thread and at once, what the menu for a held word
     * may offer. Map and set lookups only; the walk that describes the word
     * in full happens later, for the card.
     */
    val facts: (word: String) -> WordMenuFacts = { WordMenuFacts() },
    /** A menu item was pressed. */
    val onMenu: (WordMenuAction) -> Unit = {},
    /** Something on the open word card was pressed. */
    val onCard: (WordCardAction) -> Unit = {},
    /** Something on the open synonyms sheet was pressed (#321). */
    val onSynonyms: (SynonymAction) -> Unit = {},
)
