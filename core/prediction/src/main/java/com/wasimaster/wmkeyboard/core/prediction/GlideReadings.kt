package com.wasimaster.wmkeyboard.core.prediction

import kotlin.math.abs

/**
 * What each recently glided word's stroke also read as, tied to where that
 * word stands in the field.
 *
 * Going back to a swiped word puts these in front of the strip (#115). They
 * used to be keyed by the word's spelling, which is fine for a dozen words
 * and wrong for a message's worth: "form" swiped in the first paragraph and
 * "form" tapped out in the fourth are one key, so reading the tapped one back
 * offered the old stroke's "from" and taking it taught a correction of a
 * stroke that word never had (#199). So a reading is found by spelling *and*
 * place, and the window can be as long as [LearningBuffer]'s.
 *
 * Place is an anchor, the caret the commit's own selection echo reported,
 * so it sits at the word's end give or take the spaces a glide adds around
 * it ([SLACK]). Keeping anchors true as the text changes in front of them is
 * the hard part, because nobody reports an insertion:
 *
 * * **The keyboard's deletes and replacements** are reported ([onDeleted],
 *   [onReplaced]) and move the anchors behind them exactly.
 * * **Everything else** — a letter typed in front, a word glided into the
 *   middle of a sentence — only shows up as the caret moving. A move of a
 *   few characters in front of a word *may* have been an edit, so it widens
 *   how far that word's anchor is trusted to have drifted ([Entry.drift]).
 *   A long jump is the user tapping somewhere, not typing, and widens
 *   nothing: counting it would let a word match a copy of itself a paragraph
 *   away, which is the mistake this class exists to stop.
 *
 * A match that needed some of that allowance re-anchors the word where it was
 * found, so a word read back once is exact again. A paste in front of a word
 * is longer than any allowance and loses that word's readings, and so does
 * text the app itself rewrites. Both fail towards an ordinary strip, never
 * towards another stroke's readings.
 *
 * Purely in-memory and per-field, like the buffer it mirrors.
 */
class GlideReadings(private val capacity: Int = DEFAULT_CAPACITY) {

    private class Entry(val key: String, val readings: List<String>) {
        var anchor: Int = UNANCHORED

        /** How far [anchor] may have drifted, from caret moves in front of it. */
        var drift: Int = 0
    }

    /** Oldest first; the unanchored ones, if any, are the newest. */
    private val entries = ArrayDeque<Entry>()

    /** The last collapsed caret reported, or [UNANCHORED] when unknown. */
    private var caret = UNANCHORED

    /**
     * No anchor is past this. Only ever too high (a shift down or an eviction
     * does not lower it), which costs a loop, never a missed drift.
     */
    private var furthest = UNANCHORED

    val size: Int get() = entries.size

    /**
     * Keeps [readings], what the stroke that committed [word] also had to
     * offer. Anchored by the next caret report. A single reading teaches
     * nothing (there was no other answer), so it is not kept.
     */
    fun remember(word: String, readings: List<String>) {
        if (readings.size < 2) return
        val key = WordKey.of(word)
        if (key.isEmpty()) return
        entries.addLast(Entry(key, readings))
        while (entries.size > capacity) entries.removeFirst()
    }

    /**
     * The caret was reported at [selStart]..[selEnd].
     *
     * Runs on every keystroke. Typing at the end of the text moves the caret
     * in front of nothing, so that case only compares two numbers.
     */
    fun onCaret(selStart: Int, selEnd: Int = selStart) {
        val from = caret
        caret = if (selStart >= 0) selStart else UNANCHORED
        if (selStart < 0 || entries.isEmpty()) return
        // A range selection is not a resting place: nothing anchors to it,
        // and whatever replaces it is reported as a delete first.
        if (selStart != selEnd) return
        if (from >= 0) {
            val moved = abs(selStart - from)
            val front = minOf(from, selStart)
            if (moved in 1..EDIT_REACH && front < furthest) {
                for (entry in entries) {
                    if (entry.anchor != UNANCHORED && entry.anchor > front) entry.drift += moved
                }
            }
        }
        var i = entries.lastIndex
        while (i >= 0 && entries[i].anchor == UNANCHORED) {
            entries[i].anchor = selStart
            i--
        }
        if (i < entries.lastIndex) furthest = maxOf(furthest, selStart)
    }

