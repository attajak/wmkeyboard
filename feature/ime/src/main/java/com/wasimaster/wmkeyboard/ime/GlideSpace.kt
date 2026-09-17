package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.prediction.WordContext
import kotlin.math.hypot

/**
 * The text and shape decisions behind the spaces the keyboard types for the
 * user — after a glided word, after a word picked from the suggestion strip,
 * after a punctuation mark — and the possessive flick that can extend one.
 * Pulled out of `WMKeyboardService` so they can be checked without an
 * `InputConnection` or a live keyboard. The service does the field reads and
 * the edits; these only say what a read means.
 */

/**
 * Marks that take back a space the keyboard typed, so they hug the word they
 * belong to: "hello." and "(hello)", never "hello .".
 *
 * The auto-space marks plus the closers, which is the one place the two sets
 * differ. A closing bracket or quote comes *after* the space in the
 * auto-space-after-punctuation rule's job (spacing what follows a mark) and
 * *before* it in this one (removing what precedes a mark). Openers are absent
 * on purpose — "hello (world)" wants its space kept. So are the CJK wide forms,
 * which no glide produces: the decoder is Latin-only.
 *
 * The straight double quote is not here because it is both a closer and an
 * opener; [AMBIGUOUS_QUOTES] and [closesQuote] settle which one it is. The
 * curly close quote is unambiguous, so it stays in the plain list.
 *
 * The slash is here rather than only in the URL list: "and/or", "he/she" and
 * "24/7" are prose, and a slash typed straight after a word is joining it to
 * the next one far more often than it is standing between two spaced phrases.
 *
 * The apostrophe, the dashes, the backslash and the underscore joined it on the
 * same argument (issue #123): every one of them typed straight after a word is
 * building one longer word — "hello's", "well-known", "snake_case" — far more
 * often than it is standing between two spaced ones. The spaced reading is
 * still one keypress away, since a space typed after the mark is the user's own
 * and nothing takes it back.
 *
 * `#` and `@` are deliberately absent, and they are the reason this list is not
 * simply "every mark". Those two attach to what comes *after* them: "check this
 * #cool" and "ask @wasi" both want the space in front kept. They are in
 * [WORD_OPENERS] instead, which is the same statement pointing the other way.
 */
private val AUTO_SPACE_SWALLOWERS = charArrayOf(
    '.', '!', '?', '।', ',', ';', ':', ')', ']', '}', '”', '…', '%',
    '/', '\\', '\'', '’', '-', '–', '—', '_',
)

/**
 * Quote characters that stand for both ends of a quotation, so whether they
 * take the space back depends on the text behind them (issue #34): the `"` of
 * `he said "hello"` opens on its first press and closes on its second, and
 * swallowing the space both times gives `he said"hello"`.
 *
 * The straight apostrophe is deliberately absent. It is a letter inside a word
 * ("don't", "rock 'n' roll"), so counting its appearances says nothing about
 * which end of a quotation the next one is.
 */
private val AMBIGUOUS_QUOTES = charArrayOf('"')

/**
 * The marks that join the list in a URL field, where a glided word is a host
 * or a path segment rather than a word in a sentence: "example" then "#" is
 * "example#", never "example #". `#` and `@` are here and not in the prose list
 * because a fragment and a user part are inside one address, while a hashtag
 * and a mention start a new word.
 */
private val URI_AUTO_SPACE_SWALLOWERS = charArrayOf('#', '&', '=', '@', '+', '~')

/**
 * Marks a glided word starts straight after, with no space between: the openers,
 * which is the mirror of the closers in [AUTO_SPACE_SWALLOWERS].
 *
 * A glide commits a space in front of its word so two swiped words do not run
 * together, and that space is wrong after an opening bracket or quote —
 * `he said "` then a glided "hello" gave `he said " hello` (issue #34). The
 * straight double quote is absent for the usual reason: it is both ends of a
 * quotation, and [spacesBeforeGlidedWord] asks [closesQuote] which one it is.
 *
 * `#` and `@` are here rather than in [WORD_JOINERS] because they attach
 * forward whatever stands behind them: "check this #" then a glided "cool" is
 * "#cool", and the space in front of the hash is the one that belongs (issue
 * #123). The apostrophes are here for the same reason from the other end —
 * "he said '" opens a quotation and "don'" is a contraction, and neither wants
 * a space after it.
 */
