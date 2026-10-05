package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Phonetic typing for the Indic languages that do not have an engine of their
 * own: roman letters in, native script out — Marathi, Gujarati, Punjabi, Odia,
 * Tamil, Telugu, Kannada and Malayalam, one [IndicProfile] each.
 *
 * Every word is spelled in Devanagari first (extended with the letters
 * Devanagari encodes for the Dravidian languages: ऎ ऒ ॆ ॊ ऩ ऱ ऴ ळ) and then
 * moved into the target script by [IndicScript.fromDevanagari], because the
 * Unicode blocks are laid out in parallel. What differs between the languages
 * is the profile, and above all its [IndicProfile.Model]:
 *
 *  - **NORTH** (Marathi, Gujarati, Punjabi) is [HindiPhonetic]'s model, ported
 *    rule for rule. These are written the way they sound, and the inherent
 *    vowel is dropped in speech: "karna" is two syllables, "namaste" has a
 *    conjunct, a final vowel is long, a nasal before its stop is an anusvara,
 *    and a cluster joins only where the table says so — never across a
 *    grammatical ending, which each profile lists for its own language.
 *  - **EXPLICIT** (Odia and the Dravidian four) types every vowel, because the
 *    languages say every vowel: "amma", "nanna", "avan". A consonant directly
 *    followed by a consonant is a conjunct, full stop, and a word-final
 *    consonant takes a virama where the profile asks (a pulli in Tamil, a
 *    chillu in Malayalam).
 *
 * Only runs of roman letters are read; everything else passes through. The
 * rules guess where the spelling is undecided — vowel length, dental against
 * retroflex — and with a word list installed the guess hardly matters:
 * [IndicPhoneticIndex] finds the word either way. [variants] is for the user
 * with no list.
 *
 * The tables were tuned against hand-written evaluation sets and the real word
 * lists, one language at a time; each profile's comments say what moved.
 */
class IndicPhonetic(val profile: IndicProfile) {

    /**
     * @param match the roman token this rule fires on
     * @param full a consonant, or a vowel's independent form
     * @param sign the vowel-sign form used after a consonant; null means the
     *        rule is not a vowel. Empty for the inherent "a", which has none.
     * @param long what a short vowel becomes at the end of a word
     */
    class Rule internal constructor(
        val match: String,
        val full: String,
        val sign: String? = null,
        val kind: Kind = if (sign != null) Kind.VOWEL else Kind.CONSONANT,
        val long: Rule? = null,
    ) {
        val isSchwa: Boolean get() = sign != null && sign.isEmpty()
    }

    enum class Kind { CONSONANT, VOWEL, SIGN }

    private class Reading(
        val guards: Boolean = true,
        val joinAll: Boolean = false,
        val joinNone: Boolean = false,
        val longFinal: Boolean = true,
        val longLast: Boolean = false,
        /** EXPLICIT only: the closing inherent "a" read long (Tamil "amma" அம்மா). */
        val longFinalA: Boolean = false,
    )

    private val longA = Rule("aa", AA, AA_SIGN)

    private val rules: List<Rule> = buildRules()

    private val rulesByFirstChar: Map<Char, List<Rule>> = rules.groupBy { it.match[0] }

    private val diphthongAi: Rule = rules.first { it.match == "ai" }

    private val north: Boolean get() = profile.model == IndicProfile.Model.NORTH

