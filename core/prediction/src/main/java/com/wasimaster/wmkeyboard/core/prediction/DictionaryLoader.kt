package com.wasimaster.wmkeyboard.core.prediction

import java.io.InputStream

/**
 * Loads a word list into a [Trie].
 *
 * Two formats, told apart by the first line that is not blank or a comment.
 *
 * **This app's own:** one entry per line, `word<space>frequency`. Lines starting
 * with `#` and malformed lines are skipped, so lists can carry comments and
 * survive hand editing.
 *
 * **The AOSP `.combined` list**, which is what every wordlist published for
 * AOSP-derived keyboards is written in — HeliBoard's dictionary repository, the
 * lists FUTO links, and the sources the `.dict` compiler takes. It is a header
 * line followed by indented `word=` records:
 *
 * ```
 * dictionary=main:en,locale=en,description=English,date=1414726273,version=54
 *  word=the,f=222,flags=,originalFreq=222
 *   bigram=is,f=8
 * ```
 *
 * Read as this app's format it is not rejected — it is *accepted wrongly*. A
 * record line holds no space, so the whole of `word=the,f=222,flags=` was taken
 * as one word at frequency 1, and importing a combined list quietly filled the
 * user's dictionary with junk that then came back as suggestions. The sniff
 * below is what stops that.
 */
object DictionaryLoader {

    fun load(stream: InputStream): Trie {
        val trie = Trie()
        for ((word, frequency) in loadEntries(stream)) trie.insert(word, frequency)
        return trie
    }

    fun loadEntries(stream: InputStream): List<Pair<String, Int>> {
        val entries = ArrayList<Pair<String, Int>>()
        var format: Format? = null
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                if (format == null) format = formatOf(trimmed)
                when (format) {
                    Format.COMBINED -> combinedEntry(trimmed)?.let { entries.add(it) }
                    Format.PLAIN, null -> entries.add(plainEntry(trimmed))
                }
            }
        }
        return entries
    }

    /**
     * Which format a list is, decided on its first meaningful line.
     *
     * Decided once rather than per line: a combined list's own header is the
     * only thing that names the format, and a `word=…` line half way down a
     * plain list is a word someone really wrote.
     */
    private fun formatOf(firstLine: String): Format =
        if (firstLine.startsWith(COMBINED_HEADER)) Format.COMBINED else Format.PLAIN

    private enum class Format { PLAIN, COMBINED }

    private fun plainEntry(line: String): Pair<String, Int> {
        val separator = line.lastIndexOf(' ')
        if (separator <= 0) return line to 1
        val word = line.substring(0, separator).trim()
        val frequency = line.substring(separator + 1).toIntOrNull() ?: 1
        return word to frequency
    }

    /**
     * One `word=` record, or null for everything else in a combined file.
     *
     * The other record kinds — `bigram=`, `shortcut=` — describe a word that
     * has already been read, and neither is a word in its own right. A
     * shortcut in particular is an expansion (`pls` → `please`) whose target
     * would otherwise be inserted as a word nobody typed.
     */
    private fun combinedEntry(line: String): Pair<String, Int>? {
        if (!line.startsWith(WORD_FIELD)) return null
        var word: String? = null
        var frequency = DEFAULT_COMBINED_FREQUENCY
        for (field in line.split(',')) {
            val name = field.substringBefore('=', missingDelimiterValue = "")
            val value = field.substringAfter('=', missingDelimiterValue = "")
            when (name) {
                "word" -> word = value
                // `f` is 0..255 there, and `whitelist` where a shortcut stands
                // in for a number. A non-numeric value keeps the default rather
                // than dropping a real word over a field this app does not use.
                "f" -> frequency = value.toIntOrNull()?.let(::scaleAospFrequency) ?: frequency
                else -> Unit
            }
        }
        return word?.takeIf { it.isNotEmpty() }?.let { it to frequency }
    }

    /**
     * An AOSP frequency on this app's scale.
     *
     * Shared with [AospDictionary], which reads the compiled form of the same
     * lists: one scale, decided once.
     *
     * Over there a word is 0..255; here the bundled lists run to 10000. The
     * two meet in one list once a user imports one, so the ordering has to
     * survive the move — a straight copy would sink every imported word below
     * every bundled one.
     */
    internal fun scaleAospFrequency(f: Int): Int =
        (f.coerceIn(0, MAX_COMBINED_FREQUENCY) * MAX_LOCAL_FREQUENCY / MAX_COMBINED_FREQUENCY)
            .coerceAtLeast(1)

    private const val COMBINED_HEADER = "dictionary="
    private const val WORD_FIELD = "word="
    private const val MAX_COMBINED_FREQUENCY = 255
    private const val MAX_LOCAL_FREQUENCY = 10000

    /** What a record with no readable `f` is worth: present, but unranked. */
    private const val DEFAULT_COMBINED_FREQUENCY = 1
}
