package com.wasimaster.wmkeyboard.core.input.composer

import java.text.Normalizer

/**
 * Whether a string is shaped like a Vietnamese syllable.
 *
 * Vietnamese spelling is unusually closed: a syllable is one onset from a list
 * of 28, one vowel nucleus from a list of about 50, one coda from a list of 8,
 * and a tone the coda may veto. Nothing else is a syllable, which makes the
 * question decidable from a few tables instead of a dictionary — worth having
 * for a language whose transliterator will happily produce `tést` out of an
 * English word and hand it to the personal dictionary as a new spelling.
 *
 * Deliberately a shape test and not a word test: `blup` fails because no
 * Vietnamese syllable starts `bl`, while `mèo` passes whether or not any
 * wordlist has heard of it. Used to decide what may be *learned*, never what
 * may be typed — a syllable that fails here still commits exactly as typed.
 */
object VietnameseOrthography {

    /** Phụ âm đầu. The empty string is one: a syllable may start with a vowel. */
    private val ONSETS = hashSetOf(
        "", "b", "c", "ch", "d", "đ", "g", "gh", "gi", "h", "k", "kh",
        "l", "m", "n", "ng", "ngh", "nh", "p", "ph", "qu", "r", "s",
        "t", "th", "tr", "v", "x",
    )

    /** Phụ âm cuối. Also possibly empty — an open syllable ends on its vowel. */
    private val CODAS = hashSetOf("", "c", "ch", "m", "n", "ng", "nh", "p", "t")

    /**
     * The stop codas. A syllable that ends in one can only carry sắc or nặng:
     * the other three tones need a voiced ending to be pronounced on, so `bàc`
     * and `bảt` are not misspellings but non-words.
     */
    private val STOP_CODAS = hashSetOf("c", "ch", "p", "t")

    /** Every vowel nucleus, written without its tone. */
    private val NUCLEI = hashSetOf(
        "a", "ă", "â", "e", "ê", "i", "o", "ô", "ơ", "u", "ư", "y",
        "ai", "ao", "au", "ay", "ây", "âu",
        "eo", "êu",
        "ia", "iê", "iu", "iêu",
        "oa", "oai", "oay", "oă", "oe", "oeo", "oi", "oo", "ôi", "ơi",
        "ua", "uâ", "uay", "uây", "uô", "uôi", "uơ", "uê", "ui", "uy", "uya",
        "uyê", "uyu",
        "ưa", "ươ", "ưi", "ưu", "ươi", "ươu",
        "ya", "yê", "yêu",
    )

    /**
     * The onsets the open nucleus `uơ` may follow.
     *
     * It is the most restricted nucleus in the language, and the one place the
     * flat lists above are not enough: the onset and the nucleus do not choose
     * each other freely. `huơ`, `quơ` and `thuở` are written with a plain `u`,
     * while `người`, `đường` and `hương` are `ươ` — `nguơ` is nothing at all.
     * The empty string is in the list because `uơ` needs no onset to be
     * well-shaped, which is what makes a bare `uow` the pair's open reading.
     */
    private val UO_ONSETS = hashSetOf("", "c", "h", "k", "kh", "qu", "th")

    /** Multi-letter onsets, longest first so `ngh` is not read as `ng`. */
    private val LONG_ONSETS = arrayOf("ngh", "ng", "nh", "ch", "gh", "kh", "ph", "th", "tr", "qu", "gi")

    /** Multi-letter codas, longest first. */
    private val LONG_CODAS = arrayOf("ng", "nh", "ch")

    /** Single letters that can stand alone as an onset. */
    private const val SINGLE_ONSETS = "bcdđghklmnpqrstvx"

    /** Single letters that can stand alone as a coda. */
    private const val SINGLE_CODAS = "cmnpt"

    /** The vowels, marked and unmarked, that decide where an onset ends. */
    private const val VOWELS = "aeiouyăâêôơư"

    private const val ACUTE = '́'
    private const val GRAVE = '̀'
    private const val HOOK = '̉'
    private const val TILDE = '̃'
    private const val DOT = '̣'

    /** Whether [word] could be one Vietnamese syllable. */
    fun isSyllable(word: String): Boolean {
        val clean = word.trim().lowercase()
        if (clean.isEmpty()) return false

        // Split the tone off its letter: the tables below are written without
        // tones, and a tone is a property of the whole syllable anyway.
        var tone: Char? = null
        val bare = StringBuilder(clean.length)
        for (ch in Normalizer.normalize(clean, Normalizer.Form.NFD)) {
            when (ch) {
                ACUTE, GRAVE, HOOK, TILDE, DOT -> {
                    // Two tones on one syllable is not a syllable.
                    if (tone != null) return false
                    tone = ch
                }
                else -> bare.append(ch)
            }
        }
        val base = Normalizer.normalize(bare, Normalizer.Form.NFC)
        if (base.isEmpty()) return false

        val onset = onsetOf(base)
        val rest = base.substring(onset.length)
        if (rest.isEmpty()) return false
        val coda = codaOf(rest)
        val nucleus = rest.dropLast(coda.length)

        if (nucleus.isEmpty()) return false
        if (onset !in ONSETS) return false
        if (coda !in CODAS) return false
        if (nucleus !in NUCLEI) return false
        // `uơ` is the one nucleus an onset may refuse, and the one that takes no
        // coda at all: `huơ` and `quơ` are syllables, `huơng` and `nguơ` are not
        // — the first is `hương` and the second is not a word.
        if (nucleus == "uơ" && (onset !in UO_ONSETS || coda.isNotEmpty())) return false
        // Sắc and nặng are the only tones a stop coda can carry.
        if (coda in STOP_CODAS && tone != null && tone != ACUTE && tone != DOT) return false
        return true
    }

