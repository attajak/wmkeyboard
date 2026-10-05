package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Punjabi phonetic typing, into Gurmukhi: Hindi's schwa-dropping model
 * ([IndicProfile.Model.NORTH]), with the converter turning conjuncts into the
 * addak or pairin and the anusvara into a tippi or a bindi.
 *
 * Gurmukhi writes no conjuncts beyond pairin ਰ and ਹ, so most of the clusters
 * the north model joins vanish in conversion anyway. What Punjabi romanization
 * does carry is a handful of spelling patterns the generic model cannot see,
 * and [hook] rewrites them on the token list, one numbered rule each:
 *
 *  1. "oh" and "eh" opening a word are ਉਹ and ਇਹ: ohda ਉਹਦਾ, ehna ਇਹਨਾਂ.
 *  2. "eh" after a consonant is ਅ + ਹਿ: kehnda ਕਹਿੰਦਾ, pehla ਪਹਿਲਾ, shehar
 *     ਸ਼ਹਿਰ. Before a final "a" it is ਿਹਾ instead: reha ਰਿਹਾ, keha ਕਿਹਾ.
 *  3. Gurmukhi spells the y-glides with vowel carriers, not ਯ. A consonant and
 *     "ya" is ਿਆ (pyar ਪਿਆਰ, gya ਗਿਆ, dekhya ਦੇਖਿਆ); a consonant and "yi" or
 *     "ye" is a bare ਈ or ਏ (gyi ਗਈ, pye ਪਏ). "eya" is ਿਆ (peya ਪਿਆ); "iya" is
 *     ੀਆ past the first syllable or before "-n" (duniya ਦੁਨੀਆ, kuRiyan ਕੁੜੀਆਂ)
 *     and ਿਆ otherwise (piyar ਪਿਆਰ). A vowel and "ya" is ਇਆ, with a short "a"
 *     before it read long (hoya ਹੋਇਆ, khaya ਖਾਇਆ); a vowel and "yi" or "ye"
 *     drops the y (gayi ਗਈ, gaye ਗਏ). "yu" and "yo" are left alone: those are
 *     tatsama ਯ (ਉਦਯੋਗ, ਨਿਯੁਕਤ).
 *  4. "au" before "n" and then a d or a final vowel is ਾਉ: aunda ਆਉਂਦਾ,
 *     launa ਲਾਉਣਾ.
 *  5. "n" before a final -a, -i or -e (and -an, -iyan) is ਣ after a long
 *     vowel, after an "a" that is not the word's first letter, after ਹਿ, and
 *     after any consonant but ਰ ੜ ੜ੍ਹ ਣ ਨ ਹ, the dentals, ਵ and ਸ਼ — and ਸ before
 *     -e, the ergative of ਉਸਨੇ. So jana ਜਾਣਾ, pani ਪਾਣੀ, dekhna ਦੇਖਣਾ, apna
 *     ਆਪਣਾ, kehna ਕਹਿਣਾ, while karna ਕਰਨਾ and ohna ਉਹਨਾ keep ਨ. After the
 *     dentals, ਵ and ਸ਼ the word list is mostly tatsama nouns (ਪਤਨੀ ਸਾਧਨਾ
 *     ਆਮਦਨੀ ਭਾਵਨਾ ਰੋਸ਼ਨੀ) and few verbs, so ਨ is the likelier letter there.
 *  6. A word-final "n" is the nasal after o, e, ai and aa (ton ਤੋਂ, kiven
 *     ਕਿਵੇਂ, main ਮੈਂ, haan ਹਾਂ), and "-an" past the first syllable is ਾਂ,
 *     the plural (gallan ਗੱਲਾਂ, pehlan ਪਹਿਲਾਂ). "-un" is ਣ after a consonant
 *     (hun ਹੁਣ) and the nasal after a vowel (kiun ਕਿਉਂ); "-aun" is ਣ (kaun ਕੌਣ).
 *  7. A final "nu" is the dative ਨੂੰ: mainu ਮੈਨੂੰ, ohnu ਉਹਨੂੰ.
 *
 * Only the endings that open with ਵ or ਨ guard a join. -da, -di and -de must
 * not block the anusvara (hunda ਹੁੰਦਾ, kehnda ਕਹਿੰਦਾ), and -na and -ne must not
 * block a doubled nasal (kinna ਕਿੰਨਾ); Gurmukhi drops every other conjunct
 * anyway.
 */
