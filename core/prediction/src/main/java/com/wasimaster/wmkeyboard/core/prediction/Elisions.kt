package com.wasimaster.wmkeyboard.core.prediction

/**
 * Elision: a short word losing its vowel against the vowel that follows, and
 * the apostrophe that marks the loss. French does it constantly — "ce est" is
 * *c'est*, "je ai" is *j'ai*, "le ami" is *l'ami*, "que il" is *qu'il* — and a
 * French typist skips the apostrophe as often as a Polish one skips an accent
 * (#215).
 *
 * The word lists cannot answer this on their own. The French list holds
 * `c'est` 218 times and `cest` 1,383 times, because the corpus behind it was
 * tokenised at the apostrophe: *c'est* became `c` and `est`, and only the
 * misspelling survived as one token. So the apostrophe-less spelling is a
 * *known word* to every store, the elided one is nearly unknown, and the
 * ordinary route — the list carries both spellings, the commoner wins — is
 * closed. What the list does hold is the two halves, and that is what this
 * reads: a fused spelling is a prefix that elides plus a word that starts with
 * a vowel.
 *
 * Which prefix takes which word is grammar, and this is where a plain
 * "prefix + known word" rule falls over: `jet` is `j` + `et`, `don` is `d` +
 * `on`, `sun` is `s` + `un`, and none of *j'et*, *d'on*, *s'un* is French.
 * The trouble is all in the short function words — *et*, *on*, *un*, *il*,
 * *est* — each of which follows some prefixes and not others, so those are
 * listed per prefix. A longer content word is open to the prefixes that take
 * nouns and verbs alike (*l'*, *d'*, *qu'*) and, for the pronoun prefixes
 * that only ever precede a verb (*j'*, *n'*, *s'*, *m'*, *t'*), is asked to
 * end the way a French verb does, so that *sami* — a name — is not read as
 * *s'ami* while *jarrive* still becomes *j'arrive*.
 *
 * This decides only whether a spelling *could* be an elision. Whether it *is*
 * one — whether the typed word is unknown, or known only as a rare stand-in
 * for the two words it fuses — is the engine's call, made against the lists
 * the same way an accentless stand-in is judged.
 */
object Elisions {

    /** The rules for [languageId], or null for a language that does not elide. */
    fun rulesFor(languageId: String): Rules? =
        when (languageId.substringBefore('-').substringBefore('_')) {
            "fr" -> FRENCH
            else -> null
        }

    /** One reading of a fused spelling: the prefix and the word after it. */
    data class Split(val prefix: String, val rest: String) {
        /** The spelling with its apostrophe, given the word [rest] resolved to. */
        fun spell(word: String): String = prefix + APOSTROPHE + word
    }

