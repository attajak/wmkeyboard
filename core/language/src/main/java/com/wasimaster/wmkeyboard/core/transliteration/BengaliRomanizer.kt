package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Bengali script written back in Latin letters, the way people spell it in a
 * chat: তোমার → tomar, ধন্যবাদ → dhonnobad, কী → ki.
 *
 * The inverse of `AvroPhonetic`, roughly, but not by inverting its rules:
 * Avro reads a spelling somebody chose, while this has to *choose* one, and
 * the choices are pronunciation rules. Every consonant carries an inherent
 * "o" unless something silences it: a vowel sign, a hasanta, the end of the
 * word (কাল is kal, not kalo) or a following consonant that has its own vowel
 * (বলছি is bolchhi). A cluster keeps it (কষ্ট is koshto), and so do ভ হ ড় ঢ়
 * at the end of a word (শুভ, দেহ, বড়). য-ফলা and ব-ফলা double the consonant
 * they sit under except at the start of a word, where they are y and w
 * (সত্য sotto, ব্যাপার byapar, স্বপ্ন swopno, বিশ্ব bissho).
 *
 * Heuristic by nature: a converter puts a dictionary of known spellings in
 * front of this and lets the rules catch the rest. Output is always lower
 * case; anything that is not Bengali passes through untouched.
 */
object BengaliRomanizer {

    private const val HASANTA = '্'
    private const val NUKTA = '\u09BC'
    private const val CANDRABINDU = 'ঁ'
    private const val ANUSVARA = 'ং'
    private const val VISARGA = 'ঃ'
    private const val ZWJ = '‍'
    private const val ZWNJ = '‌'

    private val CONSONANTS: Map<Char, String> = mapOf(
        'ক' to "k", 'খ' to "kh", 'গ' to "g", 'ঘ' to "gh", 'ঙ' to "ng",
        'চ' to "ch", 'ছ' to "chh", 'জ' to "j", 'ঝ' to "jh", 'ঞ' to "n",
        'ট' to "t", 'ঠ' to "th", 'ড' to "d", 'ঢ' to "dh", 'ণ' to "n",
        'ত' to "t", 'থ' to "th", 'দ' to "d", 'ধ' to "dh", 'ন' to "n",
        'প' to "p", 'ফ' to "ph", 'ব' to "b", 'ভ' to "bh", 'ম' to "m",
        'য' to "j", 'র' to "r", 'ল' to "l", 'শ' to "sh", 'ষ' to "sh",
        'স' to "s", 'হ' to "h", '\u09DC' to "r", '\u09DD' to "rh", '\u09DF' to "y",
        'ৎ' to "t", 'ৰ' to "r", 'ৱ' to "w",
    )

    private val VOWELS: Map<Char, String> = mapOf(
        'অ' to "o", 'আ' to "a", 'ই' to "i", 'ঈ' to "i", 'উ' to "u", 'ঊ' to "u",
        'ঋ' to "ri", 'ঌ' to "li", 'এ' to "e", 'ঐ' to "oi", 'ও' to "o", 'ঔ' to "ou",
    )

    private val MATRAS: Map<Char, String> = mapOf(
        'া' to "a", 'ি' to "i", 'ী' to "i", 'ু' to "u", 'ূ' to "u", 'ৃ' to "ri", 'ৄ' to "ri",
        'ৢ' to "li", 'ৣ' to "li", 'ে' to "e", 'ৈ' to "oi", 'ো' to "o", 'ৌ' to "ou", 'ৗ' to "u",
    )

    /** Consonants that keep their inherent vowel at the end of a word. */
    private val KEEP_FINAL_O = setOf('ভ', 'হ', '\u09DC', '\u09DD')

    /** A ব-ফলা under one of these is a real b (অম্বর ombor), not a doubling. */
    private val B_STAYS_B = setOf('ম', 'ল', 'ন', 'ণ', 'ড', 'ঙ')

    /** Decomposed nukta letters, two-part vowels and the অ+া misspelling folded to one code point each. */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == ZWJ || c == ZWNJ -> i++
                next == NUKTA && c == 'ড' -> { out.append('\u09DC'); i += 2 }
                next == NUKTA && c == 'ঢ' -> { out.append('\u09DD'); i += 2 }
                next == NUKTA && c == 'য' -> { out.append('\u09DF'); i += 2 }
                next == NUKTA -> { out.append(c); i += 2 }
                c == 'ে' && next == 'া' -> { out.append('ো'); i += 2 }
                c == 'ে' && next == 'ৗ' -> { out.append('ৌ'); i += 2 }
                c == 'অ' && next == 'া' -> { out.append('আ'); i += 2 }
                c == NUKTA -> i++
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** Whole text: every Bengali run becomes Latin, everything else passes through. */
    fun romanize(text: String): String {
        val norm = normalize(text)
        val out = StringBuilder(norm.length)
        var i = 0
        while (i < norm.length) {
            if (BengaliGraphemes.isBengali(norm[i])) {
                var j = i
                while (j < norm.length && BengaliGraphemes.isBengali(norm[j])) j++
                out.append(word(norm.substring(i, j)))
                i = j
            } else {
                // The danda is Devanagari punctuation shared by Bengali, so
                // it sits outside the run but still wants a full stop.
                when (norm[i]) {
                    '\u0964' -> out.append('.')
                    '\u0965' -> out.append("..")
                    else -> out.append(norm[i])
                }
                i++
            }
        }
        return out.toString()
    }

