package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Malayalam's profile for [IndicPhonetic]: Mozhi's conventions ("t" ട, "th" ത,
 * "dh" ദ, "T" ഠ, "D" ഡ; a consonant closing a word is a chillu), bent toward how
 * people really type Malayalam in chats — "Manglish". Every choice below was
 * counted on the word list (ml_full, weighted by frequency) and checked on the
 * evaluation set.
 *
 * A nasal cluster is spelled out, never as an anusvara: Malayalam writes ന്ത
 * ണ്ട ങ്ക, not ംത ംട ംക (ംക 537 against ങ്ക 21,701). So [IndicProfile.anusvara]
 * is off, and the clusters are whole tokens of their own:
 *
 *  - "nt" ന്റ — ente, ninte (Mozhi's own rule)
 *  - "nth" ന്ത — enthu, so that "nt" does not eat the t of "th"
 *  - "nd" ണ്ട — undu, venda, kondu (ണ്ട 81,881 against ന്ദ 5,562)
 *  - "ndh" ന്ദ — the rarer one, typed with the h
 *  - "ng" ങ്ങ — ningal, engane (a lone ങ is not written)
 *  - "nk" ങ്ക — enkil
 *  - "nch" ഞ്ച — anchu
 *  - "sht" ഷ്ട — ishtam, kashtam (ശ്ട never occurs)
 *  - "tth" ത്ത — puratthu ("t" then "th" would be ട്ത)
 *
 * Everything that depends on where a letter stands is in [hook], one numbered
 * rule each:
 *
 *  1. "m" closing a word, or before s, sh, h, bh, r or v, is the anusvara:
 *     sukham സുഖം, samsaaram സംസാരം, sambhavam സംഭവം — Malayalam never writes a
 *     final മ്. And "mb" is മ്പ, the way Malayalam spells it: mumbu മുമ്പ്,
 *     ambalam അമ്പലം (മ്പ 9,307, ംബ 2,610).
 *  2. "d" between vowels, or closing a word after one, is ട, said [ɖ]: ivide
 *     ഇവിടെ, veedu വീട് (VടV 122,352 against VഡV 4,861). Anywhere else —
 *     opening a word, beside a consonant — it is ദ: divasam ദിവസം, addeham
 *     അദ്ദേഹം (a word-initial ദ 13,565 times, ഡ 10,423, mostly in English).
 *  3. "sh" after u, o, ee or r is ഷ, the Sanskrit "ruki" rule that Malayalam
 *     spelling keeps: santhosham സന്തോഷം, manushyan മനുഷ്യൻ, varsham വർഷം.
 *     After those ഷ beats ശ 8,700 to 1,900; anywhere else ശ stays (shari ശരി).
 *  4. "ch" between vowels is the geminate ച്ച: kurachu കുറച്ച്, vilichu
 *     വിളിച്ചു (45,962 against 6,131 for a single ച).
 *  5. "nj" after a vowel is the geminate ഞ്ഞ (paranju പറഞ്ഞു); a single ഞ
 *     inside a word is all but unwritten (53 against 7,916).
 *  6. "l" after the inherent vowel, closing a word or before a case ending, is
 *     the ള of the plural and the pronouns: ningal നിങ്ങൾ, ningalude
 *     നിങ്ങളുടെ, avalkku അവൾക്ക്, kuttikale കുട്ടികളെ. Closing a word ൾ beats
 *     ൽ 51,716 to 9,217; ളുടെ beats ലുടെ 12,176 to 28; ൾക്ക beats ൽക്ക 4,754
 *     to 194; ളെ beats ലെ 9,709 to 555. After "o" it is the "-pol" of ippol
 *     ഇപ്പോൾ, with a long ോ (ോൾ 4,294, ോൽ 159, ൊൾ 12).
 *  7. "n" in a closing "-anam" is ണ: panam പണം, kaaranam കാരണം, parayanam
 *     പറയണം — the modal "-aNam" and the Sanskrit "-aNa" nouns (ണം 16,487,
 *     നം 1,877).
 *  8. r, l and L closing a syllable inside a word are chillus, as modern
 *     Malayalam writes them: varsham വർഷം, sharkkara ശർക്കര, ningalkku
 *     നിങ്ങൾക്ക്. Counted per following letter the chillu wins everywhere
 *     (ർത 3,770 to ര്ത 41) except before യ (kaaryam കാര്യം), before the same
 *     letter doubled (ല്ല ള്ള), and ല before പ.
 *  9. A closing "u" is the half-u ് (samvruthokaram): athu അത്, enikku
 *     എനിക്ക്, undu ഉണ്ട്. Per closing cluster ് wins (ക്ക് 51,116 to 4,533;
 *     ണ്ട് 25,252 to 3,050) except after [U_KEEPS] — the past tense and
 *     "-unnu" (vannu വന്നു, paranju പറഞ്ഞു, cheythu ചെയ്തു) — and after a
 *     letter that has a chillu, whose ് would read as the chillu (oru ഒരു).
 */
object MalayalamPhonetic {

    val PROFILE = IndicProfile(
        languageId = "ml",
        script = IndicScript.MALAYALAM,
        model = IndicProfile.Model.EXPLICIT,
        dravidianVowels = true,
        extraConsonants = listOf(
            "zh" to "\u0934",
            "L" to "\u0933",
            "R" to "\u0931",
            "rr" to "\u0931",
            "nj" to "\u091E",
            "ng" to "\u0919\u094D\u0919",
            "nk" to "\u0919\u094D\u0915",
            "nt" to "\u0928\u094D\u0931",
            "nth" to "\u0928\u094D\u0924",
            "nd" to "\u0923\u094D\u091F",
            "ndh" to "\u0928\u094D\u0926",
            "nch" to "\u091E\u094D\u091A",
            "sht" to "\u0937\u094D\u091F",
            "tth" to "\u0924\u094D\u0924",
        ),
        override = mapOf(
            "t" to "\u091F",
            "th" to "\u0924",
            "T" to "\u0920",
            "Th" to "\u0925",
            "d" to "\u0921",
            "dh" to "\u0926",
            "D" to "\u0921",
            "Dh" to "\u0922",
        ),
        finalVirama = true,
        anusvara = false,
        wordHook = ::hook,
    )

    // The Devanagari letters the hook respells into, by the Malayalam they become.
    private const val TTA = "\u091F" // ट -> ട
    private const val DA = "\u0926" // द -> ദ
    private const val PA = "\u092A" // प -> പ
    private const val SSA = "\u0937" // ष -> ഷ
    private const val NNA = "\u0923" // ण -> ണ
    private const val LLA = "\u0933" // ळ -> ള
    private const val RA = "\u0930" // र
    private const val LA = "\u0932" // ल
    private const val CCA = "\u091A\u094D\u091A" // च्च -> ച്ച
    private const val NYNY = "\u091E\u094D\u091E" // ञ्ञ -> ഞ്ഞ
    private const val NN = "\u0928\u094D\u0928" // न्न -> ന്ന
    private const val LONG_O = "\u0913" // ओ
    private const val LONG_O_SIGN = "\u094B" // ो
    private const val ANUSVARA = "\u0902"
    private const val VIRAMA = "\u094D"
    private const val YA_LETTER = '\u092F' // य
    private const val PA_LETTER = '\u092A' // प

    /** An "m" before one of these is the anusvara (rule 1): स श ष ह भ र व. */
    private val M_ANUSVARA_BEFORE: Set<String> = setOf(
        "\u0938", "\u0936", "\u0937", "\u0939", "\u092D", "\u0930", "\u0935",
    )

    /** The vowels after which "sh" is ഷ (rule 3). */
    private val SH_AFTER: Set<String> = setOf("u", "uu", "oo", "o", "ee", "ii", "ru", "rri")

    /** What may follow the "-al" of the plural or a pronoun and keep it ള (rule 6). */
    private val L_CASE_ENDINGS: Set<String> =
        setOf("", "ude", "kku", "kk", "e", "il", "ilum", "ode", "odu", "aanu")

    /** र ल ळ to ർ ൽ ൾ, written straight into the output (rule 8). */
    private val CHILLU_OF: Map<String, String> = mapOf(
        "\u0930" to "\u0D7C",
        "\u0932" to "\u0D7D",
        "\u0933" to "\u0D7E",
    )

    /** न ण र ल ळ: a virama after one of these would read as a chillu (rule 9). */
    private val CHILLU_LETTERS: Set<String> = setOf("\u0928", "\u0923", "\u0930", "\u0932", "\u0933")

    /**
     * The closing clusters that keep a full ു (rule 9) — nn, chch, ch, njnj,
     * nj, yth: the past tense and "-unnu".
     */
    private val U_KEEPS: Set<String> = setOf(
        "\u0928\u094D\u0928",
        "\u091A\u094D\u091A",
        "\u091A",
        "\u091E\u094D\u091E",
        "\u091E",
        "\u092F\u094D\u0924",
    )

    /** The spellings that depend on position, which a match table cannot express. */
    @Suppress("UNUSED_PARAMETER")
    private fun hook(engine: IndicPhonetic, tokens: List<IndicPhonetic.Rule>): List<IndicPhonetic.Rule> {
        val out = tokens.toMutableList()
        val n = out.size

        fun isVowel(i: Int): Boolean = i in 0 until n && out[i].kind == IndicPhonetic.Kind.VOWEL

        fun isConsonant(i: Int): Boolean = i in 0 until n && out[i].kind == IndicPhonetic.Kind.CONSONANT

        for (i in 0 until n) {
            val t = out[i]
            if (t.kind != IndicPhonetic.Kind.CONSONANT) continue
            // 1. A closing "m", or one before s/sh/h/bh/r/v, is the anusvara; "mb" is മ്പ.
            if (t.match == "b" && i >= 1 && out[i - 1].match == "m") {
                out[i] = IndicPhonetic.Rule("b", PA)
                continue
            }
            if (t.match == "m" && isVowel(i - 1)) {
                if (i == n - 1 || (isConsonant(i + 1) && out[i + 1].full in M_ANUSVARA_BEFORE)) {
                    out[i] = IndicPhonetic.Rule("m", ANUSVARA, kind = IndicPhonetic.Kind.SIGN)
                }
                continue
            }
            // 2. "d" between vowels, or closing a word after one, is ട; anywhere else ദ.
            if (t.match == "d") {
                val intervocalic = isVowel(i - 1) && (i == n - 1 || isVowel(i + 1))
                out[i] = IndicPhonetic.Rule("d", if (intervocalic) TTA else DA)
                continue
            }
            // 3. "sh" after u, o, ee or r is ഷ.
            val afterRuki = i >= 1 && (
                (out[i - 1].kind == IndicPhonetic.Kind.VOWEL && out[i - 1].match.lowercase() in SH_AFTER) ||
                    out[i - 1].full == RA
                )
            if (t.match == "sh" && afterRuki) {
                out[i] = IndicPhonetic.Rule("sh", SSA)
                continue
            }
            // 4. "ch" between vowels is ച്ച.
            if (t.match == "ch" && isVowel(i - 1) && isVowel(i + 1)) {
                out[i] = IndicPhonetic.Rule("ch", CCA)
                continue
            }
            // 5. "nj" after a vowel is ഞ്ഞ.
            if (t.match == "nj" && isVowel(i - 1)) {
                out[i] = IndicPhonetic.Rule("nj", NYNY)
                continue
            }
            // 6. "l" after the inherent vowel, closing a word or before a case
            //    ending, is ള; the "-ol" closing a word is ോൾ.
            if (t.match == "l" && isVowel(i - 1) && i >= 2) {
                val rest = buildString { for (k in i + 1 until n) append(out[k].match) }.lowercase()
                if (out[i - 1].isSchwa && rest in L_CASE_ENDINGS) {
                    out[i] = IndicPhonetic.Rule("l", LLA)
                } else if (out[i - 1].match == "o" && i == n - 1) {
                    out[i - 1] = IndicPhonetic.Rule("o", LONG_O, LONG_O_SIGN)
                    out[i] = IndicPhonetic.Rule("l", LLA)
                }
                continue
            }
            // 7. "n" in a closing "-anam" is ണ. (Python's i == 0 case reads
            //    out[-1], which can never pass the checks; the i >= 1 guard
            //    only keeps the index in range.)
            if (t.match == "n" && i == n - 3 && i >= 1 && out[i - 1].isSchwa &&
                out[i + 1].isSchwa && out[i + 2].match == "m"
            ) {
                out[i] = IndicPhonetic.Rule("n", NNA)
                continue
            }
        }

        // 8. r, l and L closing a syllable inside a word are chillus, except
        //    before യ, before the same letter, and ല before പ.
        for (i in 1 until n - 1) {
            val t = out[i]
            val chillu = CHILLU_OF[t.full] ?: continue
            if (t.kind == IndicPhonetic.Kind.CONSONANT &&
                out[i - 1].kind == IndicPhonetic.Kind.VOWEL &&
                out[i + 1].kind == IndicPhonetic.Kind.CONSONANT
            ) {
                val next = out[i + 1].full[0]
                if (next != t.full[0] && next != YA_LETTER && !(t.full == LA && next == PA_LETTER)) {
                    out[i] = IndicPhonetic.Rule(t.match, chillu, kind = IndicPhonetic.Kind.SIGN)
                }
            }
        }

        // 9. A closing "u" is the half-u ്, except after U_KEEPS or a chillu letter.
        if (n >= 2 && out[n - 1].match == "u" && out[n - 2].kind == IndicPhonetic.Kind.CONSONANT) {
            var j = n - 2
            val cluster = ArrayList<String>()
            while (j >= 0 && out[j].kind == IndicPhonetic.Kind.CONSONANT) {
                cluster.add(0, out[j].full)
                j--
            }
            val joined = cluster.joinToString(VIRAMA)
            if (j >= 0 && joined !in U_KEEPS && cluster.last() !in CHILLU_LETTERS && !joined.endsWith(NN)) {
                out[n - 1] = IndicPhonetic.Rule("u", VIRAMA, VIRAMA)
            }
        }
        return out
    }
}