    /** The keyboard removed `[start, end)` of the field. */
    fun onDeleted(start: Int, end: Int) = onReplaced(start, end, 0)

    /**
     * The keyboard replaced `[start, end)` of the field with [length]
     * characters. Anchors behind the span move by the difference; one inside
     * it lands at the replacement's end, where a word written over the old
     * one now ends. It keeps its old spelling, so it matches nothing new.
     */
    fun onReplaced(start: Int, end: Int, length: Int) {
        if (start < 0 || end < start || length < 0) return
        caret = start + length
        if (entries.isEmpty()) return
        val delta = length - (end - start)
        var highest = UNANCHORED
        for (entry in entries) {
            if (entry.anchor == UNANCHORED) continue
            if (entry.anchor >= end) {
                entry.anchor += delta
            } else if (entry.anchor > start) {
                entry.anchor = start + length
            }
            highest = maxOf(highest, entry.anchor)
        }
        furthest = highest
    }

    /**
     * The other words the glide that wrote the [word] starting at [start]
     * read, or empty when that word was not glided, or was glided too long
     * ago to still be here.
     */
    fun readingsAt(word: String, start: Int): List<String> =
        find(word, start)?.readings?.filterNot { it.equals(word, ignoreCase = true) }.orEmpty()

    /** Forgets the readings of the [word] starting at [start]: one was taken. */
    fun forget(word: String, start: Int) {
        find(word, start)?.let { entries.remove(it) }
    }

    fun clear() {
        entries.clear()
        caret = UNANCHORED
        furthest = UNANCHORED
    }

    /**
     * The entry for the [word] at [start]: same spelling, anchored within
     * [SLACK] of the word or within its own drift of that. The closest wins,
     * and the newest of equally close ones, since a word swiped over a
     * deleted copy of itself is the one standing there now.
     *
     * Re-anchors what it finds. The word has just been seen at [start], so
     * however far it had drifted, it is exactly there now.
     */
    private fun find(word: String, start: Int): Entry? {
        if (start < 0 || entries.isEmpty()) return null
        val key = WordKey.of(word)
        if (key.isEmpty()) return null
        val end = start + word.length
        var best: Entry? = null
        var bestOff = Int.MAX_VALUE
        for (entry in entries) {
            if (entry.anchor == UNANCHORED || entry.key != key) continue
            val off = when {
                entry.anchor < start - SLACK -> start - SLACK - entry.anchor
                entry.anchor > end + SLACK -> entry.anchor - end - SLACK
                else -> 0
            }
            if (off > entry.drift || off > bestOff) continue
            best = entry
            bestOff = off
        }
        best ?: return null
        if (bestOff > 0) {
            best.anchor = end
            furthest = maxOf(furthest, end)
        }
        best.drift = 0
        return best
    }

    companion object {
        /**
         * Swiped words kept. The same window [LearningBuffer] holds words
         * open for, so any word still waiting to settle that a glide wrote
         * can still be fixed from its stroke (#199).
         */
        const val DEFAULT_CAPACITY = LearningBuffer.DEFAULT_CAPACITY

        /**
         * How far an anchor may sit from the word it belongs to. The echo
         * that anchors a glide can land before its leading space or after
         * its trailing one.
         */
        const val SLACK = LearningBuffer.ANCHOR_SLACK

        /**
         * The longest caret move that may have been an edit rather than a
         * jump: a glided word with its spaces, or a picked phrase.
         */
        const val EDIT_REACH = 64

        private const val UNANCHORED = -1
    }
}
