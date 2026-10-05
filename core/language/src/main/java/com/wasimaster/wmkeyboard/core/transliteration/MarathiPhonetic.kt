package com.wasimaster.wmkeyboard.core.transliteration

import com.wasimaster.wmkeyboard.core.transliteration.IndicPhonetic.Kind
import com.wasimaster.wmkeyboard.core.transliteration.IndicPhonetic.Rule

/**
 * Marathi phonetic: roman letters in, Devanagari out, on [IndicPhonetic]'s
 * NORTH model — Hindi's, since Marathi drops the inherent vowel in speech the
 * same way. What differs from Hindi's table is a handful of letters: ळ is a
 * letter of its own, typed "L"; "z", "zh" and "jh" are all झ (the झ of "maza"
 * माझा and "zala" झाला), because Marathi barely uses ज़; and it has no ड़ or ढ़, so
 * a capital "R" / "Rh" is a plain ड / ढ.
 *
 * The rest is the word hook, which knows Marathi's grammar where the generic
 * reading only knows its sounds. On the 202-word chat evaluation set it takes
 * the likely reading from 98 exact words to 115. Its rules, in the order they
 * run:
 *
 *  1. **Glued postpositions keep their own spelling.** Marathi writes its
 *     postpositions onto the noun, and they have a fixed spelling the sounds do
 *     not give: the "madhe" people type is मध्ये, "sathi" is साठी, "kade" कडे,
 *     "mule" मुळे ("gharasathi" घरसाठी, "tyamule" त्यामुळे). Matched on the
 *     roman letters of the closing tokens, and only after a stem with a vowel.
 *  2. **i or u right after another vowel is long**: "hoil" होईल, "yeun" येऊन.
 *  3. **sh before t or th is retroflex**: "goshta" गोष्ट, "shreshtha" श्रेष्ठ.
 *     Marathi has no श्ट; the cluster is always ष्ट / ष्ठ.
 *  - 3b. **Clusters the table does not join, written as one letter**: "shtr" is
 *     ष्ट्र ("maharashtra"), "vh" is व्ह ("kevha" केव्हा, "navhta" नव्हता) and
 *     "lh" is ल्ह ("jilha" जिल्हा, "kolhapur").
 *  - 3c. **The prefix maha- is महा-**: "maharashtra", "mahamarg", "mahag" महाग.
 *  - 3d. **The present plural -tat is -तात**: "kartat" करतात, "astat".
 *  4. **The absolutive / ablative "-un" is -ऊन**: "karun" करून, "baghun" बघून.
 *  5. **A closing "au" is आऊ**: "bhau" भाऊ, "jau" / "jaun" जाऊ(न) — never the
 *     diphthong औ, which Marathi keeps for Sanskrit words.
 *  6. **The future participle is -णार**: "karnar" करणार, "janar" जाणार,
 *     "karnara" करणारा. Typed with a dental n, said and written with ण. No verb
 *     root ends in the inherent vowel, so a schwa just before it is long:
 *     "janar" is जाणार, not जणार.
 *  7. **The plural oblique -आं- before a case ending**: "tyanchi" त्यांची,
 *     "mitranno" मित्रांनो, "lokanna" लोकांना. The typed "n" is the anusvara.
 *  8. **The oblique -या- before a case ending**: "tyala" त्याला, "tyachya"
 *     त्याच्या, and the -झ्याकडे of "mazyakade".
 *  9. **The noun oblique -आ- before the genitive**: "gharachya" घराच्या,
 *     "devacha" देवाचा.
 *  10. **A closing "Cra" or "shta" keeps the inherent vowel** instead of the
 *     long final every other word gets: "mitra" मित्र, "goshta" गोष्ट,
 *     "shreshtha" श्रेष्ठ.
 */
object MarathiPhonetic {

    val PROFILE = IndicProfile(
        languageId = "mr",
        script = IndicScript.DEVANAGARI,
        model = IndicProfile.Model.NORTH,
        extraConsonants = listOf("L" to "\u0933", "R" to "\u0921", "Rh" to "\u0922", "zh" to "\u091D"),
        override = mapOf("z" to "\u091D"),
        endings = setOf(
            "cha", "che", "chi", "chya", "hun", "kade", "kadun", "la", "madhe", "madhun", "madhye", "mule",
            "na", "ne", "ni", "nu", "paryant", "pasun", "sathi", "tun", "un", "var", "varun",
        ),
        softEndings = setOf("le", "li", "lo", "naar", "nar", "ta", "tat", "te", "tes", "to", "tos"),
        wordHook = ::hook,
    )

