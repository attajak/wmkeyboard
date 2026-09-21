package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.AospDictionary.Ngram
import com.wasimaster.wmkeyboard.core.prediction.AospDictionary.Result
import com.wasimaster.wmkeyboard.core.prediction.AospDictionary.Shortcut
import java.io.InputStream

/**
 * Reading the AOSP `.combined` text list whole: words, and the pairs and
 * shortcuts [DictionaryLoader] steps past.
 *
 * The source form of every compiled `.dict`, and what AOSP's `dicttool` prints
 * when it dumps one. A header line, then one record per line, each belonging
 * to the `word=` record above it:
 *
 * ```
 * dictionary=main:fr,locale=fr,description=Français,date=1414726264,version=54
 *  word=coeur,f=103,flags=,originalFreq=103
 *   shortcut=cœur,f=14
 *   bigram=de,f=160
 *  word=il,f=191
 *  ngram=est,f=180
 *   prev_word[0]=il
 * ```
 *
 * - `shortcut=` is what typing the word above offers; `f=14` or
 *   `f=whitelist` is how strongly.
 * - `bigram=` is a word that follows the one above, `f` on the 0..255 scale.
 * - `ngram=` is the newer spelling of the same thing, used by dumps of a
 *   version 4 dictionary. Its context is the `prev_word[i]` lines under it,
 *   `[0]` the nearest; one line is a pair and two a triple.
 * - `historicalInfo=timestamp:level:count` stands in for `f` in a user
 *   history dump, and is read as a count, the way [AospDictionaryV4] reads it.
 */
object AospCombined {

    /** Whether [firstLine] (the first line that is not blank or a comment) opens a combined list. */
    fun looksLikeCombined(firstLine: String): Boolean = firstLine.trim().startsWith(HEADER)

    /**
     * Everything in a combined list, or [Result.NotADictionary] when the first
     * meaningful line is not its header.
     */
    fun read(stream: InputStream): Result {
        val reader = Reader()
        var header: Map<String, String>? = null
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                if (header == null) {
                    if (!looksLikeCombined(trimmed)) return Result.NotADictionary
                    header = fields(trimmed)
                    continue
                }
                reader.line(trimmed)
            }
        }
        reader.finish()
        val attributes = header ?: return Result.NotADictionary
        return Result.Contents(reader.words, reader.ngrams, reader.shortcuts, attributes)
    }

    /** One record's `key=value` fields. A value may itself contain `=`. */
    private fun fields(line: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (field in line.split(',')) {
            val key = field.substringBefore('=', missingDelimiterValue = "")
            if (key.isNotEmpty()) out[key] = field.substringAfter('=')
        }
        return out
    }

    private class Reader {
        val words = ArrayList<Pair<String, Int>>()
        val ngrams = ArrayList<Ngram>()
        val shortcuts = ArrayList<Shortcut>()

        /** The word the records below belong to, even when it is not a word itself. */
        private var current: String? = null

        /** An `ngram=` record still collecting its `prev_word` lines. */
        private var ngram: Map<String, String>? = null
        private val context = ArrayList<Pair<Int, String>>()

        fun line(line: String) {
            val record = fields(line)
            when {
                WORD in record -> {
                    finishNgram()
                    word(record)
                }
                NGRAM in record -> {
                    finishNgram()
                    ngram = record
                }
                line.startsWith(PREV_WORD) -> prevWord(line)
                BIGRAM in record -> bigram(record)
                SHORTCUT in record -> shortcut(record)
                else -> Unit
            }
        }

        fun finish() = finishNgram()

        private fun word(record: Map<String, String>) {
            val word = record.getValue(WORD).takeIf { it.isNotEmpty() }
            current = word
            if (word == null) return
            // The sentence-start marker and a not-a-word entry both exist only
            // to hang other records off.
            if (record[NOT_A_WORD].toBoolean() || record[BEGINNING_OF_SENTENCE].toBoolean()) return
            words.add(word to frequencyOf(record))
        }

        private fun bigram(record: Map<String, String>) {
            val first = current ?: return
            val second = record.getValue(BIGRAM).takeIf { it.isNotEmpty() } ?: return
            ngrams.add(Ngram(listOf(first), second, pairCountOf(record)))
        }

        private fun shortcut(record: Map<String, String>) {
            val trigger = current ?: return
            val target = record.getValue(SHORTCUT).takeIf { it.isNotEmpty() && it != trigger } ?: return
            val strength = record[FREQUENCY]
            val whitelist = strength == WHITELIST || strength?.toIntOrNull() == WHITELIST_STRENGTH
            shortcuts.add(Shortcut(trigger, target, whitelist))
        }

        /** `prev_word[0]=il`, and optionally `,beginning_of_sentence=true`. */
        private fun prevWord(line: String) {
            if (ngram == null) return
            val index = line.substringAfter('[').substringBefore(']').toIntOrNull() ?: return
            val record = fields(line)
            if (record[BEGINNING_OF_SENTENCE].toBoolean()) {
                // A pair that starts a sentence has nothing typed before it.
                context.add(index to "")
                return
            }
            val word = record.entries.firstOrNull { it.key.startsWith(PREV_WORD) }?.value ?: return
            context.add(index to word)
        }

        private fun finishNgram() {
            val record = ngram ?: return
            ngram = null
            val target = record[NGRAM]?.takeIf { it.isNotEmpty() }
            // [0] is the nearest word, so the typed order is the reverse.
            val words = context.sortedByDescending { it.first }.map { it.second }
            context.clear()
            if (target == null || words.isEmpty() || words.size > MAX_CONTEXT) return
            if (words.any { it.isEmpty() }) return
            ngrams.add(Ngram(words, target, pairCountOf(record)))
        }

        private fun frequencyOf(record: Map<String, String>): Int {
            record[FREQUENCY]?.toIntOrNull()?.takeIf { it >= 0 }?.let {
                return DictionaryLoader.scaleAospFrequency(it)
            }
            val count = typedCount(record) ?: return DEFAULT_FREQUENCY
            return AospScores.historicalFrequency(count, total = 0)
        }

        private fun pairCountOf(record: Map<String, String>): Int {
            typedCount(record)?.let { return AospScores.historicalPairCount(it) }
            val probability = record[FREQUENCY]?.toIntOrNull() ?: return 1
            return AospScores.pairCount(probability)
        }

        private fun typedCount(record: Map<String, String>): Int? =
            record[HISTORICAL_INFO]?.split(':')?.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
    }

    private const val HEADER = "dictionary="
    private const val WORD = "word"
    private const val BIGRAM = "bigram"
    private const val NGRAM = "ngram"
    private const val SHORTCUT = "shortcut"
    private const val PREV_WORD = "prev_word"
    private const val FREQUENCY = "f"
    private const val HISTORICAL_INFO = "historicalInfo"
    private const val NOT_A_WORD = "not_a_word"
    private const val BEGINNING_OF_SENTENCE = "beginning_of_sentence"
    private const val WHITELIST = "whitelist"
    private const val WHITELIST_STRENGTH = 15
    private const val MAX_CONTEXT = 2

    /** What a record with no readable strength is worth: present, but unranked. */
    private const val DEFAULT_FREQUENCY = 1
}
