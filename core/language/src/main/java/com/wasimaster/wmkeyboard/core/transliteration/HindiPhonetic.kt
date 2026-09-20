package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Rule-based phonetic transliteration from romanized Hindi ("Hinglish") to
 * Devanagari: "kaise ho" → कैसे हो.
 *
 * The same kind of engine as [AvroPhonetic] — a greedy longest-match walk over
 * a rule table, vowels as independent letters or as matras depending on what
 * came before — with one difference that shapes everything else. Avro reads a
 * spelling scheme its users learned: every cluster they type is a conjunct,
 * because the inherent vowel is a key they press. Hindi has no such scheme.
 * People write it in Latin letters the way it sounds, and Hindi *drops* its
 * inherent vowel in speech wherever it can (schwa deletion), so the letters
 * "rn" in "karna" are two syllables (करना) while the "st" in "namaste" is one
 * conjunct (नमस्ते), and nothing in the spelling says which. Only a dictionary
 * settles that for certain, and the dictionary is a download the user may not
 * have — so the rules here make the likelier guess, and [variants] hands the
 * other readings to the strip.
 *
 *  1. **The inherent vowel is "a".** Silent after a consonant, अ at a word
 *     start. "aa" or "A" is आ / ा.
 *  2. **A word-final vowel is long.** Hindi never pronounces a final schwa, so
 *     a typed final "a" can only mean ा ("karna" → करना, "mera" → मेरा), and
 *     final "i" and "u" are ी and ू far more often than not ("ladki", "bhi",
 *     "tu").
 *  3. **An "a" before "o" or "u" is long**: "jao" → जाओ, "khao" → खाओ. So is
 *     the one in an "ai" that closes a word of more than one syllable, which
 *     is the noun ending ाई far more often than it is ै: "safai" → सफाई,
 *     "kamai" → कमाई, but "hai" → है.
 *  4. **A nasal before the stop it belongs to is an anusvara**: "hindi" →
 *     हिंदी, "lamba" → लंबा, "rang" → रंग.
 *  5. **Consonants split unless the table joins them** ([joins]). Joined:
 *     a cluster that opens the word (क्या, प्यार, स्कूल) or closes it (दोस्त,
 *     शब्द), a doubled consonant (पक्का, बच्चा), anything before "y", "tr" /
 *     "dr" / "shr", "sw" / "tv" / "dv", an "s" before t / p / k / m, "r" before
 *     the word's last consonant (गर्मी, धर्म), and "nh" / "mh".
 *  6. **A grammatical ending never joins its stem** ([guarded]): the "na" of
 *     an infinitive, the "ta" of a participle, the "ka" / "ko" / "se" / "me"
 *     that Hindi writes as separate words and chat spelling glues on — "karna",
 *     "sakta", "uska", "mujhse". This is checked before the joins above, which
 *     is what keeps "sunna" at सुनना rather than सुन्ना.
 *  7. Capitals spell the retroflex row and the long vowels for anyone who
 *     wants to be exact (T Th D Dh N Sh R Rh, A I U, M for ं, H for ः), and a
 *     capital that spells nothing reads as its lowercase self.
 *
 * Only runs of Latin letters are read; everything else passes through, so this
 * can be handed a whole sentence.
 *
 * Dental against retroflex ("t" is त in "tum" and ट in "roti"), and every other
 * distinction the spelling does not carry, is the dictionary's job —
 * [HindiPhoneticIndex] — not this transliterator's.
 */
object HindiPhonetic {

    private const val VIRAMA = '्'
    private const val ANUSVARA = 'ं'
    private const val VISARGA = 'ः'

    private const val AA = "आ"
    private const val AA_MATRA = "ा"

    // Written as escapes: the nukta letters have no code point NFC keeps, so
    // the pair is the spelling — and a literal here would be at the mercy of
    // whatever normalisation the source file has been through.
    private const val ZA = "ज़"
    private const val RRA = "ड़"
    private const val RRHA = "ढ़"

    private enum class Kind { CONSONANT, VOWEL, SIGN }

    /**
     * @param match the romanized token this rule fires on
     * @param full a consonant, or a vowel's independent form
     * @param matra the vowel-sign form used after a consonant; null means the
     *        rule is not a vowel. Empty for the inherent "a", which has none.
     * @param long what a short vowel becomes at the end of a word (rule 2)
     */
    private class Rule(
        val match: String,
        val full: String,
        val matra: String? = null,
        val kind: Kind = if (matra != null) Kind.VOWEL else Kind.CONSONANT,
        val long: Rule? = null,
    ) {
        val isSchwa: Boolean get() = matra != null && matra.isEmpty()
    }

