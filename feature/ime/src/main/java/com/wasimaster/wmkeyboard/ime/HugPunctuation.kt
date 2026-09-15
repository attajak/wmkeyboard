package com.wasimaster.wmkeyboard.ime

/**
 * How many spaces to take out from in front of the caret when [mark] is about
 * to land, under the "remove a space before punctuation" rule
 * (`AutoTextSettings.hugPunctuation`). 0 means leave the field alone.
 *
 * [before] is the text behind the caret, [marks] the user's list of marks the
 * rule applies to. The rule is deliberately narrow: only plain spaces (a line
 * break is a paragraph, not a slip), only when they follow a word, a number
 * or something that closes one (a bracket, a quote), and only when the whole
 * run is visible in [before]. A run that fills the entire read may continue
 * past it, and a mark at the start of a line, or after another mark, is not a
 * spacing slip but whatever the user meant.
 */
fun straySpacesBefore(before: CharSequence, mark: Char, marks: String): Int {
    if (mark !in marks) return 0
    var spaces = 0
    var i = before.length
    while (i > 0 && before[i - 1] == ' ') {
        spaces++
        i--
    }
    if (spaces == 0 || i == 0) return 0
    val prev = before[i - 1]
    return if (prev.isLetterOrDigit() || prev in HUG_CLOSERS) spaces else 0
}

/**
 * Whether a mark landing with [before] behind the caret belongs to a number,
 * so the automatic space after punctuation (`AutoTextSettings.spaceAfterPunctuation`)
 * holds off (#206). "5,000", "3.14" and "5:00" carry on past the mark, and a
 * space typed for the user splits the number in two. Any script's digits
 * count, so "৫,০০০" is kept whole too.
 */
fun markContinuesNumber(before: CharSequence): Boolean = before.lastOrNull()?.isDigit() == true

/** What may stand in front of a hugged mark besides a word: things that close one. */
private const val HUG_CLOSERS = ")]}\"'”’"

/** Characters the hug rule reads behind the caret; longer than any honest run of spaces. */
const val HUG_LOOKBACK = 16
