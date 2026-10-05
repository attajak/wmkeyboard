package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Rule-based phonetic transliteration from roman Urdu to the Urdu script:
 * "aap kaise hain" → آپ کیسے ہیں (issue #496).
 *
 * The third engine of its kind here, after [AvroPhonetic] and [HindiPhonetic],
 * and the one with the least to go on — because Urdu's own spelling throws away
 * more than any roman spelling of it does.
 *
 *  1. **Short vowels are not written.** Urdu marks zabar, zer and pesh with
 *     diacritics that nobody types and the word lists do not carry, so a short
 *     vowel contributes nothing: "din" is دن, "kitab" is کتاب, "kuch" is کچھ.
 *     A vowel is written only where it is long — ا و ی ے — which is why this
 *     transliterator writes *fewer* letters than it is given, the opposite of
 *     the Indic two.
 *  2. **A word-final vowel is long**, since Urdu ends a word on a letter and
 *     not on a mark: final "a" is ا (کرنا), "i" is ی (کی), "u" and "o" are و
 *     (تو), "e" and "ai" are ے (کے, ہے).
 *  3. **A word-initial vowel needs a carrier**: a / i / u open on ا (اب, اس),
 *     "aa" on آ (آنا), e / ai on ای (ایک), o / au / oo on او (اور).
 *  4. **A vowel running into a vowel takes a hamza**: ی and ے become ئ after
 *     another vowel letter (کوئی, ہوئی), and و becomes ؤ after ا (جاؤ).
 *  5. **Aspiration is do-chashmi he**, so "bh th kh gh ch jh dh" are بھ تھ کھ
 *     گھ چھ جھ دھ, and the retroflexes are their capitals (T D R → ٹ ڈ ڑ), as
 *     on the other two phonetic layouts.
 *  6. **"kh" opening a word is خ**, not کھ. Both readings are everywhere —
 *     خوش, خبر, خدا, خاص against کھانا, کھیل — but the aspirated کھ is far
 *     commoner at the end of a word, where Urdu's own verbs put it (دیکھ, لکھ,
 *     رکھ, آنکھ), and the Perso-Arabic خ is commoner at the start. "Kh" is خ
 *     wherever it is typed, for anyone who wants to say so. "gh" is the other
 *     way round and stays گھ, because گھر alone outweighs the غ words; "Gh" is
 *     غ.
 *  7. **A doubled consonant is written once.** Urdu doubles with a shadda that
 *     is not typed and is not in the lists, so "acha" and "accha" are the same
 *     keystrokes to this (اچا, and اچھا through the spelling map).
 *
 * What the rules cannot know they do not pretend to: whether a lone "a" is a
 * long ا or nothing at all ("kitab" is کتاب but "karna" is کرنا), whether a "t"
 * is ت or ٹ, whether a final "a" is ا or the ہ of a Perso-Arabic noun (بچہ,
 * روزہ), and whether a final "n" is ن or the nasalisation ں (جان against ہیں).
 * Every one of those is a lexical fact, and the two layers above this one hold
 * it: the curated spelling map (`ur_rom.tsv`), which covers the commonest few
 * hundred words outright, and [UrduPhoneticIndex], whose fold throws away
 * exactly these distinctions so the word list can answer regardless. [variants]
 * is the fallback for someone with neither — the same spelling read with its
 * last, first or every inner "a" long.
 *
 * Only runs of Latin letters are read; everything else passes through, so this
 * can be handed a whole sentence.
 */
object UrduPhonetic {

    private const val ALIF = 'ا'
    private const val MADDA = 'آ'
    private const val WAO = 'و'
    private const val YE = 'ی'
    private const val BARI_YE = 'ے'
    private const val HAMZA_YE = 'ئ'
    private const val HAMZA_WAO = 'ؤ'
    private const val GOL_HE = 'ہ'
    private const val DO_HE = 'ھ'
    private const val NUN_GHUNNA = 'ں'
    private const val KHE = 'خ'

    /** The letters a following vowel cannot simply sit next to (rule 4). */
    private val VOWEL_LETTERS = charArrayOf(ALIF, MADDA, WAO, YE, BARI_YE, HAMZA_YE, HAMZA_WAO)

    /**
     * @param match the roman token this rule fires on
     * @param out what a consonant writes; empty for a vowel, which uses [forms]
     * @param forms a vowel's word-initial, word-internal and word-final
     *        spellings — internal is empty for the short vowels, which are not
     *        written at all (rule 1)
     */
    private class Rule(val match: String, val out: String = "", val forms: Forms? = null) {
        val isVowel: Boolean get() = forms != null
    }

    private class Forms(val initial: String, val medial: String, val final: String)

    private fun consonant(match: String, out: String) = Rule(match, out)

    private fun vowel(match: String, initial: String, medial: String, final: String) =
        Rule(match, forms = Forms(initial, medial, final))

