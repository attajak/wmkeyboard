package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Tamil phonetic typing on [IndicPhonetic], on the chat ("Tanglish")
 * conventions people actually type rather than a scholarly scheme: "t"/"d" are
 * ட, "th"/"dh" த, "zh" ழ, "L" ள, "R"/"rr" ற, "ng" ங்க (by way of the nasal
 * rule), "nj" ஞ்ச, and "s" is ச, which is how Tamil says it. Every vowel is
 * typed, e and o are short with one letter and long with a capital, a closing
 * consonant takes a pulli, the voiced and aspirated stops fold into the one
 * letter Tamil has for each, and the engine picks the dental ந or the alveolar
 * ன for every n.
 *
 * Romanizers do not spell Tamil letter for letter, though, and [hook] rewrites
 * the tokens of each word where the way Tamil is romanized is regular enough to
 * read back:
 *
 *  1. **"ndr" is ன்ற** — nandri நன்றி, indru இன்று, ondru ஒன்று. Tamil has no
 *     ண்ட்ர; the cluster is the alveolar ன்ற, said "ndr". Not opening a word.
 *  2. **A vowel, "tr" or "ttr", a vowel is ற்ற** — kaatru காற்று, netru நெற்று:
 *     ற்ற is said "ttr". A word-initial "tr" is left alone, because that is a
 *     loanword: train ட்ரைன்.
 *  3. **An r after n or l is ற** — solren சொல்றேன், enru என்று: native Tamil
 *     has no ன்ர or ல்ர.
 *  4. **A lone k, ch, t or p between vowels is doubled** — iruku இருக்கு,
 *     pochu பொச்சு, veetu வீட்டு, ipo இப்போ. A single Tamil stop between
 *     vowels is voiced, and romanizers write the voiced letter for it (g, s, d,
 *     b or v), so an unvoiced letter there is the geminate.
 *
 *     4b. **k, ch, th or p after ர or ழ is doubled** — vaazhkai வாழ்க்கை,
 *     vaazhthu வாழ்த்து, paarthen பார்த்தேன்: native Tamil always geminates a
 *     stop after these two.
 *  5. **A word-final e or o is long** — thaane தானே, eppo எப்போ, po போ: Tamil
 *     words do not end on a short ெ or ொ.
 *  6. **The verb endings -en (first person singular) and -om (plural) are long**
 *     in a word of two or more syllables — vanthen வந்தேன், povom பொவோம்.
 *  7. **The plural -gal / -kkal is கள்** — neengal நீங்கள், makkal மக்கள் —
 *     and so is it before a case ending that opens on a vowel other than a:
 *     ungalukku உங்களுக்கு, ungalai உங்களை. "kalai" கலை is not a plural.
 *  8. **The case ending -oda has a long o** — ennoda என்னோட, unnoda உன்னோட.
 *  9. **The formal present-tense marker -kir- / -gir- is கிற** — irukkiren
 *     இருக்கிறேன், seigirathu செய்கிறது — before a personal ending (e, aa, o)
 *     or "-athu"/"-adhu", and never opening a word: kiran கிரன்.
 *  10. **The spoken present-tense marker -ur- before a personal ending is உற** —
 *     pesuren பெசுறேன், pannuraanga பன்னுறாங்க.
 *  11. **An "i" after e, aa, o (and their long forms) is the glide ய்** — sei
 *     செய், seiya செய்ய, naai நாய், poi பொய்: Tamil has no such vowel sequence,
 *     and romanizers write the glide as i.
 *  12. **The obligative -anum after a verb stem is ணும்** — sollanum
 *     சொல்லணும், paakanum பாக்கணும். Only after a consonant a verb stem ends
 *     in, so not after v, th or b, which close the n-stem nouns that take -um:
 *     avanum அவனும். (An n is not a stem end either, so pannanum stays
 *     பன்னனும் and the word list puts it right.)
 *  13. **"tth" is த்த** — rattham ரத்தம், patthu பத்து: Tamil has no ட்த.
 *
 * Rules 1 to 4 and 9 to 13 rewrite one token at a time, walking the word left
 * to right and looking back at what has already been written; 5 to 8 and 12
 * look at how the rewritten word ends, and 7 scans it whole.
 */
