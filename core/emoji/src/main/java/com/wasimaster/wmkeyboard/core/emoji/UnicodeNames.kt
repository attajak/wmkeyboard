package com.wasimaster.wmkeyboard.core.emoji

import java.util.Locale

/**
 * Every named character of the Basic Multilingual Plane, searchable by the
 * words of its Unicode name (#385): "latin small letter a with ogonek",
 * "greek capital letter omega", "em dash", "copyright sign".
 *
 * Built once, on first use and off the main thread, from [Character.getName]:
 * about fifty thousand names, a few megabytes, which is why it is not built
 * until a search asks for it. Emoji are deliberately not what this is for —
 * the catalogue knows them by better words than their names — but nothing
 * excludes them, so a query that hits one finds the same thing twice, and the
 * caller drops the duplicate.
 */
object UnicodeNames {

    /** The category every character found here reports, so a caller can tell them from the catalogue's emoji. */
    const val CATEGORY = "unicode"

    private const val FIRST = 0x00A1
    private const val LAST = 0xFFFD

    private class Named(val text: String, val name: String, val words: List<String>)

    @Volatile
    private var index: List<Named>? = null

    private fun entries(): List<Named> = index ?: synchronized(this) {
        index ?: buildIndex().also { index = it }
    }

    private fun buildIndex(): List<Named> {
        val out = ArrayList<Named>(60_000)
        for (cp in FIRST..LAST) {
            when (Character.getType(cp).toByte()) {
                Character.UNASSIGNED, Character.SURROGATE, Character.PRIVATE_USE,
                Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                Character.PARAGRAPH_SEPARATOR,
                -> continue
            }
            val name = Character.getName(cp) ?: continue
            val lower = name.lowercase(Locale.ROOT)
            out += Named(String(Character.toChars(cp)), lower, lower.split(' ', '-').filter { it.isNotEmpty() })
        }
        return out
    }

    /** Builds the index ahead of the first search, off whatever thread calls it. */
    fun warm() {
        entries()
    }

    /**
     * Characters whose name holds every word of [query], best first, at most
     * [limit]. A query word matches a name word whole or as its start, whole
     * counting for more; ties go to the shorter name, so "dash" puts EM DASH
     * ahead of WAVE DASH ahead of the longer compounds.
     */
    fun search(query: String, limit: Int = 20): List<EmojiEntry> {
        val tokens = query.lowercase(Locale.ROOT).split(Regex("[\\s_]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.all { it.length < 2 }) return emptyList()
        val scored = ArrayList<Pair<Named, Int>>()
        for (entry in entries()) {
            var score = 0
            for (token in tokens) {
                score += when {
                    token in entry.words -> WHOLE_WORD
                    entry.words.any { it.startsWith(token) } -> WORD_START
                    else -> {
                        score = -1
                        break
                    }
                }
            }
            if (score > 0) scored += entry to score
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<Named, Int>> { it.second }
                    .thenBy { it.first.name.length }
                    .thenBy { it.first.name },
            )
            .take(limit)
            .map { (entry, _) ->
                EmojiEntry(
                    emoji = entry.text,
                    category = CATEGORY,
                    keywords = entry.words,
                    name = entry.name.replaceFirstChar { it.titlecase(Locale.ROOT) },
                )
            }
    }

    private const val WHOLE_WORD = 10
    private const val WORD_START = 4
}