    private val rules: List<Rule> = buildList {
        // Aspirated digraphs. "nh", "mh", "lh" and "rh" are deliberately absent:
        // نھ is vanishingly rare next to the "nh" of "nhi" (نہیں) and "kahin",
        // where the h is the gol he of a whole syllable.
        add(consonant("bh", "ب$DO_HE"))
        add(consonant("ph", "پ$DO_HE"))
        add(consonant("th", "ت$DO_HE"))
        add(consonant("Th", "ٹ$DO_HE"))
        add(consonant("jh", "ج$DO_HE"))
        add(consonant("chh", "چ$DO_HE"))
        // "accha" doubles the c the way "pakka" doubles the k, and what
        // it means is the aspirate: اچھا, which is the word.
        add(consonant("cchh", "چ$DO_HE"))
        add(consonant("cch", "چ$DO_HE"))
        add(consonant("ch", "چ"))
        add(consonant("dh", "د$DO_HE"))
        add(consonant("Dh", "ڈ$DO_HE"))
        add(consonant("kh", "ک$DO_HE"))
        add(consonant("Kh", KHE.toString()))
        add(consonant("gh", "گ$DO_HE"))
        add(consonant("Gh", "غ"))
        add(consonant("Rh", "ڑ$DO_HE"))
        add(consonant("sh", "ش"))
        add(consonant("zh", "ژ"))

        add(consonant("b", "ب"))
        add(consonant("p", "پ"))
        add(consonant("t", "ت"))
        add(consonant("T", "ٹ"))
        add(consonant("s", "س"))
        add(consonant("S", "ص"))
        add(consonant("j", "ج"))
        add(consonant("h", GOL_HE.toString()))
        add(consonant("H", "ح"))
        add(consonant("d", "د"))
        add(consonant("D", "ڈ"))
        add(consonant("z", "ز"))
        add(consonant("Z", "ض"))
        add(consonant("r", "ر"))
        add(consonant("R", "ڑ"))
        add(consonant("f", "ف"))
        add(consonant("q", "ق"))
        add(consonant("k", "ک"))
        add(consonant("g", "گ"))
        add(consonant("l", "ل"))
        add(consonant("m", "م"))
        add(consonant("n", "ن"))
        // The nasalisation the rules never guess at (جان against ہیں), typed
        // deliberately.
        add(consonant("N", NUN_GHUNNA.toString()))
        add(consonant("v", WAO.toString()))
        add(consonant("w", WAO.toString()))
        add(consonant("y", YE.toString()))
        add(consonant("x", "کس"))
        add(consonant("c", "ک"))
        // The ain of عرض and دعا: no roman letter stands for it, so the
        // apostrophe does, as transcriptions of Urdu and Arabic both write it.
        add(consonant("'", "ع"))

        add(vowel("aa", MADDA.toString(), ALIF.toString(), ALIF.toString()))
        add(vowel("A", MADDA.toString(), ALIF.toString(), ALIF.toString()))
        add(vowel("a", ALIF.toString(), "", ALIF.toString()))
        add(vowel("ee", "$ALIF$YE", YE.toString(), YE.toString()))
        add(vowel("ii", "$ALIF$YE", YE.toString(), YE.toString()))
        add(vowel("I", "$ALIF$YE", YE.toString(), YE.toString()))
        add(vowel("ai", "$ALIF$YE", YE.toString(), BARI_YE.toString()))
        add(vowel("i", ALIF.toString(), "", YE.toString()))
        add(vowel("oo", "$ALIF$WAO", WAO.toString(), WAO.toString()))
        add(vowel("uu", "$ALIF$WAO", WAO.toString(), WAO.toString()))
        add(vowel("U", "$ALIF$WAO", WAO.toString(), WAO.toString()))
        // "hua" is ہوا and "hui" ہوئی: a short u running into another vowel is
        // the long و after all, because there is nothing else for the second
        // vowel to hang off.
        add(vowel("ua", "$ALIF$WAO$ALIF", "$WAO$ALIF", "$WAO$ALIF"))
        add(vowel("ui", "$ALIF$WAO$HAMZA_YE$YE", "$WAO$HAMZA_YE$YE", "$WAO$HAMZA_YE$YE"))
        add(vowel("u", ALIF.toString(), "", WAO.toString()))
        add(vowel("au", "$ALIF$WAO", WAO.toString(), WAO.toString()))
        add(vowel("ou", "$ALIF$WAO", WAO.toString(), WAO.toString()))
        add(vowel("ay", "$ALIF$YE", YE.toString(), BARI_YE.toString()))
        add(vowel("ey", "$ALIF$YE", YE.toString(), BARI_YE.toString()))
        add(vowel("e", "$ALIF$YE", YE.toString(), BARI_YE.toString()))
        add(vowel("o", "$ALIF$WAO", WAO.toString(), WAO.toString()))
    }.sortedByDescending { it.match.length }

    /** Bucketed by first char and still length-descending, as in [AvroPhonetic]. */
    private val rulesByFirstChar: Map<Char, List<Rule>> = rules.groupBy { it.match[0] }