object PunjabiPhonetic {

    val PROFILE = IndicProfile(
        languageId = "pa",
        script = IndicScript.GURMUKHI,
        model = IndicProfile.Model.NORTH,
        extraConsonants = listOf(
            "L" to "ळ",
            "R" to "ड़",
            "Rh" to "ड़्ह",
            "rh" to "ड़्ह",
        ),
        endings = setOf("naal", "vich", "wala", "wale", "wali"),
        softEndings = setOf("ke", "ta", "te", "ti"),
        wordHook = ::hook,
    )

    private const val ANUSVARA = "ं"

    /** इ ि, never lengthened at the end of a word, as the base "i" would be. */
    private val I_SHORT = IndicPhonetic.Rule("i", "इ", "ि")

    /** उ ु. */
    private val U_SHORT = IndicPhonetic.Rule("u", "उ", "ु")

    /** ई written in full even after a consonant: gyi ਗਈ. */
    private val FULL_EE = IndicPhonetic.Rule("ee", "ई", "ई")

    /** ए likewise: gye ਗਏ. */
    private val FULL_E = IndicPhonetic.Rule("e", "ए", "ए")

    /** ण, which Gurmukhi writes ਣ. */
    private val NNA = IndicPhonetic.Rule("n", "ण")

    /** A final "n" read as the nasal: ਂ or ੰ once converted. */
    private val NASAL = IndicPhonetic.Rule("n", ANUSVARA, kind = IndicPhonetic.Kind.SIGN)

    private val LONG = setOf("aa", "A", "ee", "ii", "I", "oo", "uu", "U", "e", "o", "ai", "au", "ou")
    private val FINAL_V = setOf("a", "aa", "i", "ee", "e")

    /** The tails besides a lone final vowel that rule 5 reads ਣ before. */
    private val NNA_TAILS = setOf("an", "aan", "eeaa", "eeaan")

    /** ड़्ह, which Gurmukhi writes ੜ੍ਹ: parh ਪੜ੍ਹ. */
    private const val RH = "ड़्ह"

    /**
     * Rule 5 keeps ਨ after ਰ ੜ ੜ੍ਹ ਣ ਨ ਹ (karna ਕਰਨਾ, ohna ਉਹਨਾ), and after the
     * dentals, ਵ and ਸ਼.
     */
    private val NO_NNA_AFTER = setOf(
        "र", "ड़", RH, "ण", "न", "ह",
        "त", "थ", "द", "ध", "व", "श",
    )

    private const val SA = "स"
    private const val DA = "द"

    private val A_OR_AA = setOf("a", "aa")
    private val I_OR_E = setOf("i", "ee", "e")
    private val PLURAL = setOf("an", "aan")
    private val NASAL_AFTER = setOf("o", "e", "ai", "aa", "A")

    private fun isVowel(rule: IndicPhonetic.Rule) = rule.kind == IndicPhonetic.Kind.VOWEL

    private fun isConsonant(rule: IndicPhonetic.Rule) = rule.kind == IndicPhonetic.Kind.CONSONANT

    /** The roman spelling of [tokens] from [from] to the end. */
    private fun tail(tokens: List<IndicPhonetic.Rule>, from: Int): String =
        buildString { for (k in from until tokens.size) append(tokens[k].match) }

