package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Urdu written back in Latin letters: کرنا → karna, مرد → mard, بچہ → bacha.
 *
 * The inverse of [UrduPhonetic], and the hardest of the three romanizers here,
 * because Urdu's script does not write its short vowels at all. Going the other
 * way they have to be *invented*: دن is "din", پر is "par" and کل is "kal", and
 * nothing in the letters says which vowel belongs between the two consonants.
 * This writes an "a" wherever a vowel is needed to make the word sayable, which
 * is right more often than any other single guess and wrong often enough that
 * the two callers that matter put a dictionary in front of it — the romanized
 * word list for a swipe, and the spelling map inverted for the To roman Urdu
 * macro ([com.wasimaster.wmkeyboard.core.prediction.RomanizedConverter]). This
 * is the fallback for what neither of them has heard of.
 *
 * Long vowels are written the way chat spelling writes them: ا is "a", و is "o"
 * (or "w" where it opens a word or a syllable), ی is "i" ("y" opening one), ے
 * is "e", آ is "aa". Aspiration folds into the consonant before it (کھ is
 * "kh"), the nasalisation ں is "n", and the carriers ء ئ ؤ are silent — they
 * are orthography rather than sound. Output is always lower case; anything that
 * is not Urdu passes through untouched.
 */
object UrduRomanizer {

    private const val DO_HE = 'ھ'
    private const val GOL_HE = 'ہ'
    private const val ALIF = 'ا'
    private const val MADDA = 'آ'
    private const val WAO = 'و'
    private const val YE = 'ی'
    private const val BARI_YE = 'ے'

    private val CONSONANTS: Map<Char, String> = mapOf(
        'ب' to "b", 'پ' to "p", 'ت' to "t", 'ٹ' to "t", 'ث' to "s",
        'ج' to "j", 'چ' to "ch", 'ح' to "h", 'خ' to "kh", 'د' to "d",
        'ڈ' to "d", 'ذ' to "z", 'ر' to "r", 'ڑ' to "r", 'ز' to "z",
        'ژ' to "zh", 'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z",
        'ط' to "t", 'ظ' to "z", 'غ' to "gh", 'ف' to "f", 'ق' to "q",
        'ک' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n",
        'ں' to "n", GOL_HE to "h", 'ع' to "a",
    )

    /** The Arabic spellings of letters Urdu writes its own way, folded to Urdu's. */
    private val FOLD: Map<Char, Char> = mapOf(
        'ي' to YE, 'ى' to YE, 'ك' to 'ک', 'ه' to GOL_HE, 'ۃ' to GOL_HE,
        'ۂ' to GOL_HE, 'ة' to GOL_HE, 'أ' to ALIF, 'إ' to ALIF, 'ٱ' to ALIF,
        'ۓ' to BARI_YE, 'ۀ' to GOL_HE, 'ﮨ' to GOL_HE,
    )

    private const val ZWJ = '‍'
    private const val ZWNJ = '‌'

    /**
     * Urdu letters and the marks that spell a word with them — not its digits
     * (۰–۹ and ٠–٩), and not the full stop ۔ or the comma ،.
     */
    fun isUrdu(c: Char): Boolean = when (c.code) {
        in 0x0621..0x063A, in 0x0640..0x065F, 0x0670,
        in 0x0671..0x06D3, 0x06D5, in 0x06E5..0x06EF, in 0x06FA..0x06FF -> true
        else -> false
    }

