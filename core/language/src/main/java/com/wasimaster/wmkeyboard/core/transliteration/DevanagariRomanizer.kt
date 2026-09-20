package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Hindi written back in Latin letters, the way people spell it in a chat:
 * करना → karna, नमस्ते → namaste, लड़की → ladki.
 *
 * The inverse of [HindiPhonetic], roughly, and like [BengaliRomanizer] not by
 * inverting its rules: the forward direction reads a spelling somebody chose,
 * while this has to *choose* one, and the choice that matters is which
 * inherent vowels to write. Devanagari gives every bare consonant an "a" and
 * spoken Hindi drops a good half of them, so the script says कर‌ना and the
 * mouth says "karna". Two rules cover nearly all of it:
 *
 *  - the inherent vowel that closes a word is silent (कमल is kamal), unless
 *    the word is that one syllable (न is na) or ends in a conjunct that cannot
 *    be said without it (मित्र is mitra, सत्य is satya);
 *  - inside a word it is silent between two syllables that are both voiced —
 *    vowel, consonant, *this*, consonant, vowel — decided from the end of the
 *    word backwards, so that समझना comes out samajhna rather than samjhna and
 *    no two in a row are ever dropped. A conjunct on either side keeps it:
 *    नमस्ते is namaste, not namste.
 *
 * Length is written the way chat spelling writes it: doubled inside a word
 * (naam, theek, door), single at the end (mera, ladki, tu), where Hindi has no
 * short vowel for it to be confused with.
 *
 * Heuristic by nature: a converter puts a dictionary of known spellings in
 * front of this and lets the rules catch the rest. Output is always lower
 * case; anything that is not Devanagari passes through untouched.
 */
object DevanagariRomanizer {

    private const val VIRAMA = '्'
    private const val NUKTA = '़'
    private const val ANUSVARA = 'ं'
    private const val CANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'
    private const val ZWJ = '‍'
    private const val ZWNJ = '‌'

