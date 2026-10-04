package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.WordKey
import com.wasimaster.wmkeyboard.core.prediction.WordSource
import java.util.BitSet

/**
 * The capitals a word list spells its words with, kept apart from the list
 * (#481).
 *
 * Every lookup here is by lower-case key, and a curated list is not lower
 * case: AOSP's German list writes `Zeit`, `Haus`, `Berlin`, 86,000 of its
 * 205,000 words. Packed as written, those words sat under keys that nothing
 * typed in lower case ever reached, so "haus" was not a word at all and
 * neither a completion nor a swipe could find it.
 *
 * [fold] puts every word under its lower-case key and returns what was lost
 * as a second trie: the same keys, with the *frequency* slot holding the
 * shape instead of a count. It is written beside the list as `caps.wmdict`
 * and mapped the same way, so a language's capitals cost no heap.
 *
 * A word the list also spells in lower case keeps no shape. German has both
 * `essen` and `Essen`, English both `may` and `May`, and what was typed
 * cannot say which was meant.
 */
object DictionaryCapitals {

    /**
     * A shape is a bit per character, set where that character is a capital.
     * Eleven bits is what a dictionary frequency stores exactly; a capital
     * further in than that is only kept for a word that is capitals throughout.
     */
    private const val MASK_BITS = 11

    /** Every character a capital, whatever the length: `USA`, `UNESCO`. */
    private const val ALL_CAPS = (1 shl MASK_BITS) - 1

    /** [capitals] is null when the list spells nothing with a capital. */
    class Folded(val capitals: PackedTrie?)

    /**
     * Rewrites the first [count] of [words] to their lower-case keys, in
     * place, and returns the capitals that took away.
     */
    fun fold(words: Array<String?>, count: Int): Folded {
        val shapes = HashMap<String, Int>()
        val folded = BitSet(count)
        for (i in 0 until count) {
            val word = words[i] ?: continue
            if (!hasCapital(word)) continue
            val key = WordKey.of(word)
            // Sorted by frequency, so the first spelling met is the common one.
            if (key !in shapes) shapes[key] = shapeOf(word, key)
            words[i] = key
            folded.set(i)
        }
        if (shapes.isEmpty()) return Folded(null)
        // The twins: a key the list also wrote in lower case, itself.
        var i = folded.nextClearBit(0)
        while (i < count) {
            words[i]?.let(shapes::remove)
            i = folded.nextClearBit(i + 1)
        }
        return Folded(pack(shapes))
    }

    /**
     * The capitals of a plain list of spellings, one a line, `#` for a
     * comment: what the app ships for a bundled list that has none (#517).
     */
    fun ofSpellings(lines: Sequence<String>): PackedTrie? {
        val words = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList<String?>().toTypedArray()
        return fold(words, words.size).capitals
    }

    private fun pack(shapes: Map<String, Int>): PackedTrie? {
        val kept = shapes.filterValues { it > 0 }
        if (kept.isEmpty()) return null
        return PackedTrie.of(kept.map { it.key to it.value })
    }

    /** [key] as [capitals] spells it, or null when it has no capital there. */
    fun spelling(capitals: WordSource, key: String): String? {
        val shape = capitals.frequencyOf(key)
        if (shape <= 0) return null
        if (shape == ALL_CAPS) return key.uppercase()
        val out = StringBuilder(key)
        for (i in 0 until minOf(key.length, MASK_BITS)) {
            if (shape and (1 shl i) != 0) out.setCharAt(i, key[i].uppercaseChar())
        }
        return out.toString()
    }

    /**
     * The shape that turns [key] back into [word], or 0 when no shape does:
     * a capital past the eleventh character, or a letter whose two cases are
     * not the same length.
     */
    private fun shapeOf(word: String, key: String): Int {
        val surface = WordKey.surface(word)
        if (surface.length != key.length) return 0
        if (key.length > 1 && surface == key.uppercase()) return ALL_CAPS
        var shape = 0
        for (i in surface.indices) {
            if (surface[i] == key[i]) continue
            if (i >= MASK_BITS || key[i].uppercaseChar() != surface[i]) return 0
            shape = shape or (1 shl i)
        }
        return if (shape == ALL_CAPS) 0 else shape
    }

    private fun hasCapital(word: String): Boolean {
        for (c in word) if (c.isUpperCase() || c.isTitleCase()) return true
        return false
    }
}
