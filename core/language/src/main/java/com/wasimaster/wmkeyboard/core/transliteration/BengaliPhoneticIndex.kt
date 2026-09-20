package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup from romanized Bengali to dictionary words.
 *
 * Banglish spelling is loose — আছি gets typed as "asi", "achi" or "achhi".
 * Instead of trying to predict every spelling, both the romanized input and
 * each Bengali dictionary word are folded to a lenient canonical key where
 * confusable sounds collapse (স/শ/ষ/ছ/চ → s, ত/ট → t, ব/ভ → b, aspiration
 * dropped, inherent vowels dropped). Words sharing a key are phonetic
 * siblings; the highest-frequency sibling is offered as the primary
 * suggestion, which is what makes "ami valo asi" become আমি ভালো আছি.
 *
 * Frequency alone is too blunt to order those siblings, because the key
 * throws away two things the typist actually wrote down:
 *
 *  1. **Aspiration.** থাকে and তাকে share the key "tk", and তাকে is the more
 *     common word — so "thake" used to come back as তাকে, silently replacing
 *     the word the h was there to ask for. The [aspiration] mask records, per
 *     key position, whether that consonant was written aspirated, and siblings
 *     that contradict what was typed are ranked down. It is a ranking signal
 *     rather than a filter, and deliberately lopsided: typing the h and not
 *     getting it ([ASPIRATION_MISMATCH_TYPED]) is a worse error than leaving it
 *     out and getting it anyway ([ASPIRATION_MISMATCH_WORD]), since casual
 *     romanization drops an h far more readily than it invents one. Only the
 *     ক/খ, গ/ঘ, ত/থ, ট/ঠ, দ/ধ and ড/ঢ pairs count: "ch" for চ and "ph" for ফ
 *     are ordinary English-influenced spellings, not claims about aspiration.
 *  2. **A final ও.** [dropTrailingO] folds ভালো and ভাল together so both are
 *     reachable from "valo", but it also folds কথা together with কথাও — and ও
 *     is a whole word's worth of meaning ("also"). [endsWithO] keeps the two
 *     apart again, so "kotha" answers কথা and only "kothao" answers কথাও.
 */
class BengaliPhoneticIndex(entries: List<Pair<String, Int>>) : PhoneticIndex {

    private val byKey = HashMap<String, MutableList<Entry>>()
    private val freqByWord = HashMap<String, Int>()

    /** One indexed word, with the fold's discarded detail kept alongside. */
    private class Entry(
        val word: String,
        val frequency: Int,
        val aspiration: String,
        val endsWithO: Boolean,
    )

    init {
        for ((word, frequency) in entries) {
            val folded = foldBengaliFull(word)
            if (folded.key.isEmpty()) continue
            byKey.getOrPut(folded.key) { mutableListOf() }
                .add(Entry(word, frequency, folded.aspiration, endsWithO(word)))
            freqByWord.merge(word, frequency, ::maxOf)
        }
        byKey.values.forEach { list -> list.sortByDescending { it.frequency } }
    }

    override val isEmpty: Boolean get() = byKey.isEmpty()

    /** Dictionary words phonetically matching the romanized [input], best first. */
    override fun lookup(input: String): List<String> {
        val folded = foldRomanFull(input)
        val bucket = byKey[folded.key] ?: return emptyList()
        // One sibling is the overwhelmingly common case; skip the comparator.
        if (bucket.size == 1) return listOf(bucket[0].word)
        val typedFinalO = input.isNotEmpty() && input.last().lowercaseChar() in "ow"
        return bucket
            .sortedByDescending { it.frequency.toLong() * SCALE / handicap(it, folded.aspiration, typedFinalO) }
            .map { it.word }
    }

    /**
     * What to divide [entry]'s frequency by for disagreeing with what was
     * actually typed. 1 means it agrees on every count and its frequency
     * stands as it is.
     */
    private fun handicap(entry: Entry, typed: String, typedFinalO: Boolean): Long {
        var divisor = 1L
        if (entry.endsWithO != typedFinalO) divisor *= FINAL_O_MISMATCH
        val shared = minOf(typed.length, entry.aspiration.length)
        for (i in 0 until shared) {
            val typedAspirated = typed[i] == ASPIRATED
            val wordAspirated = entry.aspiration[i] == ASPIRATED
            if (typedAspirated == wordAspirated) continue
            divisor *= if (typedAspirated) ASPIRATION_MISMATCH_TYPED else ASPIRATION_MISMATCH_WORD
        }
        return divisor
    }