    /**
     * Whether [word] could still become a syllable if the user kept typing.
     *
     * The difference from [isSyllable] is that every part is allowed to be
     * unfinished, because a Telex buffer is read mid-word: at the `s` of
     * `nuocsw` the word in hand is `nuoc`, whose nucleus `uo` is not a nucleus
     * yet — the `w` two keys later is what makes it `ươ` — and a rule that
     * wanted the finished spelling would refuse the tone and break the word.
     * `nuoc` is a prefix of `nước`; `has` is not a prefix of anything, because
     * no Vietnamese syllable ends `s`.
     *
     * This is what decides whether a tone key may mark the word, so it has to
     * answer for a buffer that is not a word yet. It is not a word test either
     * way: `bl` passes the onset rule the way `blup` fails it, and neither is
     * claimed to be a word.
     */
    fun isSyllablePrefix(word: String): Boolean {
        var tone: Char? = null
        val bare = StringBuilder(word.length)
        for (ch in Normalizer.normalize(word.trim().lowercase(), Normalizer.Form.NFD)) {
            when (ch) {
                ACUTE, GRAVE, HOOK, TILDE, DOT -> {
                    if (tone != null) return false
                    tone = ch
                }
                else -> bare.append(ch)
            }
        }
        val base = Normalizer.normalize(bare, Normalizer.Form.NFC)
        if (base.isEmpty()) return true
        // Every split into onset / nucleus / coda, because which letters are the
        // onset is not known until the whole thing is: `n` opens `nghiêng` as
        // part of `ngh`, and `gi` is an onset in `gió` but a nucleus in `gì`.
        for (onsetLength in 0..minOf(3, base.length)) {
            val onset = base.substring(0, onsetLength)
            if (!couldBeOnset(onset)) continue
            val rest = base.substring(onsetLength)
            if (rest.isEmpty()) return true
            for (codaLength in 0..minOf(3, rest.length)) {
                val nucleus = rest.substring(0, rest.length - codaLength)
                val coda = rest.substring(rest.length - codaLength)
                if (nucleus.isEmpty()) continue
                if (!couldBeNucleus(nucleus)) continue
                if (coda.isEmpty()) return true
                if (!couldBeCoda(coda)) continue
                // Sắc and nặng are the only tones a stop coda can carry, and a
                // coda that can still grow into one counts against the tone now:
                // `c` may yet be `ch`, and both are stops, so `nưỡc` is refused
                // rather than accepted as a prefix of something.
                if (tone != null && tone != ACUTE && tone != DOT &&
                    CODAS.any { it.startsWith(coda) && it in STOP_CODAS }
                ) {
                    continue
                }
                return true
            }
        }
        return false
    }

    /**
     * Whether [word] carries a mark Vietnamese has and Latin text does not.
     *
     * What tells a Vietnamese word that has gone wrong from a Latin one that
     * was never Vietnamese: `rhees` has no such mark and is left exactly as
     * typed, while `rhês` does and is not a word — so the `ê` is the keyboard's
     * doing and is the keyboard's to take back.
     */
    fun hasVietnameseMark(word: String): Boolean {
        for (ch in Normalizer.normalize(word, Normalizer.Form.NFD)) {
            if (ch == ACUTE || ch == GRAVE || ch == HOOK || ch == TILDE || ch == DOT) return true
        }
        return word.any { it in "ăâêôơưđĂÂÊÔƠƯĐ" }
    }

    /** [part] is an onset, or the beginning of one. */
    private fun couldBeOnset(part: String): Boolean =
        part.isEmpty() || ONSETS.any { reaches(part, it) }

    /** [part] is a nucleus, or the beginning of one. */
    private fun couldBeNucleus(part: String): Boolean =
        NUCLEI.any { reaches(part, it) }

    /** [part] is a coda, or the beginning of one. */
    private fun couldBeCoda(part: String): Boolean =
        CODAS.any { reaches(part, it) }

    /**
     * Whether [part] could be the beginning of [whole], counting a mark the user
     * has not typed yet.
     *
     * Plain `startsWith` is not enough, and `nuoc` is why: its nucleus is `uo`,
     * which is not a string prefix of `uô` or `uơ` — the mark *replaces* the
     * letter rather than following it. So each typed letter is allowed to match
     * the marked letter it will become, which is how a Telex buffer reads while
     * it is still being typed: the `w` that makes `ươ` comes after the `c`.
     */
    private fun reaches(part: String, whole: String): Boolean {
        if (part.length > whole.length) return false
        for (i in part.indices) {
            if (part[i] != whole[i] && part[i] != bare(whole[i])) return false
        }
        return true
    }

    /** [c] without the mark a Telex key would have put on it. */
    private fun bare(c: Char): Char = when (c) {
        'ă', 'â' -> 'a'
        'ê' -> 'e'
        'ô', 'ơ' -> 'o'
        'ư' -> 'u'
        'đ' -> 'd'
        else -> c
    }

    private fun onsetOf(base: String): String {
        for (candidate in LONG_ONSETS) {
            if (!base.startsWith(candidate)) continue
            // `gi` is the one ambiguous onset: before another vowel it is the
            // onset (gió, giúp), but in `gì` and `gìn` the i *is* the nucleus
            // and the onset is a bare g.
            if (candidate == "gi" && (base.length == 2 || base[2] !in VOWELS)) return "g"
            return candidate
        }
        return if (base[0] in SINGLE_ONSETS) base.substring(0, 1) else ""
    }

    private fun codaOf(rest: String): String {
        for (candidate in LONG_CODAS) {
            if (rest.endsWith(candidate)) return candidate
        }
        return if (rest.last() in SINGLE_CODAS) rest.takeLast(1) else ""
    }
}