    private fun buildRules(): List<Rule> {
        val out = ArrayList<Rule>()
        val longI = Rule("ee", "ई", "ी")
        val longU = Rule("oo", "ऊ", "ू")
        out += longA
        out += Rule("A", AA, AA_SIGN)
        out += Rule("a", "अ", "", long = longA)
        out += longI
        out += Rule("ii", "ई", "ी")
        out += Rule("I", "ई", "ी")
        out += Rule("i", "इ", "ि", long = longI)
        out += longU
        out += Rule("uu", "ऊ", "ू")
        out += Rule("U", "ऊ", "ू")
        out += Rule("u", "उ", "ु", long = longU)
        out += Rule("ai", "ऐ", "ै")
        out += Rule("au", "औ", "ौ")
        out += Rule("ou", "औ", "ौ")
        out += Rule("RRi", "ऋ", "ृ")
        out += Rule("Ru", "ऋ", "ृ")
        if (profile.dravidianVowels) {
            // Short e and o beside the long ones: one letter is short, the
            // capital (or "ae") is long, as Baraha and Lekhini write them.
            out += Rule("e", "ऎ", "ॆ")
            out += Rule("E", "ए", "े")
            out += Rule("ae", "ए", "े")
            out += Rule("o", "ऒ", "ॊ")
            out += Rule("O", "ओ", "ो")
        } else {
            out += Rule("e", "ए", "े")
            out += Rule("o", "ओ", "ो")
        }
        out.addAll(profile.extraVowels.map { (match, full, sign) -> Rule(match, full, sign) })
        val consonants = LinkedHashMap<String, String>()
        for ((match, dev) in BASE_CONSONANTS) {
            if (match !in profile.dropConsonants) consonants[match] = profile.override[match] ?: dev
        }
        for ((match, dev) in profile.extraConsonants) consonants[match] = dev
        for ((match, dev) in consonants) out += Rule(match, dev)
        out += Rule("M", ANUSVARA.toString(), kind = Kind.SIGN)
        out += Rule("H", VISARGA.toString(), kind = Kind.SIGN)
        // A vowel rule a profile overrides wins over the base one of the same
        // spelling: the later entry, kept by distinctBy over the reversed list.
        return out.asReversed().distinctBy { it.match }.sortedByDescending { it.match.length }
    }

    /**
     * This engine's rule for exactly [match] — the one tokenizing would have
     * produced. What a [IndicProfile.wordHook] swaps a token for when it means
     * an ordinary rule ("aa", "i"), so it never builds a second rule that the
     * walk would not recognise as the same vowel.
     */
    fun rule(match: String): Rule = rules.first { it.match == match }

    private fun exactRuleAt(input: String, at: Int): Rule? =
        rulesByFirstChar[input[at]]?.firstOrNull { input.startsWith(it.match, at) }

    /**
     * [exactRuleAt], falling back to the lowercase reading of a capital that
     * spells nothing of its own. The whole tail is lowered so a two-letter rule
     * still matches across it.
     */
    private fun ruleAt(input: String, at: Int): Rule? {
        exactRuleAt(input, at)?.let { return it }
        if (input[at] !in 'A'..'Z') return null
        return exactRuleAt(input.substring(at).lowercase(), 0)
    }

    /** Transliterates roman text — one word or a sentence — into the script. */
    fun transliterate(input: String): String = render(input, LIKELY)

    /**
     * Every reading of [input] worth offering, the likely one first. NORTH
     * offers Hindi's: the last inner "a" long, every cluster joined, none
     * joined, the final vowel short. EXPLICIT offers the closing "a" long and
     * the last inner "a" long.
     */
    fun variants(input: String): List<String> {
        val readings = if (north) {
            listOf(LIKELY, LONG_LAST, TIGHT, LOOSE, SHORT_FINAL)
        } else {
            listOf(LIKELY, LONG_FINAL_A, LONG_LAST)
        }
        return readings.map { render(input, it) }.distinct()
    }

    private fun render(input: String, reading: Reading): String {
        val dev = StringBuilder(input.length * 2)
        var i = 0
        while (i < input.length) {
            if (isLatin(input[i])) {
                var j = i
                while (j < input.length && isLatin(input[j])) j++
                word(input.substring(i, j), reading, dev)
                i = j
            } else {
                dev.append(input[i])
                i++
            }
        }
        return profile.script.fromDevanagari(dev.toString())
    }

