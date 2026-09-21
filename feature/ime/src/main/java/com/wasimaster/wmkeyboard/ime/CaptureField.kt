package com.wasimaster.wmkeyboard.ime

/**
 * The keyboard's own text fields — the ones whose keystrokes never reach the
 * app behind the keyboard — and the caret that edits them.
 *
 * ## Why this file exists
 *
 * A dozen buffers live on the keyboard: the emoji and media and dictionary and
 * clipboard search queries, the AI Custom instruction, a plugin's text box,
 * Find and replace's two fields, Learn from text's speller, the calculator's
 * expression, the converters' amount. Each one was a plain `String` appended to
 * by a hand-written branch repeated in `processTypedText`, `onDelete`,
 * `canDelete`, `backspaceEditsBuffer`, `onSpace`, `onEnter` and
 * `onForwardDelete` — seven ladders that had to agree and did not. None of them
 * had a caret, so typing could only ever land at the end, and none of them
 * could be glided into or suggested for, because glide inserts a word *at* a
 * caret and a suggestion replaces the word *around* one (issue #161).
 *
 * So: [CaptureTarget] names the focused buffer once, [CaretText] does the text
 * arithmetic once, and the service keeps one caret for whichever buffer is
 * focused. The old per-buffer branches become one `captureEdit { }`.
 *
 * The caret is a caret and not a selection. The word card's [WordSpell] already
 * carries anchor+cursor for #204 and keeps its own richer editor; everything
 * here is the plain case.
 */

/**
 * A keyboard-owned buffer and where the caret stands in it, in UTF-16 units.
 *
 * Immutable, and every operation returns a new one, so a transform can be
 * handed to `captureEdit` and applied without the caller knowing which buffer
 * it is about to land in.
 */
data class CaretText(val text: String, val caret: Int = text.length) {

    /** [caret] guaranteed inside [text] and never splitting a surrogate pair. */
    val at: Int get() = clamp(caret)

    /** [text] typed at the caret. */
    fun typed(insert: String): CaretText {
        if (insert.isEmpty()) return this
        val i = at
        return CaretText(text.substring(0, i) + insert + text.substring(i), i + insert.length)
    }

    /** Backspace: the character before the caret, whole emoji included. */
    fun deletedBackward(length: Int = 1): CaretText {
        val i = at
        if (i <= 0) return this
        val from = (i - length.coerceAtLeast(1)).coerceAtLeast(0).let { safeBack(it) }
        return CaretText(text.substring(0, from) + text.substring(i), from)
    }

    /** Forward delete: the character after the caret. */
    fun deletedForward(): CaretText {
        val i = at
        if (i >= text.length) return this
        return CaretText(text.substring(0, i) + text.substring(stepForward(text, i)), i)
    }

    /** The caret moved [delta] characters, clamped to the buffer's ends. */
    fun caretMoved(delta: Int): CaretText {
        var i = at
        repeat(kotlin.math.abs(delta)) {
            i = if (delta < 0) stepBack(text, i) else stepForward(text, i)
        }
        return copy(caret = i)
    }

    /** The caret put at [index] — what a tap in the middle of the text asks for. */
    fun caretAt(index: Int): CaretText = copy(caret = clamp(index))

    /**
     * The word the caret is inside or at the end of, and where it starts.
     *
     * This is what a suggestion is *about*: the strip offers completions of
     * this word and a pick replaces exactly this span. A caret sitting on
     * whitespace or punctuation has no word, and the strip then shows
     * next-word predictions instead — the same split the field path makes.
     */
    fun wordAtCaret(): CaretWord {
        val i = at
        var start = i
        while (start > 0 && isWordChar(text[start - 1])) start--
        var end = i
        while (end < text.length && isWordChar(text[end])) end++
        // Only the part in front of the caret is "typed so far"; the tail is
        // the rest of a word being edited in the middle and is kept as-is, so
        // a pick over `he|llo` replaces the whole of `hello` rather than
        // leaving `…llo` stranded.
        return CaretWord(start = start, end = end, typed = text.substring(start, i))
    }

    /**
     * The word before [wordAtCaret], for the prediction context — the same
     * `previousWord` the field path feeds the engine.
     */
    fun wordBeforeCaret(): String? {
        var i = wordAtCaret().start
        while (i > 0 && !isWordChar(text[i - 1])) i--
        if (i <= 0) return null
        var start = i
        while (start > 0 && isWordChar(text[start - 1])) start--
        return text.substring(start, i).takeIf { it.isNotEmpty() }
    }

