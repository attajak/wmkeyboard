package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Arabic phonetic: Arabizi in, Arabic script out — "7abibi", "3arabi",
 * "el7amdulillah" → حبيبي, عربي, الحمدلله — on the walk [ArabicScriptPhonetic]
 * shares with Urdu and Persian.
 *
 * What is Arabic's own, as measured against a 200-word set of everyday MSA,
 * Egyptian, Levantine and Gulf words (45.5% exact by the rules alone, 78% with
 * the strip's other readings; the curated map and the 2.5-million-word list
 * take it from there):
 *
 *  - **its letters**: ي ك ه ة ى, never the Urdu or Persian shapes.
 *  - **digits for the letters roman lacks**: 3 ع, 3' غ, 5 خ, 6 ط, 6' ظ, 7 ح,
 *    8 ق, 9 ص, 9' ض — and **2 is ق**, not the hamza of most charts: the people
 *    who type 2 most are Egyptian and Levantine, and the glottal stop they mean
 *    is their ق (2albi قلبي, wa2t وقت). The real hamza words people type with
 *    a 2 (la2 لأ, so2al سؤال) are few and in the spelling map. A digit opens a
 *    word as readily as it sits inside one ("3ala"), and a run of digits alone
 *    stays a number.
 *  - **the article**: "al", "el" or "il" before a consonant writes ال, and an
 *    opening vowel before a doubled sun letter is the article assimilated
 *    ("essalam" السلام). With the article its own case, a word-initial a, u or
 *    o can be the hamza alif أ (ana أنا, ukhti أختي) without turning every
 *    "al…" into أل.
 *  - **"g" is ج** (Egyptian), **"ch" is ش** (Levantine and Maghrebi); the Gulf
 *    g = ق words are in the map.
 *  - **a closing "a" is ا**, with ة and ى offered beside it, and a closing "e"
 *    is ة (madrase مدرسة); the strip also offers inner i/e as ي and u/o as و,
 *    since Arabizi writes long and short vowels alike.
 */
object ArabicPhonetic {

    val TABLE = ArabicScriptPhonetic.Table(
        ye = "ي"[0],
        finalE = null,
        consonants = listOf(
            "3'" to "غ",
            "6'" to "ظ",
            "9'" to "ض",
            "2" to "ق",
            "3" to "ع",
            "5" to "خ",
            "6" to "ط",
            "7" to "ح",
            "8" to "ق",
            "9" to "ص",
            "kh" to "خ",
            "gh" to "غ",
            "sh" to "ش",
            "ch" to "ش",
            "th" to "ث",
            "dh" to "ذ",
            "b" to "ب",
            "p" to "ب",
            "t" to "ت",
            "T" to "ط",
            "s" to "س",
            "S" to "ص",
            "j" to "ج",
            "g" to "ج",
            "h" to "ه",
            "H" to "ح",
            "x" to "خ",
            "d" to "د",
            "D" to "ض",
            "z" to "ز",
            "Z" to "ظ",
            "r" to "ر",
            "f" to "ف",
            "v" to "ف",
            "q" to "ق",
            "k" to "ك",
            "l" to "ل",
            "m" to "م",
            "n" to "ن",
            "w" to "و",
            "y" to "ي",
            "c" to "ك",
            "'" to "ع",
        ),
        vowels = listOf(
            ArabicScriptPhonetic.Vowel("aa", "آ", "ا", "ا"),
            ArabicScriptPhonetic.Vowel("A", "آ", "ا", "ا"),
            ArabicScriptPhonetic.Vowel("a", "أ", "", "ا"),
            ArabicScriptPhonetic.Vowel("ee", "اي", "ي", "ي"),
            ArabicScriptPhonetic.Vowel("ii", "اي", "ي", "ي"),
            ArabicScriptPhonetic.Vowel("i", "ا", "", "ي"),
            ArabicScriptPhonetic.Vowel("ai", "اي", "ي", "ي"),
            ArabicScriptPhonetic.Vowel("ei", "اي", "ي", "ي"),
            ArabicScriptPhonetic.Vowel("oo", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("uu", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("u", "أ", "", "و"),
            ArabicScriptPhonetic.Vowel("au", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("ou", "او", "و", "و"),
            ArabicScriptPhonetic.Vowel("e", "ا", "", "ة"),
            ArabicScriptPhonetic.Vowel("o", "أ", "", "و"),
        ),
        digits = "2356789",
        finalAVariants = listOf("ة", "ى", ""),
        article = listOf("al", "el", "il"),
        sun = listOf("t", "th", "d", "dh", "r", "z", "s", "sh", "9", "9'", "6", "6'", "l"),
        longVowels = mapOf("i" to "ي", "e" to "ي", "u" to "و", "o" to "و"),
    )

    private val engine = ArabicScriptPhonetic(TABLE)

    /** Transliterates Arabizi — one word or free text — into the Arabic script. */
    fun transliterate(input: String): String = engine.transliterate(input)

    /** Every reading of [input] worth offering, the literal one first. */
    fun variants(input: String): List<String> = engine.variants(input)
}
