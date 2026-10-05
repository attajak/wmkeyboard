package com.wasimaster.wmkeyboard.core.prediction

/**
 * Romanian's comma-below letters, for word lists that spell them with a
 * cedilla.
 *
 * Romanian writes s-comma and t-comma: U+0219 and U+021B, with U+0218 and
 * U+021A above them. The letters that look like them, U+015F and U+0163, are
 * s-cedilla and t-cedilla, and they belong to Turkish and its neighbours.
 * Romanian only ever got them because the eight-bit code pages of the
 * nineties had no comma-below letter and shipped the Turkish ones in their
 * place. Twenty years of text was written that way, so any list counted from
 * the web or from subtitles inherits it: the data repo's counted Romanian
 * list spelt "and" with a cedilla 4.3 million times and with a comma a
 * twentieth as often, and ranked the cedilla "you live" ten places above the
 * comma one.
 *
 * That ranking is what reached the user. [Accents] folds both letters to `s`
 * and `t`, so either spelling matches what was typed and it is the *list's*
 * spelling that gets committed - which is how autocorrect came to write a
 * cedilla for a typist whose keyboard carries only the comma letter, every
 * Romanian layout here having had the right one on it all along.
 *
 * So the cedilla comes out on the way in, wherever a list is read, rather
 * than at every lookup. The two spellings then land on one key and their
 * counts merge - [PackedTrie.of] keeps the larger - which leaves the word
 * ranked where the two halves of the evidence put it together.
 *
 * Romanian only. Turkish, Azerbaijani, Gagauz, Zazaki and Kurmanji all write
 * a real cedilla, and rewriting theirs would be this same mistake pointed the
 * other way.
 */
object RomanianSpelling {

    /** The one language whose lists are respelt. */
    const val LANGUAGE_ID = "ro"

    private const val S_CEDILLA = '\u015F'
    private const val S_CEDILLA_CAPITAL = '\u015E'
    private const val T_CEDILLA = '\u0163'
    private const val T_CEDILLA_CAPITAL = '\u0162'
    private const val S_COMMA = '\u0219'
    private const val S_COMMA_CAPITAL = '\u0218'
    private const val T_COMMA = '\u021B'
    private const val T_COMMA_CAPITAL = '\u021A'

    /**
     * Words that only a cedilla-spelt list holds, for the one-time repair of
     * a list already on disk. Four rather than one because the smallest size
     * a list downloads at is fifty thousand words, and one of these has to be
     * inside that: "and", "you are", "the country", "the face".
     */
    val PROBES: List<String> = listOf(
        "\u015Fi",
        "e\u015Fti",
        "\u0163ara",
        "fa\u0163a",
    )

    /** Whether [langId] is the language this applies to. */
    fun appliesTo(langId: String): Boolean = langId == LANGUAGE_ID

    /** Whether [c] is one of the four letters a Romanian list gets wrong. */
    fun isCedilla(c: Char): Boolean =
        c == S_CEDILLA || c == T_CEDILLA || c == S_CEDILLA_CAPITAL || c == T_CEDILLA_CAPITAL

    /** [c] with its cedilla read as the comma below it should have been. */
    fun canonical(c: Char): Char = when (c) {
        S_CEDILLA -> S_COMMA
        S_CEDILLA_CAPITAL -> S_COMMA_CAPITAL
        T_CEDILLA -> T_COMMA
        T_CEDILLA_CAPITAL -> T_COMMA_CAPITAL
        else -> c
    }

    /**
     * [word] with every cedilla letter replaced, or [word] itself when it has
     * none.
     *
     * Returning the same instance for a word that needs nothing is the point:
     * this runs per word while a million-word list streams past, and all but
     * a twentieth of them are already right.
     */
    fun canonical(word: String): String {
        var first = -1
        for (i in word.indices) {
            if (isCedilla(word[i])) {
                first = i
                break
            }
        }
        if (first < 0) return word
        val out = StringBuilder(word.length).append(word, 0, first)
        for (i in first until word.length) out.append(canonical(word[i]))
        return out.toString()
    }
}