    /**
     * The Arabic letter shapes folded to the Urdu ones the word lists use, the
     * vowel diacritics dropped, and the joiners with them.
     *
     * Text pasted from an Arabic or Persian keyboard — or from a web page set in
     * one — carries ي for ی, ك for ک and ه for ہ, which look the same and are
     * different code points, so a word spelled with them would be a word the
     * list has never seen. The harakat (zabar, zer, pesh, shadda, sukun) go for
     * the same reason: Urdu text normally carries none, and a word that carries
     * them has to fold onto the one that does not.
     */
    fun normalize(text: String): String {
        if (text.none { it in FOLD || isDiacritic(it) || it == ZWJ || it == ZWNJ }) return text
        val out = StringBuilder(text.length)
        for (c in text) {
            when {
                isDiacritic(c) || c == ZWJ || c == ZWNJ -> Unit
                else -> out.append(FOLD[c] ?: c)
            }
        }
        return out.toString()
    }

    private fun isDiacritic(c: Char): Boolean = c.code in 0x064B..0x0652 || c.code == 0x0670

    /** Whole text: every Urdu run becomes Latin, everything else passes through. */
    fun romanize(text: String): String {
        val norm = normalize(text)
        val out = StringBuilder(norm.length)
        var i = 0
        while (i < norm.length) {
            val c = norm[i]
            when {
                isUrdu(c) -> {
                    var j = i
                    while (j < norm.length && isUrdu(norm[j])) j++
                    out.append(word(norm.substring(i, j)))
                    i = j
                }
                c == '۔' -> { out.append('.'); i++ }
                c == '،' -> { out.append(','); i++ }
                c == '؟' -> { out.append('?'); i++ }
                c == '؛' -> { out.append(';'); i++ }
                c in '۰'..'۹' -> { out.append('0' + (c - '۰')); i++ }
                c in '٠'..'٩' -> { out.append('0' + (c - '٠')); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** One Urdu word, without spaces. */
    fun romanizeWord(word: String): String = word(normalize(word))

    private fun word(w: String): String {
        val out = StringBuilder(w.length * 2)
        var consonants = 0
        for ((i, c) in w.withIndex()) {
            val opening = i == 0
            val last = i == w.lastIndex
            val next = w.getOrNull(i + 1)
            when {
                c == DO_HE -> {
                    // Aspiration belongs to the consonant in front of it, which
                    // is already written: دیکھ is "dekh", not "dekhh".
                    if (out.isNotEmpty() && !out.endsWith("h")) out.append('h')
                }
                c == MADDA -> { out.append("aa"); consonants = 0 }
                c == ALIF -> {
                    // Opening the word it is whatever short vowel was meant, and
                    // "a" is the likeliest; inside it is the long one.
                    out.append('a')
                    consonants = 0
                }
                c == WAO -> {
                    // A consonant where it opens a word or follows another vowel
                    // (وہ is "wo", ہوا is "hua"), the vowel everywhere else.
                    out.append(if (opening) "w" else "o")
                    consonants = 0
                }
                c == YE -> { out.append(if (opening) "y" else "i"); consonants = 0 }
                c == BARI_YE -> { out.append('e'); consonants = 0 }
                c == 'ئ' || c == 'ء' -> Unit
                c == 'ؤ' -> { out.append('o'); consonants = 0 }
                c == GOL_HE && last && consonants > 0 -> {
                    // A closing gol he is the "a" of a Perso-Arabic noun: بچہ is
                    // "bacha", روزہ is "roza".
                    out.append('a')
                    consonants = 0
                }
                else -> {
                    val roman = CONSONANTS[c] ?: continue
                    // Two consonants with no vowel of any kind between them:
                    // Urdu left a short one out and it has to come back, or the
                    // spelling is not sayable — کرنا is "karna" and بچہ
                    // "bacha". Not before the word's last letter, where Urdu
                    // genuinely closes on a cluster ("mard", "dost").
                    val nextIsVowel = next != null && (next in VOWEL_LETTERS || next == DO_HE)
                    if (consonants > 0 && !last && !nextIsVowel) out.append('a')
                    out.append(roman)
                    consonants++
                }
            }
        }
        return out.toString()
    }

    private val VOWEL_LETTERS = charArrayOf(ALIF, MADDA, WAO, YE, BARI_YE, 'ئ', 'ؤ', 'ء')
}
