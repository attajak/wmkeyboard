package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Khipro run backwards: the keys that spell a Bangla word on the Khipro layout,
 * for decoding a swipe over its grid (#541).
 *
 * Nothing here is a table someone wrote. The pieces are read off Khipro itself
 * ([Khipro.convert]) once: every key sequence of up to [MAX_KEYS] letters is
 * converted on its own, after a consonant and after a vowel, and what it adds
 * is recorded against the shortest sequence that adds it. A word is then cut
 * into those pieces left to right, keeping a few cheapest cuts, and a cut
 * counts only if Khipro turns its keys back into exactly the word. So a word
 * that comes back is right by construction, and any word of any list can be
 * spelled, not only the ones somebody listed.
 *
 * Letters and the slicer `/`, which a swipe over the Khipro grid passes
 * through (see [KEYS]). A word that needs any other key (the separator `;`,
 * the blinder) has no spelling here and is typed.
 */
object KhiproRomanizer {

    /** Longest key sequence read off Khipro for a single piece. */
    private const val MAX_KEYS = 3

    /** Cheapest cuts kept per position; the rest are never converted back. */
    private const val BEAM = 12

    /** Whole-word cuts converted back, shortest first, before a word is given up. */
    private const val FINAL_TRIES = 32

    /**
     * Key sequences kept per piece, shortest first. One is not enough: after
     * ক, `n` adds a separate ন, but after শ it joins into শ্ন, so শনি needs the
     * longer `on` the table would otherwise have thrown away.
     */
    private const val ALTERNATIVES = 4

    private enum class Context { START, CONSONANT, VOWEL, REPH }

    private class Tables(
        /** Bangla → keys, typed at the start of a word. */
        val start: Map<String, List<String>>,
        /** What keys add after a consonant (a vowel sign, a joined or separate consonant). */
        val afterConsonant: Map<String, List<String>>,
        /** What keys add after a vowel. */
        val afterVowel: Map<String, List<String>>,
        /** What keys add after a reph (`rr`, র্), which joins onto the consonant that follows. */
        val afterReph: Map<String, List<String>>,
        /** Longest Bangla piece in any table, in UTF-16 units. */
        val longest: Int,
    )

    @Volatile private var tables: Tables? = null

    private fun tables(): Tables = tables ?: build().also { tables = it }

    /** Reads the pieces off Khipro's touchscreen rules. About 55,000 conversions, once. */
    private fun build(): Tables {
        val start = HashMap<String, MutableList<String>>()
        val afterConsonant = HashMap<String, MutableList<String>>()
        val afterVowel = HashMap<String, MutableList<String>>()
        val afterReph = HashMap<String, MutableList<String>>()
        val consonantCarrier = Khipro.convert(CONSONANT_KEY)
        val vowelCarrier = Khipro.convert(VOWEL_KEY)
        // `rr` shows as রর until a consonant follows and turns it into a reph,
        // so a reph is recorded by what it becomes, and what follows one is
        // read after a vowel and a reph that has already joined.
        val rephCarrier = vowelCarrier + REPH
        // Sequences come shortest first, so the first few recorded are the shortest.
        fun record(map: HashMap<String, MutableList<String>>, added: String, keys: String) {
            val piece = if (keys.endsWith(REPH_KEYS) && added.endsWith(PENDING_REPH)) {
                added.dropLast(PENDING_REPH.length) + REPH
            } else {
                added
            }
            if (piece.isEmpty()) return
            val held = map.getOrPut(piece) { ArrayList(ALTERNATIVES) }
            if (held.size < ALTERNATIVES) held += keys
        }
        for (keys in sequences()) {
            record(start, Khipro.convert(keys), keys)
            Khipro.convert(CONSONANT_KEY + keys).let { out ->
                if (out.startsWith(consonantCarrier)) record(afterConsonant, out.substring(consonantCarrier.length), keys)
            }
            Khipro.convert(VOWEL_KEY + keys).let { out ->
                if (out.startsWith(vowelCarrier)) record(afterVowel, out.substring(vowelCarrier.length), keys)
            }
            Khipro.convert(VOWEL_KEY + REPH_KEYS + keys).let { out ->
                if (out.startsWith(rephCarrier)) record(afterReph, out.substring(rephCarrier.length), keys)
            }
        }
        val longest = maxOf(
            start.keys.maxOfOrNull { it.length } ?: 1,
            afterConsonant.keys.maxOfOrNull { it.length } ?: 1,
            afterVowel.keys.maxOfOrNull { it.length } ?: 1,
            afterReph.keys.maxOfOrNull { it.length } ?: 1,
        )
        return Tables(start, afterConsonant, afterVowel, afterReph, longest)
    }

    /**
     * Every sequence of 1 to [MAX_KEYS] keys: the letters-only ones first,
     * shortest first, then the ones with the slicer. A piece keeps its first
     * [ALTERNATIVES], so one a letter spelling reaches never fills its list
     * with slicer detours, and one only the slicer reaches still gets it.
     */
    private fun sequences(): Sequence<String> = sequence {
        for (alphabet in listOf(KEYS.filter { it != '/' }, KEYS)) {
            var layer = listOf("")
            repeat(MAX_KEYS) {
                layer = layer.flatMap { prefix -> alphabet.map { prefix + it } }
                yieldAll(if (alphabet == KEYS) layer.filter { '/' in it } else layer)
            }
        }
    }

    /**
     * The keys a swipe over the Khipro grid can pass through: the letters and
     * the slicer, which is how চন্দ্রবিন্দু and খণ্ড-ত are typed (`cand/` is চাঁদ).
     */
    const val KEYS = "abcdefghijklmnopqrstuvwxyz/"

    /** Builds the pieces now, off the thread that will first need them. */
    fun warm() {
        tables()
    }

    private class Cut(val keys: String, val context: Context)

    /**
     * The fewest letters that Khipro turns into [word], or null when no cut of
     * it converts back exactly (a word that needs a non-letter key, mostly).
     */
    fun romanize(word: String): String? = cut(precomposed(word))?.keys

    private fun cut(word: String): Cut? {
        if (word.isEmpty()) return null
        val t = tables()
        // cuts[i]: the cheapest few ways of typing word[0, i).
        val cuts = arrayOfNulls<MutableList<Cut>>(word.length + 1)
        cuts[0] = mutableListOf(Cut("", Context.START))
        for (i in 0 until word.length) {
            val here = cuts[i] ?: continue
            val kept = prune(here, word.substring(0, i))
            here.clear()
            here.addAll(kept)
            for (cut in here) {
                val maps = when (cut.context) {
                    Context.START -> listOf(t.start)
                    Context.CONSONANT -> listOf(t.afterConsonant)
                    Context.VOWEL -> listOf(t.afterVowel, t.start)
                    Context.REPH -> listOf(t.afterReph)
                }
                for (length in minOf(t.longest, word.length - i) downTo 1) {
                    val piece = word.substring(i, i + length)
                    for (map in maps) {
                        val options = map[piece] ?: continue
                        val next = cuts[i + length] ?: ArrayList<Cut>().also { cuts[i + length] = it }
                        for (keys in options) {
                            next += Cut(cut.keys + keys, contextAfter(piece))
                        }
                    }
                }
            }
        }
        val done = cuts[word.length] ?: return null
        for (cut in done.distinctBy { it.keys }.sortedBy { it.keys.length }.take(FINAL_TRIES)) {
            if (Khipro.convert(cut.keys) == word) return cut
        }
        return null
    }

    /**
     * The [BEAM] cuts of a prefix worth extending, shortest first. Where there
     * are more, the ones Khipro already turns into exactly [typed] go first:
     * shortness alone keeps joined spellings (`durbl`) that read as some other
     * word and pushes the right one out.
     */
    private fun prune(cuts: List<Cut>, typed: String): List<Cut> {
        val distinct = cuts.distinctBy { it.keys }.sortedBy { it.keys.length }
        if (distinct.size <= BEAM) return distinct
        val exact = ArrayList<Cut>(BEAM)
        val rest = ArrayList<Cut>()
        for (cut in distinct) {
            if (exact.size < BEAM && Khipro.convert(cut.keys) == typed) exact += cut else rest += cut
        }
        return (exact + rest).take(BEAM)
    }

    /**
     * Every way of spelling [word] worth decoding a swipe against: the fewest
     * letters, and the same with the inherent `o` written out between
     * consonants where Khipro still reads the word (`ekhn` and `ekhon` for
     * এখন), since people swipe the vowel they say. Empty when there is none.
     */
    fun spellings(text: String): List<String> {
        val word = precomposed(text)
        val best = cut(word) ?: return emptyList()
        val shortest = best.keys
        // Between two consonant keys an `o` is the inherent vowel; kept wherever
        // Khipro still reads the same word, which it does not where the two
        // would have joined or where they are one letter (`kh`).
        var spelled = shortest
        for (at in (1 until shortest.length).reversed()) {
            if (shortest[at - 1] in VOWEL_KEYS || shortest[at] in VOWEL_KEYS) continue
            if (shortest[at - 1] == '/' || shortest[at] == '/') continue
            val tried = spelled.substring(0, at) + "o" + spelled.substring(at)
            if (Khipro.convert(tried) == word) spelled = tried
        }
        return if (spelled != shortest) listOf(shortest, spelled) else listOf(shortest)
    }

    /**
     * Khipro writes য় ড় ঢ় as one character each; a list kept in NFC writes
     * them as the letter and a dot. Compared in Khipro's form.
     */
    private fun precomposed(word: String): String {
        if (word.indexOf('\u09BC') < 0) return word
        return word
            .replace("\u09A1\u09BC", "\u09DC")
            .replace("\u09A2\u09BC", "\u09DD")
            .replace("\u09AF\u09BC", "\u09DF")
    }

    private fun contextAfter(piece: String): Context {
        if (piece.endsWith("\u09B0\u09CD")) return Context.REPH
        val last = piece.last()
        return if (last in 'ক'..'হ' || last in 'ড়'..'য়' || last == '়' || last == '্') {
            Context.CONSONANT
        } else {
            Context.VOWEL
        }
    }

    /** Keys that end a consonant cluster on their own; no `o` goes before or after them. */
    private const val VOWEL_KEYS = "aeiouwy"

    private const val CONSONANT_KEY = "k"
    private const val VOWEL_KEY = "a"

    /** The keys of a reph, and the reph, and what the keys show before anything follows them. */
    private const val REPH_KEYS = "rr"
    private const val REPH = "\u09B0\u09CD"
    private const val PENDING_REPH = "\u09B0\u09B0"
}