object TamilPhonetic {

    /** ன, written straight, past the engine's choice between ந and ன. */
    private val ALVEOLAR_N = IndicPhonetic.Rule("n", "\u0929")

    /** ற. */
    private val RRA = IndicPhonetic.Rule("R", "\u0931")

    /** ள. */
    private val LLA = IndicPhonetic.Rule("L", "\u0933")

    /** Rule 4: the stops whose unvoiced letter between vowels means the geminate. */
    private val GEMINATE = setOf("k", "ch", "c", "t", "p")

    /** Rules 5 and 6: the short vowel and the rule for its long one. */
    private val LONG = mapOf("e" to "E", "o" to "O")

    /** Rule 10: what may follow the -ur- marker, all the rest of the word. */
    private val PRESENT_ENDINGS = setOf(
        "en", "En", "aen", "om", "Om", "aan", "aanga", "aa", "a", "iya", "inga",
        "eenga", "aal", "aanunga",
    )

    /** Rule 11: the vowels after which "i" is the glide. */
    private val GLIDE_BEFORE_I = setOf("e", "E", "ae", "aa", "A", "o", "O", "oo")

    /** Rule 9: the vowel that may follow -kir-. */
    private val AFTER_KIR = setOf("e", "E", "ae", "aa", "o", "O")

    /** Rule 12: the consonants a verb stem ends in before the obligative -anum. */
    private val VERB_STEM_END = setOf("k", "g", "ch", "s", "d", "t", "r", "l", "y", "p", "ng")

    /** Rule 4b: the stops doubled after ர or ழ. */
    private val AFTER_LIQUID = setOf("k", "ch", "c", "th", "p")

    /** Rule 7: the vowels after which -kal is not a plural. */
    private val A_VOWELS = setOf("a", "aa", "A")

    val PROFILE = IndicProfile(
        languageId = "ta",
        script = IndicScript.TAMIL,
        model = IndicProfile.Model.EXPLICIT,
        dravidianVowels = true,
        extraConsonants = listOf(
            "zh" to "\u0934", "L" to "\u0933", "R" to "\u0931", "rr" to "\u0931",
            "nj" to "\u091E\u094D\u091A", "S" to "\u0938",
        ),
        override = mapOf("s" to "\u091A", "t" to "\u091F", "d" to "\u0921"),
        finalVirama = true,
        tamilN = true,
        wordHook = ::hook,
    )

    private fun isVowel(rule: IndicPhonetic.Rule): Boolean = rule.kind == IndicPhonetic.Kind.VOWEL

