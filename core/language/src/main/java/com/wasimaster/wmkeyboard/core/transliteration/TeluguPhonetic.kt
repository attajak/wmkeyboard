package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Telugu phonetic typing on [IndicPhonetic]: the EXPLICIT model, short and long
 * e and o (one letter short, the capital long), and a virama on a consonant
 * that closes the word.
 *
 * Chat Telugu hears English t and d as retroflex, so it writes "th" and "dh" for
 * the dentals త and ద ("thammudu" తమ్ముడు, "idhi" ఇది) and plain t and d for
 * either. The aspirates థ and ధ are rare — Sanskrit loans — and move to "thh" and
 * "dhh". "chh" is the geminate చ్చ ("vachhindi" వచ్చింది); ఛ is all but unused.
 * "L" is ళ and "R" the old ఱ.
 *
 * The word hook makes the common cases come out without capitals. Every rule was
 * measured on the frequency-weighted Telugu word list (the top 30–60k words),
 * and the counts below are from it:
 *
 *  0. **"ru" straight after a consonant is the vowel ృ**: "krushi" కృషి,
 *     "abhivruddhi" అభివృద్ధి, "drushti" దృష్టి. Cృ outnumbers C్రు 20.7k to 2.9k,
 *     and most of the ్రు side is the agent noun -ruḍu ("chandrudu" చంద్రుడు,
 *     "mitrudu" మిత్రుడు), which keeps ్రు: after a cluster, or before a closing
 *     "du".
 *  1. **e and o after a consonant are long in an open syllable** — one consonant
 *     and a vowel follow — and at the end of the word, short before a cluster or
 *     a nasal: "nenu" నేను, "roju" రోజు, "lo" లో, but "cheppu" చెప్పు, "konchem"
 *     కొంచెం. ే against ె is 146k to 45k before one consonant, 26k to 57k before a
 *     cluster. A cluster opened by r or s is the exception, long all the same:
 *     "chesthunna" చేస్తున్నా, "erpatu" ఏర్పాటు, "post" పోస్ట్ — ేస్C against ెస్C
 *     is 7.6k to 1.7k, ేర్C against ెర్C 8.1k to 1.1k, and ోస్ and ోర్ are 99%.
 *  2. **m after a vowel is the anusvara** when it closes the word or comes before
 *     a consonant other than m, y or r: "manam" మనం, "samvatsaram" సంవత్సరం. A
 *     final ం is 147k against 1.3k for a final మ్.
 *  3. **A word-final t or d after a vowel is retroflex** (before the closing u):
 *     "vaadu" వాడు, "eppudu" ఎప్పుడు, "paatu" పాటు. V+డు is 48k against 9k for
 *     V+దు, V+టు 7.7k against 0.5k. Not d after e, though: that is the negative
 *     -lēdu ("ledu" లేదు, "raledu" రాలేదు), where ేదు beats ేడు 4.0k to 0.4k.
 *  4. **n+t before i, u, e or a long vowel is ṇṭ**: "inti" ఇంటి, "ante" అంటే,
 *     "untunna" ఉంటున్నా. ంటి is 9.7k to 2k, ంటు 7.4k to 1.6k, a final ంటే 5.4k
 *     to 0.3k; before a or o it stays ంత ("antha" అంత).
 *  5. **sh+t is ష్ట** ("ishtam" ఇష్టం, "kashtam" కష్టం); శ్త does not occur.
 *  6. **The adverb suffix "ga" is long**: "baga" బాగా, "twaraga" త్వరగా. V+గా
 *     closes 23.8k words against 1.6k for V+గ. A word of one vowel ("ga") is not
 *     a suffix and is left alone.
 *  7. **The polite imperative and honorific "-andi" is ండి**: "cheppandi"
 *     చెప్పండి, "chudandi" చూడండి. It needs two vowels before it, so "randi"
 *     రండి is left to the word list, and not after m: "-mandi" is మంది, people
 *     ("konthamandi" కొంతమంది). "ibbandi" ఇబ్బంది is the list's too.
 *
 * Rules 1 to 7 are tried in that order, the first that fits a token wins, and
 * they read the tokens as the earlier ones left them. The words above are
 * cited as Telugu writes them; where the romanization leaves a length or a
 * place undecided ("baga" for బాగా, "krushi" for కృషి) the rules give their
 * guess and the word list supplies the rest.
 */
object TeluguPhonetic {

    /** Rule 2's anusvara, standing in for the "m" it replaces. */
    private val ANUSVARA = IndicPhonetic.Rule("m", "ं", kind = IndicPhonetic.Kind.SIGN)

    /** The retroflex reading of a plain t or d: ट, ड. */
    private val RETROFLEX: Map<String, String> = mapOf("t" to "ट", "d" to "ड")

    /** ष, for rule 5. */
    private const val SSA = "ष"

    /** Rule 1: the long vowel a short e or o becomes. */
    private val LONG: Map<String, String> = mapOf("e" to "E", "o" to "O")

    /** Rule 1: the consonants that open a cluster a long e or o still sits before. */
    private val LONG_BEFORE_CLUSTER: Set<String> = setOf("r", "s")

