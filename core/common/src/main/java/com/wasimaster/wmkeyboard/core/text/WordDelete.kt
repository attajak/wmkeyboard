package com.wasimaster.wmkeyboard.core.text

/**
 * Word-at-a-time deletion, as the two delete keys use it.
 *
 * One step takes the whitespace immediately next to the cursor *and* the word
 * beyond it, so a cursor parked after "hello world " still eats "world " in
 * one step instead of spending a whole step on the space.
 */
object WordDelete {

    /**
     * UTF-16 units to delete for one word step, given the text before the
     * cursor. 0 when there is nothing left.
     *
     * Punctuation runs count as their own word, so a swipe never stalls on
     * "..." — but a run of letters stops at the first non-letter, leaving
     * "don't" as two steps rather than eating the whole line.
     */
    fun lengthBefore(before: CharSequence): Int {
        if (before.isEmpty()) return 0
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0) {
            val letters = before[i - 1].isLetterOrDigit()
            while (i > 0 && !before[i - 1].isWhitespace() &&
                before[i - 1].isLetterOrDigit() == letters
            ) i--
        }
        return before.length - i
    }

    /**
     * The same step measured the other way: UTF-16 units a forward word delete
     * takes, given the text after the cursor (issue #226). 0 when the text ends
     * there.
     *
     * The mirror of [lengthBefore] in every detail, including taking the
     * whitespace the cursor is already sitting on before the word past it, so
     * ⌦ held in "hello | world" eats " world" in one step rather than spending
     * a step on the space.
     */
    fun lengthAfter(after: CharSequence): Int {
        if (after.isEmpty()) return 0
        var i = 0
        while (i < after.length && after[i].isWhitespace()) i++
        if (i < after.length) {
            val letters = after[i].isLetterOrDigit()
            while (i < after.length && !after[i].isWhitespace() &&
                after[i].isLetterOrDigit() == letters
            ) i++
        }
        return i
    }
}