    private val LONG_A = Rule("aa", AA, AA_MATRA)
    private val LONG_I = Rule("ee", "ई", "ी")
    private val LONG_U = Rule("oo", "ऊ", "ू")
    private val SCHWA = Rule("a", "अ", "", long = LONG_A)
    private val DIPHTHONG_AI = Rule("ai", "ऐ", "ै")

    private val rules: List<Rule> = buildList {
        add(LONG_A)
        add(Rule("A", AA, AA_MATRA))
        add(SCHWA)
        add(LONG_I)
        add(Rule("ii", "ई", "ी"))
        add(Rule("I", "ई", "ी"))
        add(Rule("i", "इ", "ि", long = LONG_I))
        add(LONG_U)
        add(Rule("uu", "ऊ", "ू"))
        add(Rule("U", "ऊ", "ू"))
        add(Rule("u", "उ", "ु", long = LONG_U))
        add(Rule("e", "ए", "े"))
        add(DIPHTHONG_AI)
        add(Rule("o", "ओ", "ो"))
        add(Rule("au", "औ", "ौ"))
        add(Rule("ou", "औ", "ौ"))
        add(Rule("RRi", "ऋ", "ृ"))

        add(Rule("kh", "ख"))
        add(Rule("gh", "घ"))
        add(Rule("chh", "छ"))
        add(Rule("ch", "च"))
        add(Rule("jh", "झ"))
        add(Rule("Th", "ठ"))
        add(Rule("Dh", "ढ"))
        add(Rule("th", "थ"))
        add(Rule("dh", "ध"))
        add(Rule("ph", "फ"))
        add(Rule("bh", "भ"))
        add(Rule("sh", "श"))
        add(Rule("Sh", "ष"))
        add(Rule("Rh", RRHA))
        // One key each, but two letters: the join is part of the letter.
        add(Rule("ksh", "क्ष"))
        add(Rule("kSh", "क्ष"))
        add(Rule("gy", "ज्ञ"))
        add(Rule("jn", "ज्ञ"))
        add(Rule("x", "क्स"))

        add(Rule("k", "क"))
        add(Rule("g", "ग"))
        add(Rule("c", "च"))
        add(Rule("j", "ज"))
        add(Rule("T", "ट"))
        add(Rule("D", "ड"))
        add(Rule("N", "ण"))
        add(Rule("t", "त"))
        add(Rule("d", "द"))
        add(Rule("n", "न"))
        add(Rule("p", "प"))
        // "fir" is how फिर gets typed, and फ is how the word lists spell the
        // loans too, so "f" carries no nukta.
        add(Rule("f", "फ"))
        add(Rule("b", "ब"))
        add(Rule("m", "म"))
        add(Rule("y", "य"))
        add(Rule("r", "र"))
        add(Rule("R", RRA))
        add(Rule("l", "ल"))
        add(Rule("v", "व"))
        add(Rule("w", "व"))
        add(Rule("s", "स"))
        add(Rule("h", "ह"))
        add(Rule("z", ZA))
        add(Rule("q", "क"))

        add(Rule("M", ANUSVARA.toString(), null, Kind.SIGN))
        add(Rule("H", VISARGA.toString(), null, Kind.SIGN))
    }.sortedByDescending { it.match.length }

    /** Bucketed by first char and still length-descending, as in [AvroPhonetic]. */
    private val rulesByFirstChar: Map<Char, List<Rule>> = rules.groupBy { it.match[0] }

    private fun exactRuleAt(input: String, at: Int): Rule? {
        val bucket = rulesByFirstChar[input[at]] ?: return null
        return bucket.firstOrNull { input.startsWith(it.match, at) }
    }

    /**
     * [exactRuleAt], falling back to the lowercase reading of a capital that
     * spells nothing on its own — a shift left latched from the key before, or
     * caps lock. The whole tail is lowered so a two-letter rule still matches
     * across it ("KH" is ख).
     */
    private fun ruleAt(input: String, at: Int): Rule? {
        exactRuleAt(input, at)?.let { return it }
        if (input[at] !in 'A'..'Z') return null
        return exactRuleAt(input.substring(at).lowercase(), 0)
    }

    /**
     * How the undecided parts of a spelling are read.
     *
     * @param guards whether a grammatical ending is kept off its stem (rule 6)
     * @param joinAll whether every typed cluster is a conjunct, table or not
     * @param joinNone whether nothing joins past the start of the word, and a
     *        nasal stays a letter
     * @param longFinal whether a word-final short vowel is lengthened (rule 2)
     * @param longLast whether the last "a" inside the word is read as आ
     */
    private class Reading(
        val guards: Boolean = true,
        val joinAll: Boolean = false,
        val joinNone: Boolean = false,
        val longFinal: Boolean = true,
        val longLast: Boolean = false,
    )