    class Rules internal constructor(
        /** Every prefix that elides, as typed without its lost vowel. */
        private val prefixes: List<String>,
        /**
         * The short words that only some prefixes take, each with the prefixes
         * that do, keyed by the word with its accents off — the typist leaves
         * those off too, and `cetait` has to find *était*. Anything not listed
         * here is a content word, judged by [open] and [verbsOnly] instead.
         */
        private val functionWords: Map<String, Set<String>>,
        /**
         * Prefixes no word begins with on its own: `jusqua`, `quelquun` and
         * `aujourdhui` are never words, however often a list has seen them
         * typed, so the lists are not asked whether the fused spelling is one.
         */
        private val alwaysElide: Set<String>,
        /** Prefixes that take any content word: an article before a noun. */
        private val open: Set<String>,
        /** Prefixes that take a content word only when it looks like a verb. */
        private val verbsOnly: Set<String>,
        /** How a verb form ends, for [verbsOnly]. */
        private val verbEndings: List<String>,
        /** What a content word may start with to elide the prefix before it. */
        private val vowels: Set<Char>,
        /**
         * Function words whose accent the typist also left off, by the word as
         * typed and then the prefix: `jusqua` is *jusqu'à*, and the lists must
         * not be asked, since they would answer with the commoner bare *a*.
         */
        private val respelled: Map<String, Map<String, String>>,
    ) {

        /**
         * Whether [letters] — a composing buffer, lowercase or not — is a
         * prefix that elides, so an apostrophe typed after it ends the prefix
         * rather than joining the word ("l'" then "alp…", not "l'alp…").
         */
        fun isPrefix(letters: CharSequence): Boolean {
            if (letters.isEmpty()) return false
            for (prefix in prefixes) {
                if (prefix.length == letters.length && prefix.contentEquals(letters, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /**
         * Whether [text] ends in an elided prefix and its apostrophe — "l'",
         * "qu'", "aujourd'" — with a word boundary in front of the prefix. A
         * buffer composed after that is a word of its own, to be completed,
         * corrected and learned as one, not the tail of a longer word.
         */
        fun endsWithElidedPrefix(text: CharSequence): Boolean {
            val end = text.length - 1
            if (end < 1 || (text[end] != APOSTROPHE && text[end] != CURLY_APOSTROPHE)) return false
            for (prefix in prefixes) {
                val start = end - prefix.length
                if (start < 0 || !prefix.contentEquals(text.subSequence(start, end), ignoreCase = true)) {
                    continue
                }
                return start == 0 || !Character.isLetter(text[start - 1])
            }
            return false
        }

        /**
         * The readings of [fused], a lowercase spelling with no apostrophe, that
         * the grammar admits. Empty for a word that fuses nothing.
         *
         * Every matching prefix is tried: `quil` is `qu` + `il`, and a spelling
         * that could split two ways is left to the lists to settle.
         */
        fun splits(fused: String): List<Split> {
            if (fused.length < 2 || fused.indexOf(APOSTROPHE) >= 0) return emptyList()
            var out: MutableList<Split>? = null
            for (prefix in prefixes) {
                if (fused.length <= prefix.length || !fused.startsWith(prefix)) continue
                val rest = fused.substring(prefix.length)
                if (!admits(prefix, rest)) continue
                (out ?: ArrayList<Split>(2).also { out = it }).add(Split(prefix, rest))
            }
            return out ?: emptyList()
        }

        /** The spelling [split]'s word takes after its prefix when the lists must not choose it. */
        fun respelled(split: Split): String? = respelled[bare(split.rest)]?.get(split.prefix)

        /** Whether a spelling fused onto [prefix] can only ever be an elision. */
        fun alwaysElides(prefix: String): Boolean = prefix in alwaysElide

        /** Whether the grammar lets [prefix] elide before [rest]. */
        fun admits(prefix: String, rest: String): Boolean {
            functionWords[bare(rest)]?.let { return prefix in it }
            if (rest.length < MIN_CONTENT_WORD || rest[0] !in vowels) return false
            return when (prefix) {
                in open -> true
                in verbsOnly -> verbEndings.any { rest.endsWith(it) }
                else -> false
            }
        }
    }

    /** The straight apostrophe, which is what the lists spell an elision with. */
    const val APOSTROPHE = '\''

    /** The typographic one, which a long press or another keyboard may have typed. */
    const val CURLY_APOSTROPHE = '\u2019'

    /** [word] with its accents off, for the table lookups; [word] itself when it has none. */
    private fun bare(word: String): String {
        for (c in word) {
            if (Accents.bare(c) != c) return String(CharArray(word.length) { Accents.bare(word[it]) })
        }
        return word
    }

    /**
     * A content word this short is a function word the tables did not list,
     * and guessing at it (`d` + `os`) is how nonsense gets in.
     */
    private const val MIN_CONTENT_WORD = 3

    private val FRENCH = Rules(
        // Longest first, so `jusqu` is read before its `j`.
        prefixes = listOf(
            "jusqu", "lorsqu", "puisqu", "quoiqu", "quelqu", "presqu", "aujourd",
            "qu", "c", "j", "l", "d", "m", "n", "s", "t",
        ),
        functionWords = mapOf(
            // Both *a* and *à*: which one is read is [respelled]'s say.
            "a" to setOf("l", "m", "n", "t", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu"),
            "as" to setOf("l", "m", "n", "t"),
            "ai" to setOf("j", "l", "n", "t"),
            "aie" to setOf("j", "l", "m", "n", "t"),
            "ait" to setOf("l", "m", "n", "t"),
            "es" to setOf("n", "t"),
            // Not t': *t'est* exists, but `test` is a word people type far
            // more often than they drop that apostrophe.
            "est" to setOf("c", "l", "m", "n", "s", "qu"),
            "etait" to setOf("c", "l", "m", "n", "s"),
            "etaient" to setOf("c", "l", "m", "n", "s"),
            "en" to setOf("c", "j", "l", "d", "m", "n", "s", "t", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu"),
            "y" to setOf("j", "l", "d", "m", "n", "s", "t", "qu"),
            "on" to setOf("l", "qu", "lorsqu", "puisqu", "quoiqu"),
            "ont" to setOf("l", "m", "n", "t"),
            "un" to setOf("l", "d", "qu", "quelqu", "lorsqu", "puisqu", "quoiqu"),
            "une" to setOf("l", "d", "qu", "quelqu", "lorsqu", "puisqu", "quoiqu"),
            "uns" to setOf("quelqu"),
            "unes" to setOf("quelqu"),
            "il" to setOf("s", "qu", "lorsqu", "puisqu", "quoiqu"),
            "ils" to setOf("s", "qu", "lorsqu", "puisqu", "quoiqu"),
            "elle" to setOf("d", "qu", "lorsqu", "puisqu", "quoiqu"),
            "elles" to setOf("d", "qu", "lorsqu", "puisqu", "quoiqu"),
            "eu" to setOf("l", "n"),
            "eux" to setOf("d", "qu"),
            // *où* only; the conjunction *ou* elides nothing, so [respelled] decides.
            "ou" to setOf("d", "jusqu"),
            "et" to emptySet(),
            "au" to setOf("jusqu"),
            "aux" to setOf("jusqu"),
            "ici" to setOf("d", "qu", "jusqu"),
            "avec" to setOf("qu"),
            "hui" to setOf("aujourd"),
            "ile" to setOf("l", "presqu"),
        ),
        alwaysElide = setOf("jusqu", "lorsqu", "puisqu", "quoiqu", "quelqu", "presqu", "aujourd"),
        open = setOf("l", "d", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu"),
        verbsOnly = setOf("j", "n", "s", "m", "t"),
        verbEndings = listOf(
            "e", "es", "ent", "ons", "ez", "ais", "ait", "aient", "é", "ée", "és", "ées",
            "er", "ir", "re", "is", "it", "d", "ds", "t", "ts",
            "ra", "ras", "rez", "rons", "ront", "rais", "rait", "raient",
        ),
        vowels = "aeiouàâäéèêëîïôöùûüœæh".toSet(),
        respelled = mapOf(
            "a" to mapOf("qu" to "à", "jusqu" to "à", "lorsqu" to "à", "puisqu" to "à", "quoiqu" to "à"),
            "ou" to mapOf("d" to "où", "jusqu" to "où"),
            "ile" to mapOf("l" to "île", "presqu" to "île"),
        ),
    )
}
