package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Persian phonetic: Finglish in, Persian script out — "salam khoobi?" → سلام
 * خوبی؟ — on the walk [ArabicScriptPhonetic] shares with Urdu and Arabic.
 *
 * What is Persian's own, as measured against a 198-word hand-written set and
 * the subtitle-derived word list (60.6% exact by the rules alone, 67.2% with
 * the verb prefixes; the curated map and the dictionary take it from there):
 *
 *  - **its letters**: ی ک ه, never the Arabic ي ك or the Urdu ہ; no aspirates,
 *    no retroflexes. "kh" and "x" are خ, "ch" چ, "zh" ژ.
 *  - **short a, e and o are not written** inside a word; a closing "e" is the
 *    silent ه (khoone خونه, bache بچه); "i" is always long, ی.
 *  - **"gh" is ق** — ق is nine tenths of the ق/غ pair in the list by
 *    frequency — and "Gh" is غ. The index looks a "gh" up both ways.
 *  - **"ci" and "ce" are سی and س**: merci مرسی, cinema سینما.
 *  - **a vowel after a vowel sits on a plain ی or و**, not a hamza seat:
 *    کجایی, پاییز.
 *  - **the verb prefixes می‌ and نمی‌ are their own unit**, joined to the stem
 *    by a ZWNJ, as the list spells every one of them: mikonam می‌کنم,
 *    nemitoonam نمی‌تونم. A stem shorter than two tokens keeps its "mi" (میز),
 *    and the strip also offers the reading without the prefix (میوه).
 */
object PersianPhonetic {

    val TABLE = ArabicScriptPhonetic.Table(
        ye = "ی"[0],
        finalE = null,
        consonants = listOf(
            "kho" to "خو",
            "kh" to "خ",
            "x" to "خ",
            "gh" to "ق",
            "Gh" to "غ",
            "q" to "ق",
            "ch" to "چ",
            "sh" to "ش",
            "zh" to "ژ",
            "b" to "ب",
            "p" to "پ",
            "t" to "ت",
            "T" to "ط",
            "s" to "س",
            "S" to "ص",
            "j" to "ج",
            "h" to "ه",
            "H" to "ح",
            "d" to "د",
            "z" to "ز",
            "Z" to "ض",
            "r" to "ر",
            "f" to "ف",
            "k" to "ک",
            "g" to "گ",
            "l" to "ل",
            "m" to "م",
            "n" to "ن",
            "v" to "و",
            "w" to "و",
            "y" to "ی",
            "ci" to "سی",
            "ce" to "س",
            "c" to "ک",
            "'" to "ع",
        ),
        vowels = listOf(
            ArabicScriptPhonetic.Vowel("aa", "آ", "ا", "ا"),
            ArabicScriptPhonetic.Vowel("A", "آ", "ا", "ا"),
            ArabicScriptPhonetic.Vowel("a", "ا", "", "ا"),
            ArabicScriptPhonetic.Vowel("ee", "ای", "ی", "ی"),
            ArabicScriptPhonetic.Vowel("ii", "ای", "ی", "ی"),
            ArabicScriptPhonetic.Vowel("i", "ای", "ی", "ی"),
            ArabicScriptPhonetic.Vowel("iy", "ای", "ی", "ی"),
            ArabicScriptPhonetic.Vowel("ei", "ای", "ی", "ی"),
            ArabicScriptPhonetic.Vowel("ey", "ای", "ی", "ی"),
            ArabicScriptPhonetic.Vowel("ai", "ای", "ای", "ایی"),
            ArabicScriptPhonetic.Vowel("aei", "ای", "ای", "ایی"),
            ArabicScriptPhonetic.Vowel("oo", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("uu", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("u", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("ou", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("ow", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("au", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("e", "ا", "", "ه"),
            ArabicScriptPhonetic.Vowel("o", "ا", "", "و"),
        ),
        hamzaYe = "ی"[0],
        hamzaWao = "و"[0],
        prefixes = listOf("nemi" to "نمی\u200C", "mi" to "می\u200C"),
        prefixMinStem = 2,
    )

    private val engine = ArabicScriptPhonetic(TABLE)

    /** Transliterates Finglish — one word or free text — into the Persian script. */
    fun transliterate(input: String): String = engine.transliterate(input)

    /** Every reading of [input] worth offering, the literal one first. */
    fun variants(input: String): List<String> = engine.variants(input)
}
