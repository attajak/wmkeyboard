package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.selection.TextEdit

/**
 * The arithmetic between the matcher's ranges and the field's selection,
 * kept out of the service so it has a test.
 */
object FindReplaceGlue {

    /** The match that is exactly the selection [selStart]..[selEnd], or -1. */
    fun currentMatchIndex(matches: List<IntRange>, selStart: Int, selEnd: Int): Int =
        matches.indexOfFirst { it.first == selStart && it.last + 1 == selEnd }

    /**
     * The match [delta] steps from [current], wrapping. With no current match
     * the selection decides: forward goes to the first match at or after it,
     * back to the last one before it, each wrapping when there is none.
     */
    fun stepIndex(matches: List<IntRange>, current: Int, selStart: Int, delta: Int): Int {
        if (matches.isEmpty()) return -1
        if (current >= 0) return Math.floorMod(current + delta, matches.size)
        return if (delta > 0) {
            matches.indexOfFirst { it.first >= selStart }.let { if (it < 0) 0 else it }
        } else {
            matches.indexOfLast { it.first < selStart }.let { if (it < 0) matches.lastIndex else it }
        }
    }

    /**
     * The matches left after the one at [replaced] was swapped for text
     * [delta] characters longer: it is gone, and everything after it moved.
     */
    fun shiftMatches(matches: List<IntRange>, replaced: Int, delta: Int): List<IntRange> {
        val anchor = matches.getOrNull(replaced)?.first ?: return matches
        return matches.filterIndexed { index, _ -> index != replaced }
            .map { if (it.first > anchor) (it.first + delta)..(it.last + delta) else it }
    }

    /** Where [offset] in the text before [edits] sits in the text after them. */
    fun afterOffset(edits: List<TextEdit>, offset: Int): Int =
        offset + edits.filter { it.start < offset }.sumOf { it.text.length - (it.end - it.start) }
}