    /** The Tanglish rewrites, numbered as in the class comment. */
    private fun hook(engine: IndicPhonetic, tokens: List<IndicPhonetic.Rule>): List<IndicPhonetic.Rule> {
        val t = tokens
        val n = t.size

        fun vowel(i: Int): Boolean = i in 0 until n && isVowel(t[i])

        fun restFrom(from: Int): String = buildString { for (k in from until n) append(t[k].match) }

        val out = ArrayList<IndicPhonetic.Rule>(n + 4)
        var i = 0
        while (i < n) {
            val m = t[i].match
            val next = t.getOrNull(i + 1)?.match
            val next2 = t.getOrNull(i + 2)?.match
            val written = out.lastOrNull()?.match
            // 11. "ei" is ெய் (sei செய், seiya செய்ய, nei நெய்).
            if (m == "i" && written != null && written in GLIDE_BEFORE_I) {
                out += engine.rule("y")
                i += 1
                continue
            }
            // 13. "tth" is த்த: the "t" is dropped for a second "th", and the
            // "th" itself is still read on the next step.
            if (m == "t" && next == "th") {
                out += t[i + 1]
                i += 1
                continue
            }
            // 1. "ndr" is ன்ற.
            if (m == "n" && next == "d" && next2 == "r" && i > 0) {
                out += ALVEOLAR_N
                out += RRA
                i += 3
                continue
            }
            // 2. "tr" / "ttr" between vowels is ற்ற.
            if (m == "t" && vowel(i - 1)) {
                if (next == "r" && vowel(i + 2)) {
                    out += RRA
                    out += RRA
                    i += 2
                    continue
                }
                if (next == "t" && next2 == "r" && vowel(i + 3)) {
                    out += RRA
                    out += RRA
                    i += 3
                    continue
                }
            }
            // 3. "nr" / "lr" is ன்ற / ல்ற.
            if (m == "r" && (written == "n" || written == "l")) {
                out += RRA
                i += 1
                continue
            }
            // 9. The formal present tense -kir- / -gir-.
            if (m == "r" && out.size >= 3 && written == "i" &&
                (out[out.size - 2].match == "k" || out[out.size - 2].match == "g") &&
                out[out.size - 3].kind != IndicPhonetic.Kind.SIGN &&
                out.subList(0, out.size - 1).any(::isVowel) &&
                ((next != null && next in AFTER_KIR) || (next == "a" && (next2 == "th" || next2 == "dh")))
            ) {
                out += RRA
                i += 1
                continue
            }
            // 10. The spoken present tense -ur- before a personal ending.
            if (m == "r" && written == "u" && out.count(::isVowel) >= 2 && restFrom(i + 1) in PRESENT_ENDINGS) {
                out += RRA
                i += 1
                continue
            }
            // 4b. A stop after ர or ழ is doubled.
            if (m in AFTER_LIQUID && i > 0 && (t[i - 1].match == "r" || t[i - 1].match == "zh") &&
                vowel(i - 2) && vowel(i + 1)
            ) {
                out += t[i]
                out += t[i]
                i += 1
                continue
            }
            // 4. A lone unvoiced stop between vowels is the geminate.
            if (m in GEMINATE && vowel(i - 1) && vowel(i + 1)) {
                out += t[i]
                out += t[i]
                i += 1
                continue
            }
            out += t[i]
            i += 1
        }

        val size = out.size
        val vowels = out.count(::isVowel)
        // 5. A final e / o is long.
        if (size >= 2 && LONG.containsKey(out[size - 1].match) &&
            out[size - 2].kind == IndicPhonetic.Kind.CONSONANT
        ) {
            out[size - 1] = engine.rule(LONG.getValue(out[size - 1].match))
        }
        // 6. The verb endings -en / -om are long.
        if (size >= 3 && vowels >= 2 && isVowel(out[size - 2])) {
            val v = out[size - 2].match
            val c = out[size - 1].match
            if ((v == "e" && c == "n") || (v == "o" && c == "m")) out[size - 2] = engine.rule(LONG.getValue(v))
        }
        // 8. The case ending -oda has a long o.
        if (size >= 4 && vowels >= 3 && out[size - 3].match == "o" && out[size - 2].match == "d" &&
            out[size - 1].match == "a"
        ) {
            out[size - 3] = engine.rule("O")
        }
        // 12. The obligative -anum after a verb stem is ணும்.
        if (size >= 5 && vowels >= 3 && out[size - 4].match == "a" && out[size - 3].match == "n" &&
            out[size - 2].match == "u" && out[size - 1].match == "m" && out[size - 5].match in VERB_STEM_END
        ) {
            out[size - 3] = engine.rule("N")
        }
        // 7. The plural -gal / -kkal is கள், closing the word or before a case
        // ending that opens on a vowel other than a.
        for (j in 2 until size) {
            val plural = out[j].match == "l" && out[j - 1].match == "a" &&
                (out[j - 2].match == "g" || (out[j - 2].match == "k" && j >= 3 && out[j - 3].match == "k"))
            val closes = j == size - 1 || (isVowel(out[j + 1]) && out[j + 1].match !in A_VOWELS)
            if (plural && closes) out[j] = LLA
        }
        return out
    }
}