    private val CONSONANTS: Map<Char, String> = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )

    /** What the nukta turns a letter into, where that is a different sound. */
    private val WITH_NUKTA: Map<Char, String> = mapOf('ज' to "z", 'फ' to "f", 'क' to "q")

    /** Precomposed nukta letters (U+0958–U+095F) as base + nukta, which is what NFC makes of them. */
    private const val NUKTA_BASES = "कखगजडढफय"

    private val VOWELS: Map<Char, String> = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
    )

    private val MATRAS: Map<Char, String> = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )

    /** A long vowel written single where it closes the word. */
    private val SHORT_AT_END = mapOf("aa" to "a", "ee" to "i", "oo" to "u")

    /** Conjuncts a word cannot end on without the vowel being said: मित्र, सत्य, तत्व. */
    private val NEEDS_FINAL_A = setOf('य', 'र', 'व')

    private val LABIALS = setOf('प', 'फ', 'ब', 'भ', 'म')
    private val FRONT_MATRAS = setOf('ि', 'ी', 'े', 'ै', 'ृ')

    fun isDevanagari(c: Char): Boolean = c.code in 0x0900..0x0963 || c.code in 0x0970..0x097F

    /** Precomposed nukta letters taken apart, and the joiners dropped. */
    fun normalize(text: String): String {
        if (text.none { it in 'क़'..'य़' || it == ZWJ || it == ZWNJ }) return text
        val out = StringBuilder(text.length + 4)
        for (c in text) {
            when {
                c in 'क़'..'य़' -> out.append(NUKTA_BASES[c - 'क़']).append(NUKTA)
                c == ZWJ || c == ZWNJ -> Unit
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Whole text: every Devanagari run becomes Latin, everything else passes through. */
    fun romanize(text: String): String {
        val norm = normalize(text)
        val out = StringBuilder(norm.length)
        var i = 0
        while (i < norm.length) {
            val c = norm[i]
            when {
                isDevanagari(c) -> {
                    var j = i
                    while (j < norm.length && isDevanagari(norm[j])) j++
                    out.append(word(norm.substring(i, j)))
                    i = j
                }
                c == '।' -> { out.append('.'); i++ }
                c == '॥' -> { out.append(".."); i++ }
                c in '०'..'९' -> { out.append('0' + (c - '०')); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** One Hindi word, without spaces. */
    fun romanizeWord(word: String): String = word(normalize(word))

    /**
     * One syllable as the script writes it: a consonant cluster and its vowel,
     * or an independent vowel on its own ([consonants] empty).
     *
     * @param vowel the vowel written, or null for the inherent one
     * @param silent set by [dropSchwas]: the inherent vowel is not said
     */
    private class Akshara(val consonants: List<Char>, val nukta: BooleanArray, val vowel: String?) {
        var silent = false
        var nasal = false
        var visarga = false
        var frontVowel = false
        val inherent: Boolean get() = vowel == null && consonants.isNotEmpty()
        val voiced: Boolean get() = vowel != null || (inherent && !silent)
    }

    private fun word(w: String): String {
        val aksharas = parse(w)
        dropSchwas(aksharas)
        val out = StringBuilder(w.length * 2)
        for ((index, a) in aksharas.withIndex()) {
            val last = index == aksharas.lastIndex
            for ((k, c) in a.consonants.withIndex()) out.append(consonant(a, k, c, last))
            val vowel = when {
                a.vowel != null -> if (last && !a.nasal) SHORT_AT_END[a.vowel] ?: a.vowel else a.vowel
                a.inherent && !a.silent -> "a"
                else -> ""
            }
            out.append(vowel)
            if (a.nasal) {
                // Nasalisation on a closing long ई is the one nobody writes: nahi.
                val dropped = last && a.vowel == "ee"
                if (dropped) {
                    out.setLength(out.length - 2)
                    out.append("i")
                } else if (last && a.vowel == "e") {
                    // में is "mein" to everyone; "men" reads as English.
                    out.append("in")
                } else {
                    val next = aksharas.getOrNull(index + 1)?.consonants?.firstOrNull()
                    out.append(if (next != null && next in LABIALS) "m" else "n")
                }
            }
            if (a.visarga) out.append('h')
        }
        return out.toString()
    }

    private fun consonant(a: Akshara, k: Int, c: Char, lastAkshara: Boolean): String {
        if (a.nukta[k]) WITH_NUKTA[c]?.let { return it }
        // क्ष and ज्ञ are said, and so spelled, as one thing.
        if (c == 'ष' && k > 0 && a.consonants[k - 1] == 'क') return "sh"
        if (c == 'ज' && a.consonants.getOrNull(k + 1) == 'ञ') return "g"
        if (c == 'ञ' && k > 0 && a.consonants[k - 1] == 'ज') return "y"
        // A doubled affricate is "cch", not "chch": baccha, acchha.
        if (c == 'च' && a.consonants.getOrNull(k + 1).let { it == 'च' || it == 'छ' }) return "c"
        if (c == 'व') {
            // "w" is how chat spelling writes it — wo, sawal, vishwas — except
            // before a front vowel and at the end of a word.
            val closesCluster = k == a.consonants.lastIndex
            val closing = lastAkshara && closesCluster && a.vowel == null
            return if (closing || (closesCluster && a.frontVowel)) "v" else "w"
        }
        return CONSONANTS.getValue(c)
    }

    private fun parse(w: String): MutableList<Akshara> {
        val out = ArrayList<Akshara>(w.length)
        var i = 0
        while (i < w.length) {
            val c = w[i]
            when {
                c in CONSONANTS -> {
                    val cluster = ArrayList<Char>(3)
                    val nukta = ArrayList<Boolean>(3)
                    var j = i
                    while (j < w.length && w[j] in CONSONANTS) {
                        cluster.add(w[j])
                        j++
                        val dotted = j < w.length && w[j] == NUKTA
                        if (dotted) j++
                        nukta.add(dotted)
                        if (j < w.length && w[j] == VIRAMA && j + 1 < w.length && w[j + 1] in CONSONANTS) j++ else break
                    }
                    val dangling = j < w.length && w[j] == VIRAMA
                    if (dangling) j++
                    val matra = w.getOrNull(j)?.takeIf { it in MATRAS }
                    if (matra != null) j++
                    val akshara = Akshara(cluster, nukta.toBooleanArray(), matra?.let { MATRAS.getValue(it) })
                    akshara.frontVowel = matra != null && matra in FRONT_MATRAS
                    // A written virama with nothing after it says so outright.
                    if (dangling) akshara.silent = true
                    out.add(akshara)
                    i = j
                }
                c in VOWELS -> { out.add(Akshara(emptyList(), BooleanArray(0), VOWELS.getValue(c))); i++ }
                c == ANUSVARA || c == CANDRABINDU -> { out.lastOrNull()?.nasal = true; i++ }
                c == VISARGA -> { out.lastOrNull()?.visarga = true; i++ }
                // stray matras, nukta, om, avagraha: nothing to say
                else -> i++
            }
        }
        return out
    }

    private fun dropSchwas(aksharas: List<Akshara>) {
        if (aksharas.size < 2) return
        val final = aksharas.last()
        if (final.inherent && !final.nasal) {
            val cluster = final.consonants
            final.silent = final.silent || cluster.size == 1 || cluster.last() !in NEEDS_FINAL_A
        }
        for (i in aksharas.lastIndex - 1 downTo 1) {
            val here = aksharas[i]
            if (!here.inherent || here.silent || here.nasal || here.consonants.size != 1) continue
            val before = aksharas[i - 1]
            val after = aksharas[i + 1]
            // Voiced on both sides, and the next syllable opens on one
            // consonant: dropping this vowel must not stack three of them.
            // A nasalised syllable before it counts as closed for the same
            // reason: ज़िंदगी is zindagi, since "ndg" is not sayable.
            if (before.voiced && !before.nasal && after.voiced && after.consonants.size == 1) here.silent = true
        }
    }
}
