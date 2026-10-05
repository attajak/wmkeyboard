package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Kannada phonetic: roman letters in, Kannada script out, on [IndicPhonetic]'s
 * EXPLICIT model. Every vowel is typed, short e/o (ಎ ಒ) are one letter and the
 * long ones (ಏ ಓ) a capital, and a consonant that closes a word takes a virama.
 *
 * Two spellings differ from the generic table. Kannada chat writes the dental
 * ತ as "th" — thumba ತುಂಬ, gothu, maathu ಮಾತು — so "th" is ತ, not ಥ: the
 * aspirate is 6% of the t-family letters in the word list. And "L" is ಳ, "R"
 * ಱ, as everywhere else.
 *
 * On top of that, [hook] rewrites the tokens of each word by the language's own
 * positional rules. Each was measured on the word list (top 100k words,
 * frequency-weighted) and kept only where it paid:
 *
 *  1. **"ye" opening a word is ಎ/ಏ.** Chat spells the glide Kannada says there
 *     (yenu ಏನು, yelli ಎಲ್ಲಿ). A word-initial ಯೆ/ಯೇ is 0.4% of the e-initial
 *     words (ಯೇಸು, ಯೆಹೂದಿ). Three letters at least, so "ye" alone stays ಯೆ.
 *  2. **"ow" before a consonant is ಔ** (howdu ಹೌದು, gowda ಗೌಡ): ಒವ್ plus a
 *     consonant does not occur in the list.
 *  3. **"sht" is ಷ್ಟ, "shth" ಷ್ಠ.** ಶ never takes a retroflex stop and ಷ is
 *     followed only by one: ಷ್ಟ 26k against 37 for ಶ್ಟ and ಷ್ತ together.
 *  4. **The ಆಗು/ಆಗಿ verb.** "agi" is ಆಗಿ everywhere (ಚೆನ್ನಾಗಿ, ಆಗಿದೆ, the -ವಾಗಿ
 *     adverbs: 95k against 7k), "agu" past the first syllable (ಆಗುತ್ತೆ,
 *     -ಲಾಗುತ್ತದೆ: 20k against 1.3k) — but not at the end of a word, where only
 *     the short a occurs (ಮಲಗು, ಹಡಗು, ಕೊಡಗು).
 *  5. **The plural "-galu" is ಗಳು**: 55k against 1k for ಗಲು (ಹೋಗಲು, ಹಗಲು).
 *  6. **A final "ona" past the first syllable is the hortative -ಓಣ** (hogona
 *     ಹೋಗೋಣ, madona ಮಾಡೋಣ, nodona ನೋಡೋಣ).
 *  7. **Past the first syllable, o is long ಓ** — 81–90% in the list: ಹೋಗೋಣ,
 *     ಶುಭೋದಯ, ಸಂತೋಷ — unless a geminate or a nasal cluster follows
 *     (ಮಾಡಿಕೊಳ್ಳಿ, ತೆಗೆದುಕೊಂಡು: 95–100% short).
 *  8. **In a two-syllable word, e before a single consonant is long ಏ** — 88%
 *     in the list: ಬೇಕು, ಬೇಡ, ಹೇಳು, ಮೇಲೆ, ಏನು; ಬೆಲೆ and ಎಲೆ are the exceptions.
 *     Longer words keep the short ಎ, which wins there 56–61% (ಹೆಸರು, ಕೆಲಸ).
 *  9. **l after a short o is ಳ**: ಒಳಗೆ, ಒಳ್ಳೆ, -ಕೊಳ್ಳಿ (83% for a single l, 94%
 *     for a doubled one).
 * 10. **A single d between vowels before i is ಡ** (ಮಾಡಿ, ನೋಡಿ, ನೀಡಿ, ಕೊಡಿ,
 *     ಅಂಗಡಿ: 68–91%), except after a short u or e, where ದ still wins
 *     (-ುದಿಲ್ಲ, ಪಡೆದಿದೆ). After a typed long ee/oo it is ಡ before any vowel
 *     (ನೀಡು, ಕೂಡ: 90–93%).
 * 11. **A final "dhu"/"dhe" is ದು/ದೆ.** Chat writes the plain ದ as dh (idhu,
 *     adhu, idhe), and ಧು/ಧೆ end 0.2% as many words as ದು/ದೆ do.
 * 12. **The spoken first person -ತೀನಿ / -ದ್ದೀನಿ**: a final "ini" after a
 *     cluster ending in t, th or d (bartini ಬರ್ತೀನಿ, madtini, iddini) has the
 *     long ī.
 *
 * The rules run in that order, and later ones read what earlier ones wrote:
 * every replacement keeps the roman spelling it replaced ("l" for ಳ, "d" for
 * ಡ), so rule 9 still sees a doubled l and rule 12 still sees a d.
 */
object KannadaPhonetic {

    private val AA = IndicPhonetic.Rule("aa", "आ", "ा")
    private val LONG_E = IndicPhonetic.Rule("E", "ए", "े")
    private val LONG_O = IndicPhonetic.Rule("O", "ओ", "ो")
    private val AU = IndicPhonetic.Rule("au", "औ", "ौ")
    private val LONG_I = IndicPhonetic.Rule("ee", "ई", "ी")

    /** ಷ */
    private val SSA = IndicPhonetic.Rule("sh", "ष")

    /** ಟ */
    private val TTA = IndicPhonetic.Rule("t", "ट")

    /** ಠ */
    private val TTHA = IndicPhonetic.Rule("th", "ठ")

    /** ಳ */
    private val LLA = IndicPhonetic.Rule("l", "ळ")

    /** ಣ */
    private val NNA = IndicPhonetic.Rule("n", "ण")