    private fun hook(engine: IndicPhonetic, tokens: List<IndicPhonetic.Rule>): List<IndicPhonetic.Rule> {
        val t = tokens.toMutableList()
        if (t.isEmpty()) return t
        val schwa = engine.rule("a")
        val longA = engine.rule("aa")
        val longI = engine.rule("ee")
        val longU = engine.rule("oo")

        // 1. An opening "oh" or "eh".
        if (t.size >= 2 && (t[0].match == "e" || t[0].match == "o") && t[1].match == "h") {
            t[0] = if (t[0].match == "e") I_SHORT else U_SHORT
        }

        // 2. A consonant and "eh".
        var i = 1
        while (i < t.size - 1) {
            if (t[i].match == "e" && isConsonant(t[i - 1]) && t[i + 1].match == "h") {
                val next = t.getOrNull(i + 2)
                if (next == null || isConsonant(next)) {
                    t[i] = schwa
                    t.add(i + 2, I_SHORT)
                } else if (next.match == "a" && i + 3 == t.size - 1 && isConsonant(t[i + 3])) {
                    t[i] = schwa
                    t[i + 2] = I_SHORT
                } else if (next.match in A_OR_AA && i + 2 == t.size - 1) {
                    t[i] = I_SHORT
                }
            }
            i++
        }

        // 3. The y-glides.
        i = 1
        while (i < t.size - 1) {
            if (t[i].match != "y" || !isVowel(t[i + 1])) {
                i++
                continue
            }
            val prev = t[i - 1]
            val next = t[i + 1]
            if (isConsonant(prev)) {
                if (next.match in A_OR_AA) {
                    t[i] = I_SHORT
                    t[i + 1] = longA
                } else if (next.match in I_OR_E) {
                    t[i + 1] = if (next.match == "e") FULL_E else FULL_EE
                    t.removeAt(i)
                    continue
                }
            } else if (prev.match in I_OR_E && i >= 2 && isConsonant(t[i - 2])) {
                if (next.match in A_OR_AA) {
                    val first = t.subList(0, i - 1).none { isVowel(it) }
                    val plural = tail(t, i + 1) in PLURAL
                    t[i - 1] = if (prev.match == "e" || (prev.match == "i" && first && !plural)) I_SHORT else longI
                    t[i + 1] = longA
                    t.removeAt(i)
                    continue
                }
            } else if (isVowel(prev)) {
                if (next.match in A_OR_AA) {
                    if (prev.isSchwa) t[i - 1] = longA
                    t[i] = I_SHORT
                    t[i + 1] = longA
                } else if (next.match in I_OR_E) {
                    t.removeAt(i)
                    continue
                }
            }
            i++
        }

        // 4. "au", then "n", then a d or a final vowel.
        i = 0
        while (i < t.size - 2) {
            if (t[i].match == "au" && t[i + 1].match == "n") {
                val rest = tail(t, i + 2)
                if (t[i + 2].full == DA || rest in FINAL_V) {
                    t.removeAt(i)
                    t.addAll(i, listOf(longA, U_SHORT))
                    if (rest in FINAL_V) t[i + 2] = NNA
                    i += 2
                }
            }
            i++
        }

        // 5. "n" before a final vowel is ਣ.
        for (k in 1 until t.size - 1) {
            if (t[k].match != "n" || t[k] === NNA) continue
            val rest = tail(t, k + 1)
            if (rest !in FINAL_V && rest !in NNA_TAILS) continue
            val prev = t[k - 1]
            val ok = when (prev.kind) {
                IndicPhonetic.Kind.VOWEL ->
                    prev.match in LONG ||
                        (prev.isSchwa && k >= 2) ||
                        (prev === I_SHORT && t.getOrNull(k - 2)?.match == "h")
                IndicPhonetic.Kind.CONSONANT ->
                    prev.full !in NO_NNA_AFTER && prev.match != "y" && !(prev.full == SA && rest == "e")
                IndicPhonetic.Kind.SIGN -> false
            }
            if (ok) t[k] = NNA
        }

        // 6. A word-final "n".
        if (t.size >= 2 && t[t.lastIndex].match == "n" && isVowel(t[t.lastIndex - 1])) {
            val v = t[t.lastIndex - 1]
            val vowels = t.count { isVowel(it) }
            if (v.match in NASAL_AFTER) {
                t[t.lastIndex] = NASAL
            } else if (v.isSchwa && vowels >= 2) {
                t[t.lastIndex - 1] = longA
                t[t.lastIndex] = NASAL
            } else if (v.match == "u" && t.size >= 3) {
                t[t.lastIndex] = if (isVowel(t[t.lastIndex - 2])) NASAL else NNA
            } else if (v.match == "au") {
                t[t.lastIndex] = NNA
            }
        }

        // 7. A final "nu" is the dative ਨੂੰ.
        if (t.size >= 2 && t[t.lastIndex].match == "u" && t[t.lastIndex - 1].match == "n") {
            t[t.lastIndex] = longU
            t.add(NASAL)
        }
        return t
    }
}
