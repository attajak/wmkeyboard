package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.PhoneticIndex

/**
 * A romanized selection into its own script and back, for a whole selection:
 * Banglish to Bangla, Hinglish to Hindi — whichever [scheme] it is built for.
 *
 * Forward, each Latin word is resolved the way the strip would have
 * committed it: the spelling map first (tmr → তোমার, keyboard → কিবোর্ড),
 * then the phonetic index, which folds the spelling so dialect and habit
 * variants (asi, achi, achhi) all land on the dictionary word, and last the
 * literal transliteration. The index only beats the literal when it is
 * clearly the commoner word, the same confidence rule `SuggestionEngine`
 * applies, so a correctly spelled word is not swapped for a near tie. Only
 * letter runs are converted: digits and punctuation stay as they were, so
 * `2024` is not turned into native figures and a full stop stays a full
 * stop.
 *
 * Backward, a known word takes the most readable spelling the map has for
 * it, and anything else goes through the scheme's romanizer. The inverted map
 * is built once, lazily, on whichever thread first asks; the service asks
 * from a background one.
 */
class RomanizedConverter(
    private val scheme: PhoneticScheme,
    private val spellings: SpellingMap,
    private val phonetic: PhoneticIndex,
) {

    /** [text] with every Latin word in the scheme's script, or null when nothing changed. */
    fun toNative(text: String): String? {
        val out = StringBuilder(text.length * 2)
        var changed = false
        var i = 0
        while (i < text.length) {
            if (isLatinLetter(text[i])) {
                var j = i
                while (j < text.length && isLatinLetter(text[j])) j++
                val word = text.substring(i, j)
                val native = wordToNative(word)
                if (native != word) changed = true
                out.append(native)
                i = j
            } else {
                out.append(text[i])
                i++
            }
        }
        return if (changed) out.toString() else null
    }

    /** [text] with every word of the scheme's script in Latin letters, or null when nothing changed. */
    fun toRoman(text: String): String? {
        val norm = scheme.normalize(text)
        val out = StringBuilder(norm.length)
        var changed = false
        var i = 0
        while (i < norm.length) {
            val c = norm[i]
            when {
                scheme.isNative(c) -> {
                    var j = i
                    while (j < norm.length && scheme.isNative(norm[j])) j++
                    val word = norm.substring(i, j)
                    out.append(inverted[word] ?: scheme.romanizeWord(word))
                    changed = true
                    i = j
                }
                // The danda is one code point for every Indic script, and it
                // sits outside the run but still wants a full stop.
                c == '।' -> { out.append('.'); changed = true; i++ }
                c == '॥' -> { out.append(".."); changed = true; i++ }
                else -> { out.append(c); i++ }
            }
        }
        return if (changed) out.toString() else null
    }

    private fun wordToNative(word: String): String {
        val norm = foldCase(word)
        spellings.lookup(norm).firstOrNull()?.let { return it }
        val literal = scheme.transliterate(norm)
        val top = phonetic.lookup(norm).firstOrNull() ?: return literal
        val literalFrequency = phonetic.frequencyOf(literal)
        val topFrequency = phonetic.frequencyOf(top)
        return if (literalFrequency > 0 && topFrequency < literalFrequency * SIBLING_CONFIDENCE) literal else top
    }

    /**
     * A capital that is only the start of a sentence, or a word shouted in
     * capitals, means the same word; mixed case inside a word is a deliberate
     * spelling (Avro's nodI, Hindi's roTi) and stays.
     */
    private fun foldCase(word: String): String = when {
        word.length > 1 && word[0].isUpperCase() && word.substring(1).all { it.isLowerCase() } -> word.lowercase()
        word.all { it.isUpperCase() } -> word.lowercase()
        else -> word
    }

    private fun isLatinLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /** Native word → the spelling to write it with. */
    private val inverted: Map<String, String> by lazy { buildInverted() }

    private fun buildInverted(): Map<String, String> {
        val best = HashMap<String, Pair<String, Int>>()
        val romanised = HashMap<String, String>()
        for (spelling in spellings.spellings) {
            if (spelling.length < MIN_READABLE_SPELLING) continue
            if (!spelling.all { it in 'a'..'z' }) continue
            if (TRIPLED.containsMatchIn(spelling)) continue
            for (form in spellings.lookup(spelling)) {
                if (form.any { it.isWhitespace() }) continue
                val key = scheme.normalize(form)
                val roman = romanised.getOrPut(key) { scheme.romanizeWord(key) }
                val distance = EditOps.distance(spelling, roman)
                val current = best[key]
                val better = current == null ||
                    spelling.length > current.first.length ||
                    (spelling.length == current.first.length && distance < current.second)
                if (better) best[key] = spelling to distance
            }
        }
        return best.mapValues { it.value.first }
    }

    private companion object {
        /** Same rule and constant as SuggestionEngine's phonetic strip. */
        const val SIBLING_CONFIDENCE = 2.0
        /** Shorter than this is chat shorthand (tmr, amk), not a spelling anyone reads. */
        const val MIN_READABLE_SPELLING = 4
        /** An elongation (valoooo) is not a spelling either. */
        val TRIPLED = Regex("""(.)\1\1""")
    }
}
