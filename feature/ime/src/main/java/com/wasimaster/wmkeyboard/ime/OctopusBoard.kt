package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.prediction.OctopusWord

/**
 * The words floating over the keys, by the anchor code point of the key each
 * one hangs off. A key's list is in tier order — nearest the key first — and
 * never empty; a key with nothing to offer is absent (#136).
 *
 * One word per key was the whole board until the stack setting
 * ([com.wasimaster.wmkeyboard.core.settings.OctopusSettings.wordsPerKey]);
 * [top] is the read every surface that still wants one word makes.
 */
typealias OctopusBoard = Map<Int, List<OctopusWord>>

/** The word nearest [keyCodePoint], or null when the key carries none. */
fun OctopusBoard.top(keyCodePoint: Int): OctopusWord? = this[keyCodePoint]?.firstOrNull()

/** Every word on the board, best first within each key. */
fun OctopusBoard.allWords(): List<OctopusWord> =
    if (isEmpty()) emptyList() else values.flatten()

/** A board with every word [blocked] says so taken off, and its emptied keys with them. */
inline fun OctopusBoard.without(blocked: (OctopusWord) -> Boolean): OctopusBoard {
    if (isEmpty()) return this
    val out = LinkedHashMap<Int, List<OctopusWord>>(size)
    for ((key, words) in this) {
        val kept = words.filterNot(blocked)
        if (kept.isNotEmpty()) out[key] = kept
    }
    return out
}

/** [words] as a board: grouped by key, each key's list in the order given. */
fun List<OctopusWord>.toOctopusBoard(): OctopusBoard =
    if (isEmpty()) emptyMap() else groupBy { it.keyCodePoint }
