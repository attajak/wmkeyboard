package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Gujarati phonetic: roman letters in, Gujarati script out, on [IndicPhonetic]'s
 * NORTH model — Hindi's schwa-dropping reading, written in the Gujarati block.
 *
 * Gujarati romanizers agree on a few letters Hindi's table reads otherwise, so
 * the profile respells them: "z" is ઝ, not a nukta ज़ ("Zala", "zaad", "zaghdo"
 * ઝઘડો); "x" is ક્ષ, not ક્સ ("Axar", "Laxmi", "Daxa"); "R" and "Rh" are ડ and ઢ,
 * since Gujarati has no nukta flap to spell them with; "L" is ળ.
 *
 * The rest is the word hook, and every rewrite in it is a regular fact of
 * Gujarati morphology or spelling that the generic reading gets wrong, numbered
 * as in the prototype the rules were tuned in:
 *
 *  1. **A final "-u" is the neuter -ું**: "hu" હું, "maru" મારું, "karvu" કરવું,
 *     "karyu" કર્યું. Every verb form and neuter adjective ends this way, so it
 *     is the reading to bet on; the plain -u nouns ("bahu", "chalu") belong in
 *     the word list. A final "-au" is the first person singular -ાઉં: "jau" જાઉં.
 *  2. **The locative is always nasal**: "-ma", "-maa", "-mathi" are -માં and
 *     -માંથી — "gharma" ઘરમાં, "gharmathi" ઘરમાંથી. Nobody types the nasal, and
 *     it is never missing in writing. Not after another "m", where the "ma" is
 *     part of the stem.
 *  3. **The oblique infinitive is "-vaa-"**: the "a" after the infinitive's "v"
 *     is long before an oblique ending — "karvanu" કરવાનું, "karvama" કરવામાં.
 *  4. **"vv" before the infinitive suffix is two syllables**, not a conjunct:
 *     "aavvu" આવવું, "banavvu" બનાવવું. An unwritten "a" goes between the two v's,
 *     the inherent vowel with no long form, so it stays the bare consonant વ.
 *  5. **A one-syllable "Cai" is C + ઈ, not ઐ**, when nothing or a verbal suffix
 *     follows: "gai" ગઈ, "thai" થઈ, "jaish" જઈશ, "laine" લઈને. These are the
 *     forms of જવું, થવું, લેવું, the one-syllable "ai" words people type. A
 *     longer word keeps ઐ: "paisa" પૈસા.
 *  6. **"i" after a vowel, or before "-ne", "-sh", "-shu", "-e", "-ye", is ઈ/ી**:
 *     "joie" જોઈએ, "karine" કરીને, "karish" કરીશ, "malie" મળીએ. The conjunctive
 *     participle ("-ine") and the future ("-ish") are written long. And a
 *     closing "-iye" is -ઈએ, the "y" only marking the glide: "joiye" જોઈએ,
 *     "kariye" કરીએ, "jaiye" જઈએ.
 *  7. **A Sanskrit "-tra", "-dra", "-shra" noun ends in the consonant**: "mitra"
 *     મિત્ર, "saurashtra" સૌરાષ્ટ્ર, "narendra" નરેન્દ્ર. Gujarati does not say
 *     the closing vowel there, so the final-vowel-is-long rule must not fire.
 *
 * The examples are the words as Gujarati writes them. Vowel length and dental
 * against retroflex inside the stem are still the engine's guess, which the
 * word list settles: with no list "maru" reads મરું and "saurashtra" સૌરશ્ત્ર.
 */
object GujaratiPhonetic {

    /** ઉં / ું: the neuter ending (rule 1). */
    private val U_NASAL = IndicPhonetic.Rule("u", "\u0909\u0902", "\u0941\u0902")

    /** આં / ાં: the locative's vowel (rule 2). */
    private val AA_NASAL_MA = IndicPhonetic.Rule("a", "\u0906\u0902", "\u093E\u0902")

    /** ઈ / ી, matched as "i" (rules 5 and 6). */
    private val I_LONG = IndicPhonetic.Rule("i", "\u0908", "\u0940")

    /** The inherent vowel with no long form, so it is never lengthened (rules 4, 5, 7). */
    private val SCHWA_FLAT = IndicPhonetic.Rule("a", "\u0905", "")

    /** એ / ે, standing for the "ye" after ઈ (rule 6). */
    private val E_FULL = IndicPhonetic.Rule("ye", "\u090F", "\u0947")