    private fun isLatin(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    private fun tokenize(word: String): List<Rule> {
        val tokens = ArrayList<Rule>(word.length)
        var i = 0
        while (i < word.length) {
            val rule = ruleAt(word, i) ?: Rule(word[i].toString(), word[i].toString())
            tokens.add(rule)
            i += rule.match.length
        }
        return profile.wordHook?.invoke(this, tokens) ?: tokens
    }

    private fun word(word: String, reading: Reading, out: StringBuilder) {
        val tokens = tokenize(word)
        val start = out.length
        val lastInner = if (reading.longLast) {
            (tokens.lastIndex - 1 downTo 1).firstOrNull { tokens[it].isSchwa } ?: -1
        } else {
            -1
        }
        var prev: Kind? = null
        for ((index, token) in tokens.withIndex()) {
            val last = index == tokens.lastIndex
            when (token.kind) {
                Kind.VOWEL -> {
                    if (north && last && token === diphthongAi && tokens.subList(0, index).any { it.kind == Kind.VOWEL }) {
                        // The ाई that closes a noun: "safai" सफाई, but "hai" है.
                        out.append(if (prev == Kind.CONSONANT) AA_SIGN else AA).append("ई")
                        prev = Kind.VOWEL
                        continue
                    }
                    val next = tokens.getOrNull(index + 1)
                    val rule = when {
                        north && last && reading.longFinal && profile.longFinal -> token.long ?: token
                        !north && last && token.isSchwa && reading.longFinalA -> token.long ?: token
                        index == lastInner -> longA
                        // An "a" before "o" or "u" is long: "jao" जाओ.
                        north && token.isSchwa && next != null && next.match[0].lowercaseChar() in "ou" -> longA
                        else -> token
                    }
                    out.append(if (prev == Kind.CONSONANT) rule.sign else rule.full)
                    prev = Kind.VOWEL
                }
                Kind.CONSONANT -> {
                    if (nasalises(tokens, index, reading)) {
                        out.append(ANUSVARA)
                        prev = Kind.SIGN
                        continue
                    }
                    if (prev == Kind.CONSONANT) {
                        val join = if (north) joins(tokens, index, reading) else !reading.joinNone
                        if (join) out.append(VIRAMA)
                    }
                    var full = token.full
                    if (profile.tamilN && full == "न") {
                        // Tamil: dental ந opens a word and sits before த;
                        // alveolar ன everywhere else.
                        val opening = out.length == start
                        val beforeDental = tokens.getOrNull(index + 1)?.full == "त"
                        if (!opening && !beforeDental) full = "ऩ"
                    }
                    out.append(full)
                    prev = Kind.CONSONANT
                }
                Kind.SIGN -> {
                    out.append(token.full)
                    prev = Kind.SIGN
                }
            }
        }
        if (!north && profile.finalVirama && prev == Kind.CONSONANT) out.append(VIRAMA)
    }

    /** Whether the nasal at [index] is written as an anusvara. */
    private fun nasalises(tokens: List<Rule>, index: Int, reading: Reading): Boolean {
        if (!profile.anusvara || reading.joinNone) return false
        val next = tokens.getOrNull(index + 1) ?: return false
        if (next.kind != Kind.CONSONANT) return false
        if (tokens.getOrNull(index - 1)?.kind != Kind.VOWEL) return false
        val homorganic = when (tokens[index].full) {
            "न" -> next.full in AFTER_N
            "म" -> next.full in AFTER_M
            else -> false
        }
        return homorganic && !(north && reading.guards && guarded(tokens, index + 1))
    }

    /** Whether the rest of the word from [index] is an ending its stem stays clear of. */
    private fun guarded(tokens: List<Rule>, index: Int): Boolean {
        if (tokens.subList(0, index).none { it.kind == Kind.VOWEL }) return false
        val rest = buildString { for (k in index until tokens.size) append(tokens[k].match.lowercase()) }
        if (rest in profile.endings) return true
        return rest in profile.softEndings && tokens[index - 1].full !in SIBILANTS
    }

    /** NORTH: whether the consonant at [index] forms a conjunct with the one before it. */
    private fun joins(tokens: List<Rule>, index: Int, reading: Reading): Boolean {
        val before = tokens[index - 1].full
        val here = tokens[index].full
        if (tokens.subList(0, index).all { it.kind == Kind.CONSONANT }) return true
        if (reading.joinNone) return false
        val doubled = before == here || ASPIRATE[before] == here
        if (doubled && here != "न" && here != "स") return true
        if (reading.guards && guarded(tokens, index)) return false
        if (reading.joinAll) return true
        val last = index == tokens.lastIndex
        val closing = last || (index == tokens.lastIndex - 1 && tokens[index + 1].kind == Kind.VOWEL)
        return when {
            doubled -> true
            here == "य" -> true
            here == "र" -> before in BEFORE_R
            here == "व" -> before in BEFORE_V
            before in SIBILANTS -> here in AFTER_S
            before == "र" -> closing
            here == "ह" -> before == "न" || before == "म"
            else -> last
        }
    }

    private companion object {
        const val VIRAMA = '्'
        const val ANUSVARA = 'ं'
        const val VISARGA = 'ः'
        const val AA = "आ"
        const val AA_SIGN = "ा"

        val LIKELY = Reading()
        val LONG_LAST = Reading(longLast = true)
        val TIGHT = Reading(guards = false, joinAll = true)
        val LOOSE = Reading(joinNone = true)
        val SHORT_FINAL = Reading(longFinal = false)
        val LONG_FINAL_A = Reading(longFinalA = true)

        /** Hindi's consonant table, which every profile starts from. */
        val BASE_CONSONANTS: List<Pair<String, String>> = listOf(
            "kh" to "ख", "gh" to "घ", "chh" to "छ", "ch" to "च",
            "jh" to "झ", "Th" to "ठ", "Dh" to "ढ", "th" to "थ",
            "dh" to "ध", "ph" to "फ", "bh" to "भ", "sh" to "श",
            "Sh" to "ष", "ksh" to "क्ष", "x" to "क्स",
            "k" to "क", "g" to "ग", "c" to "च", "j" to "ज",
            "T" to "ट", "D" to "ड", "N" to "ण", "t" to "त",
            "d" to "द", "n" to "न", "p" to "प", "f" to "फ",
            "b" to "ब", "m" to "म", "y" to "य", "r" to "र",
            "l" to "ल", "v" to "व", "w" to "व", "s" to "स",
            "h" to "ह", "z" to "ज़", "q" to "क",
        )

        val AFTER_N: Set<String> = (
            "कखगघचछजझटठडढतथदधसशष"
                .map { it.toString() } + "ज़"
            ).toSet()
        val AFTER_M: Set<String> = setOf("प", "फ", "ब", "भ")
        val SIBILANTS: Set<String> = setOf("स", "श", "ष")
        val ASPIRATE: Map<String, String> = mapOf(
            "क" to "ख", "ग" to "घ", "च" to "छ", "ज" to "झ",
            "ट" to "ठ", "ड" to "ढ", "त" to "थ", "द" to "ध",
            "प" to "फ", "ब" to "भ",
        )
        val BEFORE_R: Set<String> = setOf("त", "थ", "द", "ध", "श")
        val BEFORE_V: Set<String> = setOf("स", "श", "त", "द", "ध", "ज")
        val AFTER_S: Set<String> = setOf("त", "थ", "ट", "ठ", "प", "क", "म")
    }
}

/**
 * One language's phonetic rules for [IndicPhonetic]: which script it writes,
 * which reading model it follows, and how its letters and endings differ from
 * Hindi's table.
 *
 * @param extraConsonants spellings added to, or replacing, the base table
 *        (Marathi "L" ळ, Tamil "zh" ழ)
 * @param extraVowels vowel rules added to, or replacing, the base ones, as
 *        (match, independent form, sign)
 * @param override base consonants respelled (Malayalam's Mozhi "t" ട)
 * @param finalVirama EXPLICIT: a word-final consonant takes a virama
 * @param anusvara a nasal before its stop is written ं
 * @param longFinal NORTH: a word-final short vowel is read long
 * @param endings NORTH: grammatical endings a stem never joins
 * @param softEndings NORTH: endings that still join after a sibilant
 * @param tamilN Tamil's dental/alveolar n
 * @param wordHook a language's own token rewrite, run after tokenizing, with
 *        the engine for [IndicPhonetic.rule]
 */
class IndicProfile(
    val languageId: String,
    val script: IndicScript,
    val model: Model,
    val dravidianVowels: Boolean = false,
    val extraConsonants: List<Pair<String, String>> = emptyList(),
    val extraVowels: List<Triple<String, String, String>> = emptyList(),
    val dropConsonants: Set<String> = emptySet(),
    val override: Map<String, String> = emptyMap(),
    val finalVirama: Boolean = false,
    val anusvara: Boolean = true,
    val longFinal: Boolean = true,
    val endings: Set<String> = emptySet(),
    val softEndings: Set<String> = emptySet(),
    val tamilN: Boolean = false,
    val wordHook: ((IndicPhonetic, List<IndicPhonetic.Rule>) -> List<IndicPhonetic.Rule>)? = null,
) {
    enum class Model { NORTH, EXPLICIT }
}