    // Letters, as escapes.
    private const val A = "\u0905"
    private const val AA = "\u0906"
    private const val II = "\u0908"
    private const val U = "\u0909"
    private const val UU = "\u090A"
    private const val E = "\u090F"
    private const val AA_SIGN = "\u093E"
    private const val II_SIGN = "\u0940"
    private const val U_SIGN = "\u0941"
    private const val UU_SIGN = "\u0942"
    private const val E_SIGN = "\u0947"
    private const val KA = "\u0915"
    private const val TTA = "\u091F"
    private const val TTHA = "\u0920"
    private const val DDA = "\u0921"
    private const val NNA = "\u0923"
    private const val TA = "\u0924"
    private const val THA = "\u0925"
    private const val NA = "\u0928"
    private const val PA = "\u092A"
    private const val MA = "\u092E"
    private const val YA = "\u092F"
    private const val RA = "\u0930"
    private const val LA = "\u0932"
    private const val LLA = "\u0933"
    private const val VA = "\u0935"
    private const val SHA = "\u0936"
    private const val SSA = "\u0937"
    private const val SA = "\u0938"
    private const val HA = "\u0939"
    private const val VIRAMA = "\u094D"
    private const val ANUSVARA = "\u0902"

    /** मध्ये's conjunct, ध्य. */
    private const val DHYA = "\u0927\u094D\u092F"

    /** ष्ट्र, rule 3b. */
    private const val SHTRA = "\u0937\u094D\u091F\u094D\u0930"

    /** व्ह, rule 3b. */
    private const val VHA = "\u0935\u094D\u0939"

    /** ल्ह, rule 3b. */
    private const val LHA = "\u0932\u094D\u0939"

    /**
     * Rule 1: each postposition's roman letters, matched token by token, and
     * what each of those tokens becomes — a consonant's letter, or a vowel's
     * sign ("" for the inherent vowel). First match wins.
     */
    private val POSTPOSITIONS: List<Pair<String, List<String>>> = listOf(
        "madhe" to listOf(MA, "", DHYA, E_SIGN),
        // Tokens m a dh y e: five of them against four spellings, so this never
        // matches, and dh+y joins on its own anyway.
        "madhye" to listOf(MA, "", DHYA, E_SIGN),
        "sathi" to listOf(SA, AA_SIGN, TTHA, II_SIGN),
        "kade" to listOf(KA, "", DDA, E_SIGN),
        "kadun" to listOf(KA, "", DDA, UU_SIGN, NA),
        "pasun" to listOf(PA, AA_SIGN, SA, UU_SIGN, NA),
        "mule" to listOf(MA, U_SIGN, LLA, E_SIGN),
    )

    /** A vowel sign's independent form. */
    private val VOWEL_FULL: Map<String, String> = mapOf(
        "" to A, AA_SIGN to AA, II_SIGN to II, U_SIGN to U, UU_SIGN to UU, E_SIGN to E,
    )

    /** Rule 7: the plural oblique's tails after the "a"; "V" is any vowel. */
    private val PLURAL_TAILS: List<List<String>> = listOf(
        listOf("n", "ch", "V"),
        listOf("n", "ch", "y", "a"),
        listOf("n", "n", "V"),
    )

    /** Rule 9: the genitive's tails after the consonant and its "a". */
    private val GENITIVE_TAILS: List<List<String>> = listOf(
        listOf("ch", "V"),
        listOf("ch", "y", "a"),
    )

    /** Rule 8: what may follow the oblique -या-. */
    private val OBLIQUE_TAILS: Set<String> =
        PROFILE.endings + setOf("t", "ch", "nchi", "ncha", "nche", "nchya", "nni", "nna", "nno")

    /** A vowel rule with the typed letters [match] and the sign [sign]. */
    private fun vowel(match: String, sign: String): Rule = Rule(match, VOWEL_FULL.getValue(sign), sign)