    private val LOCATIVE = setOf("ma", "maa", "mathi", "maathi")
    private val OBLIQUE_INF = setOf("vanu", "vani", "vano", "vana", "vama", "vamathi", "vathi", "vaa")
    private val INFINITIVE = setOf("vu", "va", "vi") + OBLIQUE_INF
    private val AI_SUFFIX = setOf("", "sh", "shu", "ne", "e", "ye")
    private val I_SUFFIX = setOf("ne", "sh", "shu", "e", "ye")

    /** त द श ष: the consonants a closing "-ra" leans on in rule 7. */
    private val TRA_HEADS = setOf("\u0924", "\u0926", "\u0936", "\u0937")
    private const val RA = "\u0930"

    val PROFILE = IndicProfile(
        languageId = "gu",
        script = IndicScript.GUJARATI,
        model = IndicProfile.Model.NORTH,
        extraConsonants = listOf("L" to "\u0933", "R" to "\u0921", "Rh" to "\u0922"),
        override = mapOf("z" to "\u091D", "x" to "\u0915\u094D\u0937"),
        endings = setOf(
            "ma", "maa", "maathi", "mate", "mathi", "na", "ne", "ni", "no", "nu", "par",
            "sathe", "thi", "va", "vama", "vamathi", "vana", "vani", "vano", "vanu", "vathi",
            "vi", "vu",
        ),
        softEndings = setOf("she", "shu", "su", "ta", "te", "ti", "to", "tu"),
        wordHook = ::hook,
    )

    private fun hook(engine: IndicPhonetic, tokens: List<IndicPhonetic.Rule>): List<IndicPhonetic.Rule> {
        val t = tokens.toMutableList()
        val aa = engine.rule("aa")

        fun rest(from: Int): String = buildString {
            for (k in from until t.size) append(t[k].match.lowercase())
        }

        fun vowelBefore(index: Int): Boolean =
            (0 until index).any { t[it].kind == IndicPhonetic.Kind.VOWEL }

        // 4. "aav" + "vu": keep the two v's apart. The list grows as it goes,
        //    so the bound is re-read each pass.
        var at = 1
        while (at < t.size) {
            if (t[at].match == "v" && t[at - 1].match == "v" && rest(at) in INFINITIVE && vowelBefore(at - 1)) {
                t.add(at, SCHWA_FLAT)
                at++
            }
            at++
        }
        for (k in 1 until t.size - 1) {
            val after = rest(k)
            // 3. The oblique infinitive.
            if (t[k].match == "v" && after in OBLIQUE_INF && vowelBefore(k) && t[k + 1].isSchwa) {
                t[k + 1] = aa
            }
            // 2. The locative.
            if (t[k].match == "m" && after in LOCATIVE && vowelBefore(k) &&
                t[k - 1].match != "m" && (t[k + 1].match == "a" || t[k + 1].match == "aa")
            ) {
                t[k + 1] = AA_NASAL_MA
            }
        }
        // 5. "gai", "thai", "jaish".
        for (k in t.indices) {
            if (t[k].match == "ai" && !vowelBefore(k) && rest(k + 1) in AI_SUFFIX) {
                t.removeAt(k)
                t.addAll(k, listOf(SCHWA_FLAT, I_LONG))
                break
            }
        }
        // 6. A long i after a vowel or before a verbal suffix.
        for (k in t.indices) {
            if (t[k].match == "i" && k > 0 &&
                (t[k - 1].kind == IndicPhonetic.Kind.VOWEL || (rest(k + 1) in I_SUFFIX && vowelBefore(k)))
            ) {
                t[k] = I_LONG
            }
        }
        // 6b. "-iye" is ઈએ.
        if (t.size >= 3 && t[t.size - 3] === I_LONG && t[t.size - 2].match == "y" && t[t.lastIndex].match == "e") {
            t.removeAt(t.lastIndex)
            t[t.lastIndex] = E_FULL
        }
        // 1. The neuter -ું, the first person -ાઉં.
        if (t.isNotEmpty() && t[t.lastIndex].match == "u") {
            t[t.lastIndex] = U_NASAL
        } else if (t.isNotEmpty() && t[t.lastIndex].match == "au" && t.size > 1) {
            t[t.lastIndex] = aa
            t.add(U_NASAL)
        }
        // 7. "-tra", "-dra", "-shra".
        val n = t.size
        if (n >= 4 && t[n - 1].isSchwa && t[n - 1].match == "a" && t[n - 2].full == RA &&
            t[n - 3].full in TRA_HEADS && t[n - 3].kind == IndicPhonetic.Kind.CONSONANT
        ) {
            t[n - 1] = SCHWA_FLAT
        }
        return t
    }
}