    private val LIKELY = Reading()

    /**
     * The one guess the rules cannot make at all: whether a lone "a" inside a
     * word is the inherent vowel or a long one nobody bothered to double.
     * "nazar" is नज़र and "kitab" is किताब, and nothing but knowing the word
     * tells them apart. Where it is long it is usually the last one — "pyar",
     * "sarkar", "tumhara", "pani" — so that is the reading offered second.
     */
    private val LONG_LAST = Reading(longLast = true)

    /** Every cluster a conjunct: the reading a scheme like ITRANS would make. */
    private val TIGHT = Reading(guards = false, joinAll = true)

    /** Every consonant its own syllable. */
    private val LOOSE = Reading(joinNone = true)

    /** The final vowel as short as it was typed — तत्सम words, and कि. */
    private val SHORT_FINAL = Reading(longFinal = false)

    /** Transliterates romanized Hindi — one word or free text — into Devanagari. */
    fun transliterate(input: String): String = render(input, LIKELY)

    /**
     * Every reading of [input] worth offering, the likely one first.
     *
     * The rules guess where Hindi's spelling is undecided, and with a word list
     * installed the guess hardly matters: the dictionary finds the word either
     * way. Without one these are what the strip has to offer instead — the
     * same spelling with its last "a" long, with every cluster joined, with
     * none joined, and with its final vowel left short.
     */
    fun variants(input: String): List<String> =
        listOf(LIKELY, LONG_LAST, TIGHT, LOOSE, SHORT_FINAL).map { render(input, it) }.distinct()

    private fun render(input: String, reading: Reading): String {
        val out = StringBuilder(input.length * 2)
        var i = 0
        while (i < input.length) {
            if (isLatin(input[i])) {
                var j = i
                while (j < input.length && isLatin(input[j])) j++
                word(input.substring(i, j), reading, out)
                i = j
            } else {
                out.append(input[i])
                i++
            }
        }
        return out.toString()
    }