    /** ಡ */
    private val DDA = IndicPhonetic.Rule("d", "ड")

    /** ದ */
    private val DA = IndicPhonetic.Rule("dh", "द")

    /** Typed long vowels, after which a single d is ಡ before any vowel (rule 10). */
    private val TYPED_LONG = setOf("ee", "ii", "oo", "uu")

    val PROFILE = IndicProfile(
        languageId = "kn",
        script = IndicScript.KANNADA,
        model = IndicProfile.Model.EXPLICIT,
        dravidianVowels = true,
        extraConsonants = listOf("L" to "ळ", "R" to "ऱ", "th" to "त"),
        finalVirama = true,
        wordHook = ::hook,
    )

    @Suppress("UNUSED_PARAMETER")
    private fun hook(engine: IndicPhonetic, tokens: List<IndicPhonetic.Rule>): List<IndicPhonetic.Rule> {
        val t = tokens.toMutableList()
        // 1. "ye" opening a word is ಎ/ಏ.
        if (t.size >= 3 && t[0].match == "y" && (t[1].match == "e" || t[1].match == "E")) {
            t.removeAt(0)
        }
        // 2. "ow" before a consonant is ಔ. The list shrinks as it goes, so the
        //    bound is re-read every step.
        var i = 0
        while (i + 2 < t.size) {
            if (t[i].match == "o" && (t[i + 1].match == "w" || t[i + 1].match == "v") && isC(t[i + 2])) {
                t.removeAt(i + 1)
                t[i] = AU
            }
            i++
        }
        val n = t.size
        // 3. "sht" is ಷ್ಟ, "shth" ಷ್ಠ.
        for (k in 0 until n - 1) {
            if (t[k].match == "sh" && (t[k + 1].match == "t" || t[k + 1].match == "th")) {
                t[k] = SSA
                t[k + 1] = if (t[k + 1].match == "t") TTA else TTHA
            }
        }
        // Vowel positions, for the syllable-based rules below.
        val vows = (0 until n).filter { isV(t[it]) }
        // 4. "agi" is ಆಗಿ everywhere, "agu" ಆಗು past the first syllable and
        //    short of the end.
        for ((k, v) in vows.withIndex()) {
            if (t[v].match == "a" && v + 2 < n && t[v + 1].match == "g" &&
                (t[v + 2].match == "i" || t[v + 2].match == "u")
            ) {
                if (t[v + 2].match == "i" || (k > 0 && v + 3 < n)) t[v] = AA
            }
        }
        // 5. The plural "-galu" is ಗಳು.
        if (n >= 5 && endsWith(t, "g", "a", "l", "u")) t[n - 2] = LLA
        // 6. A final "ona" past the first syllable is the hortative -ಓಣ.
        if (vows.size >= 3 && endsWith(t, "o", "n", "a")) t[n - 2] = NNA
        // 7. Past the first syllable, o is long ಓ, unless a geminate or a nasal
        //    cluster follows.
        for ((k, v) in vows.withIndex()) {
            if (k == 0 || t[v].match != "o") continue
            val a = t.getOrNull(v + 1)
            val b = t.getOrNull(v + 2)
            if (a != null && b != null && isC(a) && isC(b) &&
                (a.full == b.full || a.match == "n" || a.match == "m" || a.match == "N")
            ) {
                continue
            }
            t[v] = LONG_O
        }
        // 8. In a two-syllable word, e before a single consonant is long ಏ.
        if (vows.size == 2 && vows[1] == n - 1) {
            val e = vows[0]
            if (t[e].match == "e" && vows[1] == e + 2 && isC(t[e + 1])) t[e] = LONG_E
        }
        // 9. l after a short o is ಳ, single or doubled.
        for (k in 1 until n) {
            if (t[k].match == "l" && (t[k - 1].match == "o" || t[k - 1].match == "l")) {
                val o = if (t[k - 1].match == "o") k - 1 else k - 2
                if (o >= 0 && t[o].match == "o" && (o == k - 1 || t[o + 1].match == "l")) t[k] = LLA
            }
        }
        // 10. A single d between vowels is ಡ before i (not after a short u or
        //     e), and before any vowel after a typed long ee/oo.
        for (k in 1 until n - 1) {
            if (t[k].match != "d" || !isV(t[k - 1]) || !isV(t[k + 1])) continue
            val pv = t[k - 1].match.lowercase()
            if (pv in TYPED_LONG || (t[k + 1].match == "i" && pv != "u" && pv != "e")) t[k] = DDA
        }
        // 11. A final "dhu"/"dhe" is ದು/ದೆ.
        if (n >= 3 && t[n - 2].match == "dh" && (t[n - 1].match == "u" || t[n - 1].match == "e")) {
            t[n - 2] = DA
        }
        // 12. The spoken first person -ತೀನಿ / -ದ್ದೀನಿ.
        if (n >= 5 && endsWith(t, "i", "n", "i") &&
            (t[n - 4].match == "t" || t[n - 4].match == "th" || t[n - 4].match == "d") && isC(t[n - 5])
        ) {
            t[n - 3] = LONG_I
        }
        return t
    }

    private fun isC(rule: IndicPhonetic.Rule): Boolean = rule.kind == IndicPhonetic.Kind.CONSONANT

    private fun isV(rule: IndicPhonetic.Rule): Boolean = rule.kind == IndicPhonetic.Kind.VOWEL

    /** Whether the tokens end in exactly these roman spellings. */
    private fun endsWith(tokens: List<IndicPhonetic.Rule>, vararg matches: String): Boolean {
        if (tokens.size < matches.size) return false
        val from = tokens.size - matches.size
        return matches.indices.all { tokens[from + it].match == matches[it] }
    }
}
