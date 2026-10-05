package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Odia phonetic typing for [IndicPhonetic]: roman letters in, Odia script out.
 *
 * Odia is on the EXPLICIT model: every vowel is pronounced and typed, clusters
 * join, and a word-final consonant is written bare, as Odia spells it. The
 * inherent vowel is typed "a" (bhala ଭଲ, ghara ଘର): Odia says it as an open o,
 * but chat romanization writes "a" for it and keeps "o" for ୋ (mo ମୋ), so the
 * profile reads "o" as ୋ only. "R" and "Rh" are ଡ଼ and ଢ଼, "L" is ଳ, and "z" is
 * plain ଜ, since Odia has no z.
 *
 * The [hook] rewrites the tokens for Odia's own spelling habits. Each step is a
 * pattern of the word list, not of single words; the counts are weighted
 * frequency in the full Odia list, ours against the alternative:
 *
 *  1. **ai and au are two vowels**, a long aa and then i or u: bhai ଭାଇ, pai ପାଇ,
 *     khaiba ଖାଇବା, jauchi ଜାଉଛି. ାଇ 89.6k against ୈ 12.0k; ୌ stays on "ou".
 *  2. **A vowel and then "a" is ଆ, not ଅ:** oRia ଓଡ଼ିଆ, nua ନୁଆ. Vowel + ଆ 49.8k
 *     against vowel + ଅ 8.5k; jhia ଝିଅ is the exception the rule gets wrong.
 *  3. **Consonant + "ru" is the vowel sign ୃ**, which Odia says "ru": krushna
 *     କୃଷ୍ଣ, pruthibi ପୃଥିବି. C + ୃ 53.5k against C + ୍ରୁ 2.0k.
 *  4. **"sh" before t, th or n is the retroflex cluster** ଷ୍ଟ ଷ୍ଠ ଷ୍ଣ (kashta
 *     କଷ୍ଟ, drushti ଦୃଷ୍ଟି): ଶ୍ତ and ଶ୍ଥ do not occur, and ଷ୍ଣ is 4.5k against
 *     ଶ୍ନ 0.3k.
 *  5. **A nasal takes the place of the stop after it**, as a conjunct, not an
 *     anusvara: nk ଙ୍କ (132k against ଂକ 20k), nch ଞ୍ଚ (25k against 0.5k), nT and
 *     nD ଣ୍ଟ ଣ୍ଡ. Before s, sh, r or l it is the anusvara: ସଂସାର, ଅଂଶ, ଇଂରାଜୀ
 *     (ଂସ 10.1k against ନ୍ସ 2.6k; ଂଶ 9.2k against 4). "nh" before a vowel is ହ୍ନ,
 *     the way it is said and typed (janha ଜହ୍ନ, chinha ଚିହ୍ନ, kanhu କହ୍ନୁ): ନ୍ହ
 *     barely occurs (25), and ସିଂହ is typed "singh(a)", which no rule reaches.
 *  6. **"ch" between a vowel and a closing i, u, e or a, or before "ant", is ଛ:**
 *     the copula and the progressive and perfect endings (achi ଅଛି, karuchi
 *     କରୁଛି, achanti ଅଛନ୍ତି, gacha ଗଛ). Final ଛି 35.2k against ଚି 0.6k.
 *  7. **A word closing in -iba, -ila or -ela has a long final aa:** the infinitive
 *     and the past (kariba କରିବା, thila ଥିଲା, hela ହେଲା). -iba keeps it before the
 *     case endings ku, re and ra too (karibaku କରିବାକୁ). ିବା 49k against ିବ 4.7k,
 *     ିଲା 42k against ିଲ 3.3k, ିବାକୁ 7.9k against ିବକୁ 4.
 *  8. **v is ବ**, since Odia has no separate v sound, except right after a
 *     consonant, where it is the subjoined ୱ (svara ସ୍ୱର); w is always ୱ. ସ୍ୱ 17.1k
 *     against ସ୍ବ 0.2k.
 */
object OdiaPhonetic {

    private const val NA = "\u0928"
    private const val NGA = "\u0919"
    private const val NYA = "\u091E"
    private const val NNA = "\u0923"
    private const val HA = "\u0939"
    private const val CA = "\u091A"
    private const val CHA = "\u091B"
    private const val SHA = "\u0936"
    private const val SSA = "\u0937"
    private const val RA = "\u0930"
    private const val LA = "\u0932"
    private const val BA = "\u092C"
    private const val VA = "\u0935"
    private const val ANUSVARA = "\u0902"

    private val VELAR = setOf("\u0915", "\u0916", "\u0917", "\u0918")
    private val PALATAL = setOf("\u091A", "\u091B", "\u091C", "\u091D")
    private val RETRO = setOf("\u091F", "\u0920", "\u0921", "\u0922")
    private val TO_ANUSVARA = setOf("\u0938", "\u0936", "\u0937", "\u0930", "\u0932")

    /** Rule 4: त थ न after ष become ट ठ ण. */
    private val SH_RETROFLEX = mapOf("\u0924" to "\u091F", "\u0925" to "\u0920", "\u0928" to "\u0923")