    /** One Bengali word, without spaces. */
    fun romanizeWord(word: String): String = word(normalize(word))

    private class Cluster(val text: String, val yPhala: Boolean, val geminated: Boolean, val dead: Boolean)

    private fun word(w: String): String {
        val out = StringBuilder(w.length * 2)
        var geminateNext = false
        var i = 0
        val n = w.length
        while (i < n) {
            val c = w[i]
            when {
                c in CONSONANTS -> {
                    val cluster = ArrayList<Char>(3)
                    cluster.add(c)
                    var j = i + 1
                    while (j + 1 < n && w[j] == HASANTA && w[j + 1] in CONSONANTS) {
                        cluster.add(w[j + 1])
                        j += 2
                    }
                    val dangling = j < n && w[j] == HASANTA
                    if (dangling) j++
                    val initial = out.isEmpty()
                    val roman = cluster(cluster, initial)
                    var text = roman.text
                    if (geminateNext) {
                        text = text[0] + text
                        geminateNext = false
                    }
                    out.append(text)
                    val next = w.getOrNull(j)
                    when {
                        next != null && next in MATRAS -> {
                            out.append(MATRAS.getValue(next))
                            j++
                        }
                        dangling || roman.dead -> {}
                        else -> {
                            val strong = cluster.size > 1 || roman.geminated || text.length > roman.text.length
                            val keep = when {
                                next == null -> strong || cluster.last() in KEEP_FINAL_O
                                next in CONSONANTS ->
                                    strong || initial || next == 'হ' || w.getOrNull(j + 1) !in MATRAS
                                else -> true
                            }
                            if (keep) out.append(if (roman.yPhala) "a" else "o")
                        }
                    }
                    i = j
                }
                c in VOWELS -> { out.append(VOWELS.getValue(c)); i++ }
                c in MATRAS -> { out.append(MATRAS.getValue(c)); i++ }
                c == ANUSVARA -> { out.append("ng"); i++ }
                c == CANDRABINDU -> { out.append("n"); i++ }
                c == VISARGA -> {
                    if (i == n - 1) out.append("h") else geminateNext = true
                    i++
                }
                c == HASANTA -> i++
                c in '০'..'৯' -> { out.append('0' + (c - '০')); i++ }
                c == '।' -> { out.append('.'); i++ }
                c == '॥' -> { out.append(".."); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * The Latin for one consonant cluster. [initial] is whether it opens the
     * word, which is where য-ফলা and ব-ফলা are glides rather than doublings.
     */
    private fun cluster(chars: List<Char>, initial: Boolean): Cluster {
        if (chars.size == 1) {
            return Cluster(CONSONANTS.getValue(chars[0]), yPhala = false, geminated = false, dead = chars[0] == 'ৎ')
        }
        when {
            chars.size == 2 && chars[0] == 'ক' && chars[1] == 'ষ' ->
                return Cluster(if (initial) "kh" else "kkh", yPhala = false, geminated = !initial, dead = false)
            chars.size == 2 && chars[0] == 'জ' && chars[1] == 'ঞ' ->
                return Cluster("gg", yPhala = false, geminated = true, dead = false)
            chars.size == 2 && chars[0] == 'ঙ' && chars[1] == 'গ' ->
                return Cluster("ng", yPhala = false, geminated = false, dead = false)
        }
        val last = chars.last()
        val base = chars.dropLast(1)
        if (last == 'য') {
            if (initial) return Cluster(plain(base) + "y", yPhala = true, geminated = false, dead = false)
            if (base.last() == 'র') return Cluster(plain(base.dropLast(1)) + "rj", yPhala = false, geminated = true, dead = false)
            return Cluster(doubled(base), yPhala = false, geminated = true, dead = false)
        }
        if (last == 'ব' && base.first() != 'ব') {
            if (initial) return Cluster(plain(base) + "w", yPhala = false, geminated = false, dead = false)
            if (base.last() in B_STAYS_B) return Cluster(plain(base) + "b", yPhala = false, geminated = false, dead = false)
            return Cluster(doubled(base), yPhala = false, geminated = true, dead = false)
        }
        return Cluster(plain(chars), yPhala = false, geminated = false, dead = false)
    }

    private fun plain(chars: List<Char>): String = chars.joinToString("") { CONSONANTS.getValue(it) }

    /** [chars] with the last consonant's first Latin letter doubled: sh → ssh, n → nn. */
    private fun doubled(chars: List<Char>): String {
        val lastRoman = CONSONANTS.getValue(chars.last())
        return plain(chars.dropLast(1)) + lastRoman[0] + lastRoman
    }
}