    /** The long twin of a short a/i/u token, keeping the typed letters. */
    private fun lengthened(token: Rule): Rule = when (token.match.lowercase()) {
        "a" -> vowel(token.match, AA_SIGN)
        "i" -> vowel(token.match, II_SIGN)
        "u" -> vowel(token.match, UU_SIGN)
        else -> token
    }

    private fun hasVowel(tokens: List<Rule>): Boolean = tokens.any { it.kind == Kind.VOWEL }

    /** A tail pattern's slot: "V" is any vowel, anything else the typed letters. */
    private fun fits(token: Rule, want: String): Boolean =
        (want == "V" && token.kind == Kind.VOWEL) || token.match == want

    private fun hook(engine: IndicPhonetic, tokens: List<Rule>): List<Rule> {
        var t: MutableList<Rule> = tokens.toMutableList()
        var n = t.size

        // 1. Glued postpositions keep their own spelling.
        for ((suffix, spell) in POSTPOSITIONS) {
            if (n < spell.size) continue
            val from = n - spell.size
            if (t.subList(from, n).joinToString("") { it.match.lowercase() } != suffix) continue
            if (!hasVowel(t.subList(0, from))) continue
            // A capital M or H (anusvara, visarga) reads as "m"/"h" here, and
            // has no vowel sign to become.
            if (t.subList(from, n).any { it.kind == Kind.SIGN }) continue
            for (k in spell.indices) {
                val token = t[from + k]
                t[from + k] = if (token.kind == Kind.CONSONANT) Rule(token.match, spell[k]) else vowel(token.match, spell[k])
            }
            break
        }

        for (i in t.indices) {
            val token = t[i]
            val prev = if (i > 0) t[i - 1] else null
            // 2. i/u right after another vowel is long.
            if (token.kind == Kind.VOWEL && (token.match == "i" || token.match == "u") &&
                prev != null && prev.kind == Kind.VOWEL
            ) {
                t[i] = lengthened(token)
            }
            // 3. sh before t/th is retroflex.
            if (token.kind == Kind.CONSONANT && (token.full == TA || token.full == THA) &&
                prev != null && prev.full == SHA
            ) {
                t[i - 1] = Rule(prev.match, SSA)
                t[i] = Rule(token.match, if (token.full == TA) TTA else TTHA)
            }
        }

        // 3b. Clusters the generic table does not join, written as one letter.
        val out = ArrayList<Rule>(t.size)
        for (token in t) {
            val p = out.lastOrNull()
            if (p != null && p.kind == Kind.CONSONANT && token.kind == Kind.CONSONANT) {
                if (p.full == TTA && token.full == RA && out.size >= 2 && out[out.size - 2].full == SSA) {
                    val sh = out[out.size - 2]
                    out.removeAt(out.lastIndex)
                    out[out.lastIndex] = Rule(sh.match + p.match + token.match, SHTRA)
                    continue
                }
                val pair = when {
                    p.full == VA && token.full == HA -> VHA
                    p.full == LA && token.full == HA -> LHA
                    else -> null
                }
                if (pair != null) {
                    out[out.lastIndex] = Rule(p.match + token.match, pair)
                    continue
                }
            }
            out.add(token)
        }
        t = out
        n = t.size

        // 3c. The prefix maha- is महा-.
        if (n > 4 && t[0].match == "m" && t[1].match == "a" && t[2].match == "h" && t[3].match == "a" &&
            t.subList(4, n).any { it.kind == Kind.CONSONANT }
        ) {
            t[3] = vowel("a", AA_SIGN)
        }

        // 3d. The present plural -tat is -तात.
        if (n > 3 && t[n - 3].match == "t" && t[n - 2].match == "a" && t[n - 1].match == "t" &&
            hasVowel(t.subList(0, n - 3))
        ) {
            t[n - 2] = vowel("a", AA_SIGN)
        }

        // 4. The absolutive / ablative "-un" is -ऊन.
        if (n >= 3 && t[n - 1].full == NA && t[n - 2].match == "u" && hasVowel(t.subList(0, n - 2))) {
            t[n - 2] = lengthened(t[n - 2])
        }

        // 5. A closing "au" is आऊ.
        val au = when {
            n >= 1 && t[n - 1].match == "au" -> n - 1
            n >= 2 && t[n - 2].match == "au" && t[n - 1].full == NA -> n - 2
            else -> -1
        }
        if (au > 0 && t[au - 1].kind == Kind.CONSONANT) {
            t.removeAt(au)
            t.addAll(au, listOf(vowel("a", AA_SIGN), vowel("u", UU_SIGN)))
            n = t.size
        }

        // 6. The future participle -णार; a vowel after it (karnara) shifts it
        //    one token in from the end.
        val nar = if (n >= 5 && t[n - 1].kind == Kind.VOWEL && t[n - 1].match in FINAL_AFTER_NAR) 4 else 3
        if (n >= nar + 1 && t[n - nar + 2].full == RA &&
            (t[n - nar + 1].match == "a" || t[n - nar + 1].match == "aa") && t[n - nar].full == NA
        ) {
            val before = t.subList(0, n - nar)
            val stemEnd = before[before.lastIndex]
            // A consonant-final stem with a vowel in it (karnar), or a
            // consonant and its one vowel (yenar येणार).
            val consonantStem = stemEnd.kind == Kind.CONSONANT && hasVowel(before)
            val openStem = stemEnd.kind == Kind.VOWEL && before.size >= 2 &&
                before[before.size - 2].kind == Kind.CONSONANT &&
                before.count { it.kind == Kind.VOWEL } == 1
            val ok = consonantStem || openStem
            if (ok && (nar == 3 || stemEnd.kind == Kind.CONSONANT)) {
                // No verb root ends in a schwa: janar जाणार.
                if (stemEnd.isSchwa) t[n - nar - 1] = vowel(stemEnd.match, AA_SIGN)
                t[n - nar] = Rule(t[n - nar].match, NNA)
                t[n - nar + 1] = vowel(t[n - nar + 1].match, AA_SIGN)
            }
        }

        // 7. The plural oblique -आं- before a case ending.
        for (tail in PLURAL_TAILS) {
            val m = tail.size + 1
            if (n < m + 1) continue
            val head = t[n - m]
            if (!head.isSchwa) continue
            if (tail.indices.all { j -> fits(t[n - m + 1 + j], tail[j]) }) {
                val stem = t.subList(0, n - m)
                if (hasVowel(stem) || (stem.size >= 2 && stem.all { it.kind == Kind.CONSONANT })) {
                    t[n - m] = vowel(head.match, AA_SIGN)
                    t[n - m + 1] = Rule("n", ANUSVARA, kind = Kind.SIGN)
                }
                break
            }
        }

        // 8. The oblique -या- before a case ending.
        for (i in 2 until t.size - 1) {
            if (t[i].isSchwa && t[i - 1].full == YA && t[i - 2].kind == Kind.CONSONANT) {
                val rest = buildString { for (j in i + 1 until t.size) append(t[j].match.lowercase()) }
                if (rest in OBLIQUE_TAILS || engine.profile.endings.any { it.length >= 3 && rest.startsWith(it) }) {
                    t[i] = vowel(t[i].match, AA_SIGN)
                }
            }
        }

        // 9. The noun oblique -आ- before the genitive.
        for (tail in GENITIVE_TAILS) {
            val m = tail.size + 2
            if (t.size < m + 1) continue
            val from = t.size - m
            val consonant = t[from]
            val schwa = t[from + 1]
            if (consonant.kind == Kind.CONSONANT && schwa.isSchwa && hasVowel(t.subList(0, from)) &&
                tail.indices.all { j -> fits(t[from + 2 + j], tail[j]) }
            ) {
                t[from + 1] = vowel(schwa.match, AA_SIGN)
            }
        }

        // 10. A closing "Cra" or "shta" keeps the inherent vowel.
        val size = t.size
        if (size >= 3 && t[size - 1].match == "a") {
            val closing = t[size - 2]
            val third = t[size - 3]
            val keeps = closing.full.endsWith(VIRAMA + RA) ||
                ((closing.full == TTA || closing.full == TTHA) && third.full == SSA) ||
                (size >= 4 && closing.full == RA && third.kind == Kind.CONSONANT && third.full != RA)
            if (keeps) t[size - 1] = Rule("a", A, "")
        }
        return t
    }

    /** Rule 6: the vowels that may follow -णार (karnara, karnari, karnare). */
    private val FINAL_AFTER_NAR: Set<String> = setOf("a", "i", "e")
}
