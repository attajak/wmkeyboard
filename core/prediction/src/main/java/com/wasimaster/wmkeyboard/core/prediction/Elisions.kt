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
            "it" -> ITALIAN
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

    /** The prefixes that elide before anything: Italian's articles and prepositions. */
    private val ITALIAN_OPEN =
        setOf("l", "d", "un", "dell", "nell", "dall", "sull", "all", "quell")

    /** The prefixes that elide in a fixed handful of phrases; see [ITALIAN]. */
    private val ADJECTIVE_PREFIXES =
        setOf("quest", "grand", "sant", "bell", "buon", "mezz")

    /** Those phrases: `quest'anno`, `mezz'ora`, `buon'anima`, `grand'uomo`. */
    private val ADJECTIVE_COLLOCATIONS = listOf(
        "anno", "anni", "ora", "uomo", "uomini", "estate", "inverno", "idea",
        "aria", "opera", "epoca", "occasione", "immagine", "amore", "anima",
        "isola", "età",
    )

    /**
     * The prefixes each of those takes: the adjectives it is listed for, and
     * the open ones it would have had anyway.
     *
     * Both halves are needed. A word in the function table is *only* matched
     * against that table — that is what keeps `jet` from being *j'et* — so
     * listing `idea` for `quest'` alone would have taken `un'idea` and
     * `l'idea` away from prefixes that never needed listing.
     *
     * `d'` before *uomo* is the one subtraction: `d'uomo` is a real phrase,
     * but *duomo* is a cathedral and 1,300 times rarer than *uomo*, which is
     * exactly the shape the ratio reads as a stand-in.
     */
    private fun adjectivePrefixesFor(word: String): Set<String> = when (word) {
        "uomo", "uomini" -> ADJECTIVE_PREFIXES + ITALIAN_OPEN - "d"
        else -> ADJECTIVE_PREFIXES + ITALIAN_OPEN
    }

    /**
     * Italian elides as constantly as French — *lo albero* is `l'albero`,
     * *una amica* is `un'amica`, *ci è* is `c'è` — and the reporter of #240
     * types it the same way a French typist types *cest*: without the
     * apostrophe.
     *
     * The list behind it is tokenised differently, and it matters. The French
     * corpus cut at the apostrophe and kept both halves, so `l'` never
     * survived as a token and only the fused misspelling did. The Italian one
     * cut *after* it: `l'` is the 16th commonest token in the language,
     * `un'` and `dell'` and `all'` are all in the first 200, and the word
     * after the prefix is an ordinary token of its own. So the fused spelling
     * is usually a word nothing has ever seen — `lalbero` is in no list at
     * all — and the reading is applied outright rather than shadow-priced.
     *
     * Where the fused spelling *is* a word, the ratio decides, exactly as in
     * French. Measured over the real 184k list: `cera` (wax, 1,793) against
     * *era* (657,610) clears 200 and is corrected to `c'era`; `allora`
     * (394,873) against *ora* (559,590) does not and merely offers
     * `all'ora`; `dove` (344,643) keeps itself and offers `dov'è`; `lira`
     * keeps itself over `l'ira`.
     *
     * Two departures from the French table, both forced by the language:
     *
     *  - [verbsOnly] is empty. French can ask whether the rest looks like a
     *    verb because French verbs end distinctively; Italian words nearly
     *    all end in a vowel, so the same test admits everything. The pronoun
     *    prefixes (`c'`, `m'`, `t'`) are therefore given a function-word
     *    table and nothing else — `c'è` and `m'ha`, never `c'` plus a noun.
     *  - [open] holds the articles and prepositions only. The adjective
     *    prefixes (`quest'`, `mezz'`, `buon'`) elide in a short list of
     *    collocations instead, because *questione* and *tuttora* are real
     *    words that an open prefix rule reads straight through.
     *
     * Swept over the whole list, 123 of its 184,631 words come back with a
     * spelling to apply and about 1,350 with one to offer. Fifteen of the
     * eighteen applied spellings whose fused form is at all common are the
     * repair the typist wanted (`lho`, `cè`, `dacqua`, `mezzora`); the three
     * that are not — `lore`, `lecco`, `lallà` — are all `l'` before a word
     * that happens to follow, which is the rule that also produces
     * `l'albero`. That is the same trade French makes for `cest`.
     */
    private val ITALIAN = Rules(
        // Longest first, so `dell` is read before its `d`.
        prefixes = listOf(
            "quell", "quest", "nient", "grand", "dell", "nell", "dall", "sull",
            "anch", "tutt", "sant", "bell", "buon", "mezz", "all", "dov", "com",
            "un", "c", "d", "l", "m", "t",
        ),
        functionWords = buildMap {
            // *è*, which [respelled] spells back: asked plainly, the lists
            // answer "e" — the conjunction, and the commonest token in
            // Italian — for both spellings.
            put("e", setOf("c", "dov", "com"))
            put("era", setOf("c", "dov", "com"))
            put("erano", setOf("c", "dov", "com"))
            put("eravamo", setOf("c"))
            put("eravate", setOf("c"))
            put("ero", setOf("c"))
            put("eri", setOf("c"))
            // *avere* after a pronoun: `l'ho`, `m'ha`, `t'hanno`. None of the
            // fused spellings is an Italian word, so all of them are safe.
            put("ho", setOf("l", "c", "m", "t"))
            put("ha", setOf("l", "c", "m", "t"))
            put("hanno", setOf("l", "c", "m", "t"))
            // Not t': `thai` is a word people type and *t'hai* is not
            // standard anyway.
            put("hai", setOf("l", "c", "m"))
            put("abbiamo", setOf("c"))
            put("avete", setOf("c"))
            put("io", setOf("anch"))
            for (w in listOf("esso", "essa", "essi", "esse")) put(w, setOf("anch"))
            // The adjective prefixes are not [open]: they elide in a short
            // list of collocations and nothing else. Left open, `quest` read
            // *questore* — a police chief — as `quest'ore`.
            for (w in ADJECTIVE_COLLOCATIONS) put(w, adjectivePrefixesFor(w))
            for (w in listOf("altro", "altra", "altri", "altre")) {
                put(w, setOf("tutt", "nient", "quest", "un", "l", "d", "dell", "quell", "all"))
            }
            put("uno", setOf("tutt", "l"))
            put("una", setOf("tutt", "l"))
        },
        // Nothing: every Italian prefix above can also open an ordinary word,
        // so the fused spelling's own count always gets a say. French needs
        // this for `aujourdhui` and `jusqua`, which are never words; the
        // Italian fused spellings that matter — `lalbero`, `unamica` — are
        // simply absent from the lists, which the ratio already handles.
        alwaysElide = emptySet(),
        // The articles and prepositions, which elide before anything.
        open = ITALIAN_OPEN,
        // Italian words nearly all end in a vowel, so "does the rest look
        // like a verb" cannot be asked the way French asks it: the test would
        // admit everything. The pronoun prefixes take a listed function word
        // and nothing else.
        verbsOnly = emptySet(),
        verbEndings = emptyList(),
        vowels = "aeiouàèéìíòóùúh".toSet(),
        respelled = mapOf(
            "e" to mapOf("c" to "è", "dov" to "è", "com" to "è"),
        ),
    )
}