    /** Rule 6: what may close a word after the ଛ. */
    private val CHA_CLOSINGS = setOf("i", "u", "e", "a")

    /** Rule 7: the case endings -iba keeps its long aa before. */
    private val IBA_TAILS = setOf("", "ku", "re", "ra")

    /** Rule 3: the vowel ऋ, typed "ru". */
    private val VOCALIC_RU = IndicPhonetic.Rule("ru", "\u090B", "\u0943")

    val PROFILE = IndicProfile(
        languageId = "or",
        script = IndicScript.ORIYA,
        model = IndicProfile.Model.EXPLICIT,
        extraConsonants = listOf("L" to "\u0933", "R" to "\u0921\u093C", "Rh" to "\u0922\u093C"),
        override = mapOf("z" to "\u091C"),
        anusvara = false,
        wordHook = ::hook,
    )

    private fun hook(engine: IndicPhonetic, input: List<IndicPhonetic.Rule>): List<IndicPhonetic.Rule> {
        val aa = engine.rule("aa")
        val i = engine.rule("i")
        val u = engine.rule("u")

        // 1: ai / au split into aa + i / aa + u.
        val split = ArrayList<IndicPhonetic.Rule>(input.size + 2)
        for (t in input) {
            when (t.match.lowercase()) {
                "ai" -> {
                    split += aa
                    split += i
                }
                "au" -> {
                    split += aa
                    split += u
                }
                else -> split += t
            }
        }

        // 2, 3 and 8, in one walk; rule 3 consumes the "u" after it.
        val pending = split.toMutableList<IndicPhonetic.Rule?>()
        val tokens = ArrayList<IndicPhonetic.Rule>(split.size)
        for (k in 0 until pending.size) {
            var t = pending[k] ?: continue
            val prev = tokens.lastOrNull()
            val next = pending.getOrNull(k + 1)
            if (t.isSchwa && prev?.kind == IndicPhonetic.Kind.VOWEL) t = aa // 2
            val ru = t.full == RA && next != null && next.match == "u"
            if (ru && prev?.kind == IndicPhonetic.Kind.CONSONANT) { // 3
                tokens += VOCALIC_RU
                pending[k + 1] = null
                continue
            }
            if (t.match == "v") { // 8
                t = IndicPhonetic.Rule("v", if (prev?.kind == IndicPhonetic.Kind.CONSONANT) VA else BA)
            }
            tokens += t
        }

        val n = tokens.size
        // 4: ष before ट ठ ण.
        for (k in 0 until n - 1) {
            val t = tokens[k]
            val next = tokens[k + 1]
            if (t.full != SHA || t.match != "sh") continue
            val retroflex = SH_RETROFLEX[next.full] ?: continue
            tokens[k] = IndicPhonetic.Rule("sh", SSA)
            tokens[k + 1] = IndicPhonetic.Rule(next.match, retroflex)
        }
        // 5: a nasal after a vowel and before a consonant.
        for (k in 1 until n - 1) {
            val t = tokens[k]
            val next = tokens[k + 1]
            if (t.full != NA || tokens[k - 1].kind != IndicPhonetic.Kind.VOWEL) continue
            if (next.kind != IndicPhonetic.Kind.CONSONANT) continue
            when {
                next.full in VELAR -> tokens[k] = IndicPhonetic.Rule("n", NGA)
                next.full in PALATAL -> tokens[k] = IndicPhonetic.Rule("n", NYA)
                next.full in RETRO -> tokens[k] = IndicPhonetic.Rule("n", NNA)
                next.full in TO_ANUSVARA ->
                    tokens[k] = IndicPhonetic.Rule("n", ANUSVARA, kind = IndicPhonetic.Kind.SIGN)
                next.full == HA && k + 2 < n && tokens[k + 2].kind == IndicPhonetic.Kind.VOWEL -> {
                    // nh: ହ୍ନ
                    tokens[k] = next
                    tokens[k + 1] = t
                }
            }
        }
        // 6: ଛ in the copula and the verb endings.
        for (k in 1 until n - 1) {
            if (tokens[k].full != CA || tokens[k - 1].kind != IndicPhonetic.Kind.VOWEL) continue
            val tail = rest(tokens, k + 1)
            if (tail in CHA_CLOSINGS || tail.startsWith("ant")) tokens[k] = IndicPhonetic.Rule("ch", CHA)
        }
        // 7: the long aa of -iba, -ila, -ela.
        for (k in 2 until n) {
            if (!tokens[k].isSchwa) continue
            val c = tokens[k - 1].full
            val v = tokens[k - 2].match
            val tail = rest(tokens, k + 1)
            if (c == BA && v == "i" && tail in IBA_TAILS) {
                tokens[k] = aa
            } else if (c == LA && (v == "i" || v == "e") && tail.isEmpty()) {
                tokens[k] = aa
            }
        }
        return tokens
    }

    /** The roman spelling, lowercased, of [tokens] from [from] to the end. */
    private fun rest(tokens: List<IndicPhonetic.Rule>, from: Int): String = buildString {
        for (k in from until tokens.size) append(tokens[k].match.lowercase())
    }
}