    private fun isLatin(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    private fun tokenize(word: String): List<Rule> {
        val tokens = ArrayList<Rule>(word.length)
        var i = 0
        while (i < word.length) {
            // Every Latin letter has a rule, if only through the lowercase
            // fallback, so the walk always advances.
            val rule = ruleAt(word, i) ?: error("no rule for '${word[i]}'")
            tokens.add(rule)
            i += rule.match.length
        }
        return tokens
    }

    private fun word(word: String, reading: Reading, out: StringBuilder) {
        val tokens = tokenize(word)
        // The last inherent "a" that is inside the word rather than closing it.
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
                    // Rule 3's second half: the ाई that closes a noun.
                    if (last && token === DIPHTHONG_AI && tokens.subList(0, index).any { it.kind == Kind.VOWEL }) {
                        out.append(if (prev == Kind.CONSONANT) AA_MATRA else AA).append("ई")
                        prev = Kind.VOWEL
                        continue
                    }
                    val next = tokens.getOrNull(index + 1)
                    val rule = when {
                        last && reading.longFinal -> token.long ?: token
                        index == lastInner -> LONG_A
                        // Rule 3. Only the inherent vowel, and only before the
                        // back vowels: "gae" is गए, so "e" proves nothing.
                        token.isSchwa && next != null && next.match[0].lowercaseChar() in "ou" -> LONG_A
                        else -> token
                    }
                    out.append(if (prev == Kind.CONSONANT) rule.matra else rule.full)
                }
                Kind.CONSONANT -> {
                    if (nasalises(tokens, index, reading)) {
                        out.append(ANUSVARA)
                        prev = Kind.SIGN
                        continue
                    }
                    if (prev == Kind.CONSONANT && joins(tokens, index, reading)) out.append(VIRAMA)
                    out.append(token.full)
                }
                Kind.SIGN -> out.append(token.full)
            }
            prev = token.kind
        }
    }

    /** The stops each nasal is homorganic with, near enough: what it turns into ं before. */
    private val AFTER_N = setOf(
        "क", "ख", "ग", "घ", "च", "छ", "ज", "झ", "ट", "ठ", "ड", "ढ", "त", "थ", "द", "ध", "स", "श", "ष", ZA,
    )
    private val AFTER_M = setOf("प", "फ", "ब", "भ")

    /** Rule 4: whether the nasal at [index] is written as an anusvara. */
    private fun nasalises(tokens: List<Rule>, index: Int, reading: Reading): Boolean {
        if (reading.joinNone) return false
        val next = tokens.getOrNull(index + 1) ?: return false
        if (next.kind != Kind.CONSONANT) return false
        // Hangs off a vowel, so there has to be one: "nk" opening a word is
        // two letters, not a sign with nothing to sit on.
        if (tokens.getOrNull(index - 1)?.kind != Kind.VOWEL) return false
        val homorganic = when (tokens[index].full) {
            "न" -> next.full in AFTER_N
            "म" -> next.full in AFTER_M
            else -> false
        }
        // "unka" is उन + का, and the न there is a whole syllable.
        return homorganic && !(reading.guards && guarded(tokens, index + 1))
    }

    /**
     * Endings that are words of their own, or as good as: the postpositions
     * chat spelling glues on, the infinitive, and "-wala". Matched against the
     * whole rest of the word.
     */
    private val ENDINGS = setOf(
        "ka", "ki", "ke", "ko", "se", "me", "mein", "men", "na", "ne", "ni",
        "wala", "wali", "wale", "vala", "vali", "vale",
    )

    /**
     * The participle and the conjunctive. Guarded like [ENDINGS], except after
     * a sibilant: "dosti", "rasta" and "namaskar" are far commoner than the
     * handful of verbs whose stem ends in "s".
     */
    private val SOFT_ENDINGS = setOf("ta", "te", "ti", "kar")

    private val SIBILANTS = setOf("स", "श", "ष")

    /** Rule 6: whether the rest of the word from [index] is an ending its stem stays clear of. */
    private fun guarded(tokens: List<Rule>, index: Int): Boolean {
        // A stem has a vowel in it. Without one this is a cluster opening the
        // word ("sna…"), which is the one place a join is certain.
        if (tokens.subList(0, index).none { it.kind == Kind.VOWEL }) return false
        val rest = buildString { for (k in index until tokens.size) append(tokens[k].match.lowercase()) }
        if (rest in ENDINGS) return true
        return rest in SOFT_ENDINGS && tokens[index - 1].full !in SIBILANTS
    }

    /** Unaspirated → aspirated: "cchh", "tth", "ddh" double a consonant just as "kk" does. */
    private val ASPIRATE = mapOf(
        "क" to "ख", "ग" to "घ", "च" to "छ", "ज" to "झ", "ट" to "ठ",
        "ड" to "ढ", "त" to "थ", "द" to "ध", "प" to "फ", "ब" to "भ",
    )

    private val BEFORE_R = setOf("त", "थ", "द", "ध", "श")
    private val BEFORE_V = setOf("स", "श", "त", "द", "ध", "ज")
    private val AFTER_S = setOf("त", "थ", "ट", "ठ", "प", "क", "म")

    /**
     * Rule 5: whether the consonant at [index] forms a conjunct with the one
     * before it. One table, in one place, because every line of it is a
     * judgement about which of two readings is commoner — and the first thing
     * to revisit when a Hindi speaker says a word came out wrong.
     */
    private fun joins(tokens: List<Rule>, index: Int, reading: Reading): Boolean {
        val before = tokens[index - 1].full
        val here = tokens[index].full
        // Opening the word: there is no vowel for a schwa to have been dropped
        // after. True of every reading.
        if (tokens.subList(0, index).all { it.kind == Kind.CONSONANT }) return true
        if (reading.joinNone) return false
        val doubled = before == here || ASPIRATE[before] == here
        // A doubled consonant is a conjunct even where an ending begins with
        // it ("pakka", "kutta") — except "n" and "s", where the doubling *is*
        // the ending meeting its stem: "sunna" is सुन + ना, "usse" is उस + से.
        if (doubled && here != "न" && here != "स") return true
        if (reading.guards && guarded(tokens, index)) return false
        if (reading.joinAll) return true
        val last = index == tokens.lastIndex
        // What follows this consonant, when that is a single vowel and then
        // the end of the word — or nothing at all.
        val closing = last || (index == tokens.lastIndex - 1 && tokens[index + 1].kind == Kind.VOWEL)
        return when {
            doubled -> true
            here == "य" -> true
            here == "र" -> before in BEFORE_R
            here == "व" -> before in BEFORE_V
            before in SIBILANTS -> here in AFTER_S
            // Reph, where the cluster closes the word (गर्मी, शर्मा, धर्म). With
            // more word to come it is as often a dropped schwa (सरकार, बरसात).
            before == "र" -> closing
            here == "ह" -> before == "न" || before == "म"
            // Closing the word with no vowel typed between them: someone who
            // meant "nazar" wrote the "a".
            else -> last
        }
    }
}