    /**
     * [word] put in place of the word at the caret, with the caret past it and
     * past the space that separates it from whatever follows.
     *
     * [spaceAfter] is the trailing space a strip pick and a glide both end
     * with. A gap that is already there is stepped over rather than doubled,
     * so picking a word in the middle of a sentence leaves the caret at the
     * start of the next word either way — which is where the next keystroke
     * belongs, and what the field's own strip does.
     */
    fun replacedWordAtCaret(word: String, spaceAfter: Boolean): CaretText {
        val span = wordAtCaret()
        val tail = text.substring(span.end)
        val head = text.substring(0, span.start) + word
        if (!spaceAfter) return CaretText(head + tail, head.length)
        if (tail.startsWith(" ")) return CaretText(head + tail, head.length + 1)
        return CaretText("$head $tail", head.length + 1)
    }

    private fun clamp(index: Int): Int {
        val i = index.coerceIn(0, text.length)
        return safeBack(i)
    }

    /** [i], moved back one if it would sit between a surrogate pair. */
    private fun safeBack(i: Int): Int =
        if (i in 1 until text.length &&
            Character.isHighSurrogate(text[i - 1]) && Character.isLowSurrogate(text[i])
        ) {
            i - 1
        } else {
            i
        }

    companion object {
        fun stepBack(s: String, at: Int): Int =
            if (at >= 2 && Character.isLowSurrogate(s[at - 1]) && Character.isHighSurrogate(s[at - 2])) at - 2
            else (at - 1).coerceAtLeast(0)

        fun stepForward(s: String, at: Int): Int =
            if (at + 1 < s.length && Character.isHighSurrogate(s[at]) && Character.isLowSurrogate(s[at + 1])) at + 2
            else (at + 1).coerceAtMost(s.length)

        /**
         * What counts as part of a word for the caret's purposes. Letters and
         * digits in any script, plus the two marks that sit inside words in
         * the languages this keyboard ships — the apostrophe of `don't` and
         * `c'est`, and the hyphen of `well-known`.
         */
        fun isWordChar(c: Char): Boolean =
            c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'
    }
}

/** The span [CaretText.wordAtCaret] found, and the part of it already typed. */
data class CaretWord(val start: Int, val end: Int, val typed: String)

/**
 * Which keyboard-owned field has the keys.
 *
 * Read off the state by [KeyboardUiState.captureTarget], once, in the priority
 * order the typed-text path always used. Every ladder that used to re-derive
 * this branches on the enum instead, so the seven copies cannot drift apart
 * again — they already had, which is what [KeyboardUiState.keysTakenByKeyboard]
 * was an incomplete patch for.
 */
enum class CaptureTarget(
    /**
     * Whether the field holds *words* — so a glide can spell into it and the
     * suggestion strip has something to offer. False for the two numeric
     * fields, where a word list means nothing, and for the typing test, which
     * decodes its own strokes against the prompt it is scoring.
     */
    val takesWords: Boolean,
    /**
     * Whether the caret in this field can be moved. False only for the typing
     * test: its score counts keystrokes in the order they were made, so a
     * character inserted behind the ones already scored would be counted
     * against a word it was never typed into.
     */
    val movableCaret: Boolean = true,
) {
    TYPING_TEST(takesWords = false, movableCaret = false),
    AI_CUSTOM(takesWords = true),
    PLUGIN(takesWords = true),
    FIND_QUERY(takesWords = true),
    FIND_REPLACEMENT(takesWords = true),
    LEARN_EDIT(takesWords = true),
    CALC(takesWords = false),
    CONVERTER(takesWords = false),
    WORD_SPELL(takesWords = true),
    EMOJI_SEARCH(takesWords = true),
    MEDIA_SEARCH(takesWords = true),
    DICTIONARY_SEARCH(takesWords = true),
    CLIPBOARD_SEARCH(takesWords = true),
    /** The clipboard panel's clip editor: free text, newlines and all. */
    CLIP_EDIT(takesWords = true),
    ;

    /**
     * Whether the field's own editor owns the caret rather than the shared one.
     * Only the word card, whose [WordSpell] carries anchor and cursor for #204.
     */
    val ownsCaret: Boolean get() = this == WORD_SPELL
}

/**
 * Where a caret sits, in which buffer, and what that buffer held at the time.
 *
 * [key] identifies the buffer rather than only its [CaptureTarget], because two
 * of them are really families: a plugin has one box per widget id, and Find and
 * replace's two fields are one panel.
 *
 * [text] is what makes this safe to leave lying around. A caret is only used
 * when both the key *and* the text still match what is focused; anything else
 * — another field taking the keys, a query prefilled from the user's text, a
 * tool writing its own result into the box, a panel closing and reopening —
 * puts the caret back at the end, which is exactly where it sat before any of
 * these fields had one. So no write site has to remember to reset it, and a
 * path that sets a buffer directly cannot leave a caret pointing into text
 * that is no longer there.
 */
data class CaptureCaret(val key: String, val at: Int, val text: String)
