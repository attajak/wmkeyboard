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
    private const val GOL_HE = 'ہ'
    private const val DO_HE = 'ھ'
    private const val NUN_GHUNNA = 'ں'
    private const val KHE = 'خ'

    /** Urdu's letters, read by the walk every Arabic-script language shares. */
    val TABLE = ArabicScriptPhonetic.Table(
        ye = YE,
        // ے takes a hamza after a vowel (گئے, آئے), as ی does.
        finalE = BARI_YE,
        consonants = listOf(
            // Aspirated digraphs. "nh", "mh", "lh" and "rh" are deliberately absent:
            // نھ is vanishingly rare next to the "nh" of "nhi" (نہیں) and "kahin",
            // where the h is the gol he of a whole syllable.
            "bh" to "ب$DO_HE",
            "ph" to "پ$DO_HE",
            "th" to "ت$DO_HE",
            "Th" to "ٹ$DO_HE",
            "jh" to "ج$DO_HE",
            "chh" to "چ$DO_HE",
            // "accha" doubles the c the way "pakka" doubles the k, and what
            // it means is the aspirate: اچھا, which is the word.
            "cchh" to "چ$DO_HE",
            "cch" to "چ$DO_HE",
            "ch" to "چ",
            "dh" to "د$DO_HE",
            "Dh" to "ڈ$DO_HE",
            "kh" to "ک$DO_HE",
            "Kh" to KHE.toString(),
            "gh" to "گ$DO_HE",
            "Gh" to "غ",
            "Rh" to "ڑ$DO_HE",
            "sh" to "ش",
            "zh" to "ژ",

            "b" to "ب",
            "p" to "پ",
            "t" to "ت",
            "T" to "ٹ",
            "s" to "س",
            "S" to "ص",
            "j" to "ج",
            "h" to GOL_HE.toString(),
            "H" to "ح",
            "d" to "د",
            "D" to "ڈ",
            "z" to "ز",
            "Z" to "ض",
            "r" to "ر",
            "R" to "ڑ",
            "f" to "ف",
            "q" to "ق",
            "k" to "ک",
            "g" to "گ",
            "l" to "ل",
            "m" to "م",
            "n" to "ن",
            // The nasalisation the rules never guess at (جان against ہیں), typed
            // deliberately.
            "N" to NUN_GHUNNA.toString(),
            "v" to WAO.toString(),
            "w" to WAO.toString(),
            "y" to YE.toString(),
            "x" to "کس",
            "c" to "ک",
            // The ain of عرض and دعا: no roman letter stands for it, so the
            // apostrophe does, as transcriptions of Urdu and Arabic both write it.
            "'" to "ع",
        ),
        vowels = listOf(
            ArabicScriptPhonetic.Vowel("aa", MADDA.toString(), ALIF.toString(), ALIF.toString()),
            ArabicScriptPhonetic.Vowel("A", MADDA.toString(), ALIF.toString(), ALIF.toString()),
            ArabicScriptPhonetic.Vowel("a", ALIF.toString(), "", ALIF.toString()),
            ArabicScriptPhonetic.Vowel("ee", "$ALIF$YE", YE.toString(), YE.toString()),
            ArabicScriptPhonetic.Vowel("ii", "$ALIF$YE", YE.toString(), YE.toString()),
            ArabicScriptPhonetic.Vowel("I", "$ALIF$YE", YE.toString(), YE.toString()),
            ArabicScriptPhonetic.Vowel("ai", "$ALIF$YE", YE.toString(), BARI_YE.toString()),
            ArabicScriptPhonetic.Vowel("i", ALIF.toString(), "", YE.toString()),
            ArabicScriptPhonetic.Vowel("oo", "$ALIF$WAO", WAO.toString(), WAO.toString()),
            ArabicScriptPhonetic.Vowel("uu", "$ALIF$WAO", WAO.toString(), WAO.toString()),
            ArabicScriptPhonetic.Vowel("U", "$ALIF$WAO", WAO.toString(), WAO.toString()),
            // "hua" is ہوا and "hui" ہوئی: a short u running into another vowel is
            // the long و after all, because there is nothing else for the second
            // vowel to hang off.
            ArabicScriptPhonetic.Vowel("ua", "$ALIF$WAO$ALIF", "$WAO$ALIF", "$WAO$ALIF"),
            ArabicScriptPhonetic.Vowel("ui", "$ALIF$WAO$HAMZA_YE$YE", "$WAO$HAMZA_YE$YE", "$WAO$HAMZA_YE$YE"),
            ArabicScriptPhonetic.Vowel("u", ALIF.toString(), "", WAO.toString()),
            ArabicScriptPhonetic.Vowel("au", "$ALIF$WAO", WAO.toString(), WAO.toString()),
            ArabicScriptPhonetic.Vowel("ou", "$ALIF$WAO", WAO.toString(), WAO.toString()),
            ArabicScriptPhonetic.Vowel("ay", "$ALIF$YE", YE.toString(), BARI_YE.toString()),
            ArabicScriptPhonetic.Vowel("ey", "$ALIF$YE", YE.toString(), BARI_YE.toString()),
            ArabicScriptPhonetic.Vowel("e", "$ALIF$YE", YE.toString(), BARI_YE.toString()),
            ArabicScriptPhonetic.Vowel("o", "$ALIF$WAO", WAO.toString(), WAO.toString()),
        ),
        // Rule 6.
        khInitial = KHE,
    )

    private val engine = ArabicScriptPhonetic(TABLE)

    /** Transliterates roman Urdu — one word or free text — into the Urdu script. */
    fun transliterate(input: String): String = engine.transliterate(input)

    /**
     * Every reading of [input] worth offering, the literal one first.
     *
     * Only the "a" moves: whether a lone "a" inside a word is a long ا or
     * nothing at all. The other guesses the spelling leaves open — ت against
     * ٹ, ن against ں, a final ا against ہ — have no second reading a rule could
     * pick: they are lexical, and a strip full of plausible misspellings would
     * be worse than one honest answer.
     */
    fun variants(input: String): List<String> = engine.variants(input)
}
