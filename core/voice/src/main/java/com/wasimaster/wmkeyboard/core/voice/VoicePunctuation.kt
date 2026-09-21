package com.wasimaster.wmkeyboard.core.voice

/**
 * Spoken-punctuation post-processing for dictated text: "comma" becomes
 * ",", "দাঁড়ি" becomes "।", "new line" becomes a newline. Applied to
 * final results only (partials stay raw — they are transient anyway).
 *
 * The trade-off is deliberate and Gboard-shaped: a sentence that really
 * contains the word "period" gets a "." instead. The whole pass sits
 * behind a per-tool setting for exactly that reason.
 *
 * The marks beyond the sentence ones keep that trade-off small by their
 * names: a symbol whose plain name is an everyday word is only reachable by
 * the name nobody says by accident ("percent sign", "plus sign", "greater
 * than sign"), so "fifty percent" and "greater than ten" stay words.
 */
object VoicePunctuation {

    /** Which side of a mark the neighbouring words sit tight against. */
    private enum class Join {
        /** Closes up to the word before it: `word, next`. */
        AFTER,

        /** Closes up to the word after it: `word (next`. */
        BEFORE,

        /** Closes up on both sides: `well-known`, and a line break. */
        BOTH,

        /** Stands apart from both: `this & that`. */
        APART,
    }

    private class Rule(phrase: String, val symbol: String, val join: Join) {
        val words: List<String> = phrase.split(' ')
    }

    private fun rules(vararg entries: Triple<String, String, Join>): List<Rule> =
        entries.map { (phrase, symbol, join) -> Rule(phrase, symbol, join) }
            // Longest phrases first, so "question mark" wins over any one-word rule.
            .sortedByDescending { it.words.size }

    private infix fun Pair<String, String>.joined(join: Join) = Triple(first, second, join)

    private val ENGLISH = rules(
        "new paragraph" to "\n\n" joined Join.BOTH,
        "new line" to "\n" joined Join.BOTH,
        "question mark" to "?" joined Join.AFTER,
        "exclamation mark" to "!" joined Join.AFTER,
        "exclamation point" to "!" joined Join.AFTER,
        "full stop" to "." joined Join.AFTER,
        "period" to "." joined Join.AFTER,
        "comma" to "," joined Join.AFTER,
        "colon" to ":" joined Join.AFTER,
        "semicolon" to ";" joined Join.AFTER,
        "ellipsis" to "…" joined Join.AFTER,
        "dot dot dot" to "…" joined Join.AFTER,
        "open quote" to "\"" joined Join.BEFORE,
        "close quote" to "\"" joined Join.AFTER,
        "open parenthesis" to "(" joined Join.BEFORE,
        "close parenthesis" to ")" joined Join.AFTER,
        "open bracket" to "[" joined Join.BEFORE,
        "close bracket" to "]" joined Join.AFTER,
        "open brace" to "{" joined Join.BEFORE,
        "close brace" to "}" joined Join.AFTER,
        "apostrophe" to "'" joined Join.BOTH,
        "hyphen" to "-" joined Join.BOTH,
        "underscore" to "_" joined Join.BOTH,
        "dash" to "-" joined Join.APART,
        "en dash" to "–" joined Join.BOTH,
        "em dash" to "—" joined Join.BOTH,
        "forward slash" to "/" joined Join.BOTH,
        "slash" to "/" joined Join.BOTH,
        "backslash" to "\\" joined Join.BOTH,
        "back slash" to "\\" joined Join.BOTH,
        "at sign" to "@" joined Join.BOTH,
        "hashtag" to "#" joined Join.BEFORE,
        "hash sign" to "#" joined Join.BEFORE,
        "pound sign" to "#" joined Join.BEFORE,
        "dollar sign" to "\$" joined Join.BEFORE,
        "percent sign" to "%" joined Join.AFTER,
        "degree sign" to "°" joined Join.AFTER,
        "ampersand" to "&" joined Join.APART,
        "asterisk" to "*" joined Join.AFTER,
        "plus sign" to "+" joined Join.APART,
        "equals sign" to "=" joined Join.APART,
        "greater than sign" to ">" joined Join.APART,
        "less than sign" to "<" joined Join.APART,
        "vertical bar" to "|" joined Join.APART,
        "tilde" to "~" joined Join.BEFORE,
        "caret" to "^" joined Join.BOTH,
        "copyright sign" to "©" joined Join.AFTER,
        "registered sign" to "®" joined Join.AFTER,
        "trademark sign" to "™" joined Join.AFTER,
    )

    private val BENGALI = rules(
        "নতুন প্যারা" to "\n\n" joined Join.BOTH,
        "নতুন লাইন" to "\n" joined Join.BOTH,
        "প্রশ্নবোধক চিহ্ন" to "?" joined Join.AFTER,
        "প্রশ্নবোধক" to "?" joined Join.AFTER,
        "বিস্ময়সূচক চিহ্ন" to "!" joined Join.AFTER,
        "বিস্ময়বোধক" to "!" joined Join.AFTER,
        "দাঁড়ি" to "।" joined Join.AFTER,
        "কমা" to "," joined Join.AFTER,
        "কোলন" to ":" joined Join.AFTER,
        "সেমিকোলন" to ";" joined Join.AFTER,
    )

    /** The spoken names this pass knows for [languageTag]; none for a language it has no table for. */
    private fun tableFor(languageTag: String): List<Rule>? = when {
        languageTag.startsWith("en") -> ENGLISH
        languageTag.startsWith("bn") -> BENGALI
        else -> null
    }

    fun apply(text: String, languageTag: String): String {
        val table = tableFor(languageTag) ?: return text
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return text
        val sb = StringBuilder()
        // Set by a mark that closes up to whatever follows it (and at the very
        // start — the caller handles the leading space).
        var tight = true
        var i = 0
        outer@ while (i < words.size) {
            for (rule in table) {
                val phrase = rule.words
                if (i + phrase.size <= words.size &&
                    phrase.indices.all { words[i + it].equals(phrase[it], ignoreCase = true) }
                ) {
                    val spaceBefore = rule.join == Join.BEFORE || rule.join == Join.APART
                    if (spaceBefore && !tight) sb.append(' ')
                    sb.append(rule.symbol)
                    tight = rule.join == Join.BEFORE || rule.join == Join.BOTH
                    i += phrase.size
                    continue@outer
                }
            }
            if (!tight) sb.append(' ')
            sb.append(words[i])
            tight = false
            i++
        }
        return sb.toString()
    }
}