private val WORD_OPENERS =
    charArrayOf('(', '[', '{', '“', '‘', '«', '¿', '¡', '#', '@', '\'', '’')

/**
 * Marks that hold two words together rather than standing between them, so a
 * glided word landing on one joins it: `The/` then a glided "And" is `The/And`,
 * not `The/ And` (issue #34).
 *
 * Unlike an opener, one of these only joins when it is itself attached to the
 * word behind it. The same dash spells `well-known` and `hello - world`, and
 * the space in front of it is what tells the two apart: a mark with a word up
 * against it is building one long word, and a mark with a space in front of it
 * is standing on its own between two.
 *
 * Most of these also take the space in front of them back
 * ([AUTO_SPACE_SWALLOWERS]), so the attached reading is the one a glided word
 * plus a typed mark lands in by itself, and the spaced one is what a user gets
 * by typing the space that says so.
 */
private val WORD_JOINERS = charArrayOf('/', '\\', '&', '=', '-', '–', '—', '_', '+', '~')

/**
 * Whether a glided word landing at the end of [textBefore] earns the space a
 * glide types in front of it.
 *
 * No, in five cases: there is nothing to be separated from, the text already
 * ends in a space of its own, it ends in an opener the word belongs against, it
 * ends in a double quote that opened a quotation rather than closing one, or it
 * ends in a [WORD_JOINERS] mark that is itself joined to the word behind it.
 *
 * A URL field never earns one at all. An address has no spaces in it, so a word
 * glided into the middle of one joins whatever it lands on, whether that is a
 * dot, a slash or the tail of the host name.
 *
 * [textBefore] is the text in front of the caret. Only its last character
 * decides the answer, bar the quote, which is counted over the line, and a
 * joiner, which also asks what is in front of it.
 */
internal fun spacesBeforeGlidedWord(
    textBefore: String,
    fieldKind: FieldKind = FieldKind.TEXT,
): Boolean {
    if (fieldKind == FieldKind.URI) return false
    val last = textBefore.lastOrNull() ?: return false
    if (WordContext.isSpaceLike(last)) return false
    if (last in WORD_OPENERS) return false
    // The quote behind the caret opened a quotation exactly when a quote typed
    // now would close it, so the word goes straight up against it.
    if (last in AMBIGUOUS_QUOTES) return !closesQuote(textBefore, last)
    if (last in WORD_JOINERS) {
        // A joiner at the very start of the line has no word to be attached to,
        // and nothing to be separated from either.
        val beforeJoiner = textBefore.dropLast(1).lastOrNull() ?: return false
        return WordContext.isSpaceLike(beforeJoiner)
    }
    return true
}

/**
 * Whether a [quote] typed at the end of [textBefore] closes a quotation rather
 * than opening one, counted by parity over the current line.
 *
 * The line is the unit rather than the whole field because an unclosed quote in
 * a paragraph above must not flip every quote below it, and because a field can
 * hold more text than the caller wants to read to answer this.
 */
internal fun closesQuote(textBefore: String, quote: Char): Boolean =
    textBefore.substringAfterLast('\n').count { it == quote } % 2 == 1

/**
 * Whether typing [text] right after a space the keyboard typed — after a glided
 * word, a word picked from the suggestion strip, or the auto-space that follows
 * a punctuation mark — should take that space back.
 *
 * [fieldKind] widens the set of marks in a URL field (see
 * [URI_AUTO_SPACE_SWALLOWERS]). [textBefore] is the text in front of the caret,
 * read only for an [AMBIGUOUS_QUOTES] mark and therefore passed as a lambda: on
 * every other keystroke it costs an editor round trip for nothing.
 */