    private fun exactRuleAt(input: String, at: Int): Rule? =
        rulesByFirstChar[input[at]]?.firstOrNull { input.startsWith(it.match, at) }

    /**
     * [exactRuleAt], falling back to the lowercase reading of a capital that
     * spells nothing of its own — caps lock, or a shift the key before latched.
     * The whole tail is lowered so a two-letter rule still matches across it.
     */
    private fun ruleAt(input: String, at: Int): Rule? {
        exactRuleAt(input, at)?.let { return it }
        if (input[at] !in 'A'..'Z') return null
        return exactRuleAt(input.substring(at).lowercase(), 0)
    }

    /**
     * Which inner "a"s are read as a long ا.
     *
     * [NONE] writes none of them, which is the reading the rules make: it is
     * right for every verb ("karna" کرنا, "parhna" پڑھنا) and for the short
     * nouns ("kal" کل, "dil" دل), and it is the reading the dictionary fold
     * forgives either way. The others are what [variants] offers a user with no
     * word list, since the long one is usually the last inner vowel ("kitab"
     * کتاب, "sawal" سوال) and sometimes the first ("pani" پانی, "admi" آدمی).
     */
    private enum class LongA { NONE, LAST, FIRST, ALL }

    /** Transliterates roman Urdu — one word or free text — into the Urdu script. */
    fun transliterate(input: String): String = render(input, LongA.NONE)

    /**
     * Every reading of [input] worth offering, the literal one first.
     *
     * Only the "a" moves. The other guesses the spelling leaves open — ت
     * against ٹ, ن against ں, a final ا against ہ — have no second reading a
     * rule could pick: they are lexical, and a strip full of plausible
     * misspellings would be worse than one honest answer.
     */
    fun variants(input: String): List<String> =
        listOf(LongA.NONE, LongA.LAST, LongA.ALL, LongA.FIRST)
            .map { render(input, it) }
            .distinct()

    private fun render(input: String, longA: LongA): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (isLatin(input[i])) {
                var j = i
                while (j < input.length && isLatin(input[j])) j++
                word(input.substring(i, j), longA, out)
                i = j
            } else {
                out.append(input[i])
                i++
            }
        }
        return out.toString()
    }

    private fun isLatin(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c == '\''

    private fun tokenize(word: String): List<Rule> {
        val tokens = ArrayList<Rule>(word.length)
        var i = 0
        while (i < word.length) {
            val rule = ruleAt(word, i)
            if (rule == null) {
                // A letter with no rule at all cannot happen for a–z, but a
                // stray character still has to advance the walk.
                tokens.add(consonant(word[i].toString(), word[i].toString()))
                i++
            } else {
                tokens.add(rule)
                i += rule.match.length
            }
        }
        return tokens
    }

    private fun word(word: String, longA: LongA, out: StringBuilder) {
        val tokens = tokenize(word)
        val inner = tokens.indices.filter { i ->
            tokens[i].match == "a" && i != 0 && i != tokens.lastIndex
        }
        val long: Set<Int> = when {
            inner.isEmpty() -> emptySet()
            longA == LongA.ALL -> inner.toSet()
            longA == LongA.LAST -> setOf(inner.last())
            longA == LongA.FIRST -> setOf(inner.first())
            else -> emptySet()
        }
        val start = out.length
        var prevWasVowel = false
        for ((index, token) in tokens.withIndex()) {
            if (!token.isVowel) {
                // Rule 7: the shadda nobody types, so one letter and not two.
                val written = out.length - start
                if (!prevWasVowel && written >= token.out.length && out.endsWith(token.out)) {
                    prevWasVowel = false
                    continue
                }
                if (out.length == start && token.match == "kh") {
                    // Rule 6.
                    out.append(KHE)
                } else {
                    out.append(token.out)
                }
                prevWasVowel = false
                continue
            }
            val forms = token.forms!!
            val opening = out.length == start
            var form = when {
                opening -> forms.initial
                index == tokens.lastIndex -> forms.final
                index in long -> ALIF.toString()
                else -> forms.medial
            }
            if (form.isNotEmpty() && !opening) {
                // Rule 4: a vowel cannot lean on another vowel, so it takes a
                // hamza carrier. Only after a vowel — the ی of "yar" and the و
                // of "wada" are consonants, and nothing hangs off them.
                if (prevWasVowel && out.last() in VOWEL_LETTERS) {
                    form = when {
                        form[0] == WAO && (out.last() == ALIF || out.last() == MADDA) ->
                            HAMZA_WAO + form.substring(1)
                        form[0] == YE || form[0] == BARI_YE -> HAMZA_YE + form
                        else -> form
                    }
                }
                // One letter, not two: the ی of "yeh" and the و of "woh" are
                // the consonant and the vowel written with the same letter.
                if (form.length == 1 && out.last() == form[0]) form = ""
            }
            out.append(form)
            prevWasVowel = true
        }
    }
}
