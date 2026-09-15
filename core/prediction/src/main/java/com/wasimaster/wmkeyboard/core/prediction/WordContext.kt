package com.wasimaster.wmkeyboard.core.prediction

/**
 * Derives bigram context from the text before the caret — extracted from the
 * IME so it is pure and JVM-testable, and so sentence boundaries become part
 * of the contract instead of an accident: "Hello. " used to hand "hello" to
 * the bigram store, polluting it with pairs that cross a full stop.
 */
object WordContext {

    /** U+0001, built rather than written literally so no editor or tool can
     * corrupt an invisible control character in this file. */
    private val SENTINEL_CHAR: Char = 1.toChar()

    /**
     * Pseudo-word marking "the caret is at a sentence start". Learned as a
     * bigram *previous* (so sentence openers become predictable) but never as
     * a word or a follower; U+0001 can never be typed, so it collides with
     * nothing and any store it leaks into simply never matches.
     */
    val SENTENCE_START: String = SENTINEL_CHAR + "s"

    /**
     * True for a character that belongs to the word around it.
     *
     * `Char.isLetter()` on its own answers this only for the scripts that
     * spell a word out of letters alone. Bengali হয়েছে ends in U+09C7 VOWEL
     * SIGN E, a combining mark, so a letters-only boundary reads that word as
     * having ended three characters earlier: `trim { !it.isLetter() }` hands
     * back হয়েছ and `takeLastWhile { it.isLetter() }` hands back a bare ছ. The
     * same holds for Devanagari matras, Tamil and Thai vowel signs, Arabic
     * harakat and Hebrew niqqud. A combining mark never separates two words —
     * it is part of what the letter before it spells.
     */
    fun isWordChar(c: Char): Boolean = c.isLetter() || when (c.category) {
        CharCategory.NON_SPACING_MARK,
        CharCategory.COMBINING_SPACING_MARK,
        CharCategory.ENCLOSING_MARK,
        -> true
        else -> false
    }

    /**
     * Whether [word] is a word the keyboard may learn *on its own* (#185).
     *
     * Letters and digits, with at least one letter ("mp3" and "b2b" are words;
     * "2024" is a number), starting and ending on one of those; inside, single
     * joiners are allowed too — an apostrophe ("don't"), a hyphen
     * ("well-known"), or the zero-width joiners Indic scripts spell with —
     * never two in a row and never anything else. What it refuses is the
     * class of thing that used to reach the personal dictionary through a
     * side door: `manager"`, `man"ager`, a token with a symbol glued on.
     *
     * Only the automatic paths ask: a word the user adds by hand is theirs to
     * spell however they like. Length is the caller's business — every store
     * has its own floor and ceiling already.
     */
    fun isLearnableWord(word: String): Boolean {
        if (word.isEmpty()) return false
        fun letterOrDigit(c: Char) = isWordChar(c) || c.isDigit()
        if (!letterOrDigit(word[0]) || !letterOrDigit(word[word.length - 1])) return false
        var afterJoiner = false
        var letters = 0
        for (c in word) {
            if (isWordChar(c)) letters++
            afterJoiner = when {
                letterOrDigit(c) -> false
                c in WORD_JOINERS && !afterJoiner -> true
                else -> return false
            }
        }
        return letters > 0
    }

    /** What may sit between two letters of one word: the apostrophes, the hyphen, ZWNJ and ZWJ. */
    private const val WORD_JOINERS = "'\u2019-\u200C\u200D"

    /**
     * The completed word ending [text], for next-word context:
     *  - null while still inside a word, and null for null text, which is an
     *    editor saying it cannot answer rather than saying there is nothing;
     *  - [SENTENCE_START] for **empty** text — the caret sits at the very top
     *    of the field, which is the most sentence-start there is. It used to
     *    answer null here, and null means "no context at all", so the strip on
     *    an empty field could not offer a first word however many sentence
     *    openers had been learned (#119);
     *  - [SENTENCE_START] when a sentence ender lies between the last word
     *    and the caret ("Hello. " — the next word starts a sentence and
     *    "hello" is not its context). Known limitation, accepted: "Dr. "
     *    reads as a sentence start too;
     *  - otherwise the last word, in the one spelling every store keys on
     *    ("Hello, " -> "hello"). See [WordKey]: the text here is read back out
     *    of the field, so it can hold anything any keyboard or paste put there.
     */
    fun completedWordBefore(text: CharSequence?, enders: CharArray): String? {
        if (text == null) return null
        if (text.isEmpty()) return SENTENCE_START
        if (isWordChar(text.last()) || text.last().isDigit()) return null
        // The run of separators between the last word and the caret.
        var i = text.length - 1
        while (i >= 0 && !isWordChar(text[i])) {
            if (text[i] in enders) return SENTENCE_START
            i--
        }
        val word = text.toString()
            .trim { !isWordChar(it) }
            .takeLastWhile { isWordChar(it) }
        return WordKey.of(word).ifEmpty { null }
    }

    /** True for the sentinel (or anything in its reserved control plane). */
    fun isSentinel(word: String?): Boolean = word != null && word.startsWith(SENTINEL_CHAR)

    /**
     * The last two completed words before the caret, most recent first:
     * `(prev1, prev2)`. prev2 is null when unknown or when any boundary
     * (sentence ender, or prev1 itself being the sentinel) intervenes —
     * trigram context silently degrades to bigram rather than guessing.
     */
    fun lastTwoWords(text: CharSequence?, enders: CharArray): Pair<String?, String?> {
        val prev1 = completedWordBefore(text, enders)
        if (prev1 == null || isSentinel(prev1)) return prev1 to null
        // Strip the trailing separators and prev1's own letters, then ask the
        // same question of what remains.
        val s = text.toString()
        var end = s.length
        while (end > 0 && !isWordChar(s[end - 1])) end--
        while (end > 0 && isWordChar(s[end - 1])) end--
        val prev2 = completedWordBefore(s.substring(0, end), enders)
        return prev1 to prev2?.takeUnless { isSentinel(it) }
    }

    /**
     * The last three completed words before the caret, most recent first:
     * `(prev1, prev2, prev3)`. Same contract as [lastTwoWords] one word
     * further back: prev3 is null when unknown or when any boundary lies
     * between it and the caret, so the 2-skip bigrams (#195) reads
     * nothing across a sentence end.
     */
    fun lastThreeWords(text: CharSequence?, enders: CharArray): Triple<String?, String?, String?> {
        val (prev1, prev2) = lastTwoWords(text, enders)
        if (prev2 == null) return Triple(prev1, null, null)
        val s = text.toString()
        var end = s.length
        repeat(2) {
            while (end > 0 && !isWordChar(s[end - 1])) end--
            while (end > 0 && isWordChar(s[end - 1])) end--
        }
        val prev3 = completedWordBefore(s.substring(0, end), enders)
        return Triple(prev1, prev2, prev3?.takeUnless { isSentinel(it) })
    }
}