internal fun swallowsAutoSpace(
    text: String,
    fieldKind: FieldKind = FieldKind.TEXT,
    textBefore: () -> String = { "" },
): Boolean {
    if (text.length != 1) return false
    val c = text[0]
    if (c in AMBIGUOUS_QUOTES) return closesQuote(textBefore(), c)
    if (c in AUTO_SPACE_SWALLOWERS) return true
    return fieldKind == FieldKind.URI && c in URI_AUTO_SPACE_SWALLOWERS
}

/**
 * How many characters the glided [word] occupies at the end of [textBefore] —
 * the word, plus the space the commit typed after it when one is there. 0 when
 * the text does not end in that word at all, which is the signal to leave the
 * field alone.
 *
 * Backspace-undo and the strip's tap-to-replace both have to take the space
 * along with the word: without it an undo leaves a stray gap, and a replacement
 * lands on the far side of it ("hello world" out of one swipe). Measured off
 * the text rather than off a flag, so a handwritten word — which arms the same
 * undo but types no trailing space — comes out right too.
 *
 * [textBefore] is the last `word.length + 1` characters before the caret, which
 * is all either caller needs to read.
 */
internal fun glideCommitLength(textBefore: String, word: String): Int = when {
    word.isEmpty() -> 0
    textBefore.endsWith("$word ") -> word.length + 1
    textBefore.endsWith(word) -> word.length
    else -> 0
}

/** How near its two keys the possessive flick starts and ends, in key widths. */
internal const val POSSESSIVE_REACH_WIDTHS = 0.7f

/** How much longer than the straight line between those keys the flick may be. */
private const val POSSESSIVE_MAX_DETOUR = 1.6f

/**
 * Whether [points] is the short straight flick that appends `'s`: [from] (the key
 * standing in for the apostrophe) to [to] (the `s` key), in the same pixel space,
 * on a keyboard whose keys are [keyWidthPx] wide.
 *
 * Three tests, and the third is the one that carries the feature. A stroke can
 * start on the punctuation key and end on `s` while spelling a real word on the
 * way, so the travelled length has to agree with the direct distance between the
 * two keys: a word-shaped detour is longer than that and goes on to decode as the
 * word it is. Without it every swipe that happened to begin near the comma and end
 * near `s` would be eaten.
 */
internal fun possessiveFlick(
    points: List<GesturePoint>,
    from: KeyCenter,
    to: KeyCenter,
    keyWidthPx: Float,
): Boolean {
    if (keyWidthPx <= 0f || points.size < 2) return false
    val reach = keyWidthPx * POSSESSIVE_REACH_WIDTHS
    val start = points.first()
    val end = points.last()
    if (!within(start.x, start.y, from.x, from.y, reach)) return false
    if (!within(end.x, end.y, to.x, to.y, reach)) return false

    val direct = hypot(to.x - from.x, to.y - from.y)
    if (direct <= 0f) return false
    var travelled = 0f
    for (i in 1 until points.size) {
        travelled += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }
    return travelled <= direct * POSSESSIVE_MAX_DETOUR
}

/**
 * The strip after a glide whose word came off the ambiguity picker: [picked]
 * first, then the decoder's own order with it removed. The strip bolds index
 * zero (or swaps it into the centre slot), so a picked word has to lead the
 * list the way the decoder's leader does or the strip would bold the word the
 * user just turned down. A pick the decode no longer holds is kept all the
 * same: it was on screen when the finger lifted, and dropping it is exactly
 * the bug the picker's freeze exists to prevent (#96).
 */
internal fun glideStripOrder(candidates: List<String>, picked: String): List<String> =
    listOf(picked) + candidates.filterNot { it == picked }

private fun within(x: Float, y: Float, cx: Float, cy: Float, reach: Float): Boolean {
    val dx = x - cx
    val dy = y - cy
    return dx * dx + dy * dy <= reach * reach
}