    /** Dictionary frequency of a Bengali [word], 0 when unknown. */
    override fun frequencyOf(word: String): Int = freqByWord[word] ?: 0

    companion object {

        /** Marks a key position whose consonant was written aspirated. */
        private const val ASPIRATED = 'h'
        private const val PLAIN = '.'

        /**
         * Disagreements divide a sibling's frequency rather than outranking
         * it outright, so the penalty is a handicap and not a veto. Typing
         * "tik" for ঠিক leaves an h out, which is ordinary — ঠিক should still
         * win on being the far commoner word. Typing "thake" and getting তাকে
         * is a different matter: that h was a deliberate request, so the
         * handicap has to be big enough to overturn তাকে being ~3x commoner.
         */
        private const val ASPIRATION_MISMATCH_TYPED = 20

        /** Left the h out and the word has one — ordinary casual spelling. */
        private const val ASPIRATION_MISMATCH_WORD = 2

        private const val FINAL_O_MISMATCH = 4

        /** Keeps the divided frequencies in integer arithmetic. */
        private const val SCALE = 1024L

        /**
         * Bengali consonants whose aspiration a typist genuinely signals with
         * an h. ছ and ফ are absent on purpose: "ch" is how চ gets written by
         * anyone used to English, and "ph" is the ordinary spelling of ফ, so
         * neither h says anything about aspiration. ঝ and ভ likewise, since
         * "jh"/"bh" compete with the plain "j"/"v" spellings of the same
         * sounds.
         */
        private val aspiratedLetters = setOf('খ', 'ঘ', 'ঠ', 'ঢ', 'থ', 'ধ')

        /** Roman consonants where a following h is a real aspiration mark. */
        private const val ASPIRABLE_ROMAN = "kgtd"

        private val consonantClasses = mapOf(
            'ক' to 'k', 'খ' to 'k', 'গ' to 'g', 'ঘ' to 'g', 'ঙ' to 'q',
            'চ' to 's', 'ছ' to 's', 'জ' to 'j', 'ঝ' to 'j', 'ঞ' to 'q',
            'ট' to 't', 'ঠ' to 't', 'ড' to 'd', 'ঢ' to 'd', 'ণ' to 'n',
            'ত' to 't', 'থ' to 't', 'দ' to 'd', 'ধ' to 'd', 'ন' to 'n',
            'প' to 'p', 'ফ' to 'p', 'ব' to 'b', 'ভ' to 'b', 'ম' to 'm',
            'য' to 'j', 'র' to 'r', 'ল' to 'l', 'শ' to 's', 'ষ' to 's',
            'স' to 's', 'হ' to 'h',
            // খণ্ড-ত is a ত, and nobody spells it with a key of its own when
            // typing fast: "hotat" has to be able to reach হঠাৎ.
            'ৎ' to 't',
            // Nukta letters as precomposed code points (ড় ঢ় য়); decomposed
            // input is normalized before lookup.
            'ড়' to 'r', 'ঢ়' to 'r', 'য়' to 'y',
            'ং' to 'q', 'ঃ' to 'h',
        )

        private val vowelClasses = mapOf(
            'অ' to 'o', 'আ' to 'a', 'া' to 'a',
            'ই' to 'i', 'ঈ' to 'i', 'ি' to 'i', 'ী' to 'i',
            'উ' to 'u', 'ঊ' to 'u', 'ু' to 'u', 'ূ' to 'u',
            'এ' to 'e', 'ে' to 'e', 'ঐ' to 'i', 'ৈ' to 'i',
            'ও' to 'o', 'ো' to 'o', 'ঔ' to 'u', 'ৌ' to 'u',
            'ঋ' to 'r', 'ৃ' to 'r',
        )

        /**
         * A canonical key plus, character for character, which of its
         * positions were written aspirated. The two strings are built in
         * lockstep and edited in lockstep, so position `i` of [aspiration]
         * always describes position `i` of [key] — that alignment is what
         * lets the roman and Bengali sides be compared at all.
         */
        class Folded(val key: String, val aspiration: String)

        /** Folds a Bengali word to its canonical phonetic key. */
        fun foldBengali(word: String): String = foldBengaliFull(word).key

        /** Whether a Bengali word ends in the standalone ও or its ো sign. */
        fun endsWithO(word: String): Boolean =
            word.isNotEmpty() && (word.last() == 'ও' || word.last() == 'ো')

        /** Folds a Bengali word, keeping its aspiration pattern. */
        fun foldBengaliFull(word: String): Folded {
            // Normalize decomposed nukta pairs to precomposed code points,
            // and the corrupt U+0985 U+09BE pair (seen in scraped word lists)
            // to U+0986: the pair folds to "oa" and would hijack keys like
            // "oasi"/"wasi".
            val normalized = word
                .replace("\u09A1\u09BC", "\u09DC")
                .replace("\u09A2\u09BC", "\u09DD")
                .replace("\u09AF\u09BC", "\u09DF")
                .replace("\u0985\u09BE", "\u0986")
            val out = StringBuilder()
            val marks = StringBuilder()
            for (ch in normalized) {
                val c = consonantClasses[ch]
                val v = vowelClasses[ch]
                when {
                    c != null -> {
                        out.append(c)
                        marks.append(if (ch in aspiratedLetters) ASPIRATED else PLAIN)
                    }
                    v != null -> {
                        out.append(v)
                        marks.append(PLAIN)
                    }
                    // hasant, chandrabindu and anything unmapped fold away
                }
            }
            return dropTrailingO(Folded(out.toString(), marks.toString()))
        }

        /** Folds romanized Bengali typing to the same canonical key. */
        fun foldRoman(input: String): String = foldRomanFull(input).key

        /** Folds romanized typing, keeping the aspiration the typist wrote. */
        fun foldRomanFull(input: String): Folded {
            val lower = input.lowercase()
            val out = StringBuilder()
            val marks = StringBuilder()
            var i = 0
            while (i < lower.length) {
                val ch = lower[i]
                val next = lower.getOrNull(i + 1)
                when {
                    // Aspiration folds: kh→k, gh→g, ch→s, th→t, dh→d, ph→p, bh→b, sh→s
                    next == 'h' && ch in "kgctdpbs" -> {
                        out.append(if (ch == 'c') 's' else ch)
                        marks.append(if (ch in ASPIRABLE_ROMAN) ASPIRATED else PLAIN)
                        i += 2
                        // chh → s as well
                        if (ch == 'c' && lower.getOrNull(i) == 'h') i++
                        continue
                    }
                    ch == 'c' -> out.append('s').also { marks.append(PLAIN) }
                    ch == 'v' -> out.append('b').also { marks.append(PLAIN) }
                    ch == 'w' -> out.append('o').also { marks.append(PLAIN) }
                    ch == 'z' -> out.append('j').also { marks.append(PLAIN) }
                    ch == 'f' -> out.append('p').also { marks.append(PLAIN) }
                    ch == 'x' -> {
                        out.append('k').append('s')
                        marks.append(PLAIN).append(PLAIN)
                    }
                    ch in "aeiou" -> {
                        // Doubled vowels lengthen: ee→i (ঈ), oo→u (উ)
                        if (next == ch) {
                            i++
                            out.append(when (ch) { 'e' -> 'i'; 'o' -> 'u'; else -> ch })
                        } else {
                            out.append(ch)
                        }
                        marks.append(PLAIN)
                    }
                    ch.isLetter() -> out.append(ch).also { marks.append(PLAIN) }
                    // punctuation and digits fold away
                }
                i++
            }
            return dropTrailingO(collapseInherent(Folded(out.toString(), marks.toString())))
        }

        /**
         * Romanized inherent vowels: "kori" carries its vowels explicitly, but
         * the Bengali fold of করি is "kri" (inherent o is invisible). Dropping
         * medial "o" between consonants from the roman side aligns the two.
         */
        private fun collapseInherent(folded: Folded): Folded {
            val key = folded.key
            val out = StringBuilder()
            val marks = StringBuilder()
            for ((index, ch) in key.withIndex()) {
                val prevIsConsonant = index > 0 && key[index - 1] !in "aeiou"
                val nextIsConsonant = index < key.length - 1 && key[index + 1] !in "aeiou"
                if (ch == 'o' && prevIsConsonant && nextIsConsonant) continue
                out.append(ch)
                marks.append(folded.aspiration[index])
            }
            return Folded(out.toString(), marks.toString())
        }

        /** Word-final inherent/o-kar ambiguity: ভালো vs ভাল both end "balo". */
        private fun dropTrailingO(folded: Folded): Folded =
            if (folded.key.length > 1 && folded.key.endsWith('o')) {
                Folded(folded.key.dropLast(1), folded.aspiration.dropLast(1))
            } else {
                folded
            }
    }
}