    /** Rule 2: the consonants an m stays a consonant before. */
    private val M_KEPT_BEFORE: Set<String> = setOf("m", "y", "r")

    /** Rule 3: the long and short e that make a closing "du" the negative -lēdu. */
    private val E_VOWELS: Set<String> = setOf("e", "E")

    /** Rule 4: the vowels after which n+t is ṇṭ. */
    private val NT_RETROFLEX: Set<String> = setOf("i", "I", "u", "U", "uu", "oo", "e", "E", "ae", "aa", "A")

    val PROFILE = IndicProfile(
        languageId = "te",
        script = IndicScript.TELUGU,
        model = IndicProfile.Model.EXPLICIT,
        dravidianVowels = true,
        extraConsonants = listOf(
            "L" to "ळ",
            "R" to "ऱ",
            "chh" to "च्च",
            "thh" to "थ",
            "dhh" to "ध",
        ),
        override = mapOf("th" to "त", "dh" to "द"),
        finalVirama = true,
        wordHook = ::hook,
    )

    private fun hook(engine: IndicPhonetic, tokens: List<IndicPhonetic.Rule>): List<IndicPhonetic.Rule> {
        val t = vocalicR(engine, tokens)
        val n = t.size

        fun isVowel(i: Int) = i in 0 until n && t[i].kind == IndicPhonetic.Kind.VOWEL

        fun isConsonant(i: Int) = i in 0 until n && t[i].kind == IndicPhonetic.Kind.CONSONANT

        val vowels = t.count { it.kind == IndicPhonetic.Kind.VOWEL }
        for (i in 0 until n) {
            val m = t[i].match
            when {
                // 1. e/o long in an open syllable, at the end, or before an r- or s- cluster.
                m in LONG && isConsonant(i - 1) && (
                    i == n - 1 ||
                        (isConsonant(i + 1) && isVowel(i + 2)) ||
                        (isConsonant(i + 1) && isConsonant(i + 2) && t[i + 1].match in LONG_BEFORE_CLUSTER)
                    ) -> {
                    t[i] = engine.rule(LONG.getValue(m))
                }
                // 2. m after a vowel, closing the word or before most consonants: ం.
                m == "m" && isVowel(i - 1) && (
                    i == n - 1 || (isConsonant(i + 1) && t[i + 1].match !in M_KEPT_BEFORE)
                    ) -> {
                    t[i] = ANUSVARA
                }
                // 3. a closing t/d before u, after a vowel, is retroflex — but not -lēdu.
                m in RETROFLEX && isVowel(i - 1) && i == n - 2 && t[i + 1].match == "u" &&
                    !(m == "d" && t[i - 1].match in E_VOWELS) -> {
                    t[i] = IndicPhonetic.Rule(m, RETROFLEX.getValue(m))
                }
                // 4. n+t before i/u/e or a long vowel is ṇṭ.
                m == "t" && i >= 1 && t[i - 1].match == "n" && isVowel(i + 1) &&
                    t[i + 1].match in NT_RETROFLEX -> {
                    t[i] = IndicPhonetic.Rule(m, RETROFLEX.getValue(m))
                }
                // 5. sh+t is ష్ట.
                m == "sh" && i + 1 < n && t[i + 1].match == "t" -> {
                    t[i] = IndicPhonetic.Rule("sh", SSA)
                    t[i + 1] = IndicPhonetic.Rule("t", RETROFLEX.getValue("t"))
                }
                // 6. the adverb suffix "ga" is long.
                m == "g" && i == n - 2 && t[i + 1].isSchwa && vowels >= 2 -> {
                    t[i + 1] = engine.rule("aa")
                }
                // 7. "-andi" is ండి, not after m and not with fewer than three vowels.
                m == "d" && i == n - 2 && t[i + 1].match == "i" && i >= 3 &&
                    t[i - 1].match == "n" && t[i - 2].isSchwa && vowels >= 3 &&
                    t[i - 3].match != "m" -> {
                    t[i] = IndicPhonetic.Rule(m, RETROFLEX.getValue(m))
                }
            }
        }
        return t
    }

    /**
     * Rule 0: an "r" then "u" straight after a single consonant becomes the vowel
     * ృ, except in a closing "-rudu" (the agent noun keeps ్రు). Reads the
     * original [tokens] for every test, as the Python did, and returns a fresh
     * list the other rules rewrite in place.
     */
    private fun vocalicR(engine: IndicPhonetic, tokens: List<IndicPhonetic.Rule>): MutableList<IndicPhonetic.Rule> {
        val n = tokens.size
        val out = ArrayList<IndicPhonetic.Rule>(n)
        var k = 0
        while (k < n) {
            if (k >= 1 && tokens[k - 1].kind == IndicPhonetic.Kind.CONSONANT &&
                (k < 2 || tokens[k - 2].kind != IndicPhonetic.Kind.CONSONANT) &&
                tokens[k].match == "r" && k + 1 < n && tokens[k + 1].match == "u" &&
                !(k + 3 == n - 1 && tokens[k + 2].match == "d")
            ) {
                out.add(engine.rule("Ru"))
                k += 2
                continue
            }
            out.add(tokens[k])
            k++
        }
        return out
    }
}
