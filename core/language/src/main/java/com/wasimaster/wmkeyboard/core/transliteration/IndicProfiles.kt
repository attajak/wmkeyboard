package com.wasimaster.wmkeyboard.core.transliteration

/**
 * The [IndicPhonetic] languages, and what each one's word-list index needs
 * beyond the shared Hindi fold ([IndicPhoneticIndex]).
 *
 * Every number quoted here was measured over that language's downloadable word
 * list against a hand-written set of about 200 chat spellings, top-1 lookups:
 * Marathi 90%, Gujarati 82%, Punjabi 75%, Telugu 78%, Kannada 60%, Malayalam
 * 56%, Odia 54%, Tamil 48%.
 */
object IndicProfiles {

    val all: List<IndicProfile> = listOf(
        MarathiPhonetic.PROFILE,
        GujaratiPhonetic.PROFILE,
        PunjabiPhonetic.PROFILE,
        OdiaPhonetic.PROFILE,
        TamilPhonetic.PROFILE,
        TeluguPhonetic.PROFILE,
        KannadaPhonetic.PROFILE,
        MalayalamPhonetic.PROFILE,
    )

    fun forLanguage(languageId: String): IndicProfile? = all.firstOrNull { it.languageId == languageId }

    /**
     * The language's romanization rewritten into what the Hindi fold reads,
     * before an index lookup.
     *
     *  - **The Dravidian four write "th" and "dh" for the plain dentals** (Telugu
     *    "thammudu" తమ్ముడు, Tamil "andha" அந்த), where the Hindi fold reads an
     *    aspirate. Telugu: 68% → 78% top-1.
     *  - **Telugu, Kannada and Malayalam close a word on ం / ಂ / ം** where roman
     *    writes "m" ("manam" మనం); the fold reads an "M" as that sign.
     *  - **Tamil writes no voiced stops**, so g d b ask for the k t p that கங்கா
     *    folds to; s is ச; "zh" is ழ, which the fold files under l.
     *  - **Malayalam** is the same and more: "nj" ഞ and "ng" ങ്ങ are one nasal,
     *    "nt" is ന്റ (n and r to the fold), and its chat spells the intervocalic
     *    ട as d ("evide" എവിടെ), so voicing is folded on both sides (see
     *    [devanagariPrep]). 27% → 56% top-1.
     */
    fun romanPrep(languageId: String): (String) -> String = when (languageId) {
        "ta" -> { input -> tamil(dravidianDentals(input)) }
        "te", "kn" -> { input -> closingM(dravidianDentals(input)) }
        // In this order: "nth" has to be read as ന്ത before "th" becomes "t".
        "ml" -> { input -> unvoiced(closingM(dravidianDentals(malayalamNasals(input)))) }
        else -> { input -> input }
    }

    /**
     * The list's words, in Devanagari, rewritten the same way as [romanPrep]
     * rewrites what is typed: Malayalam's voiced stops onto the unvoiced ones,
     * since its chat writes either for either.
     */
    fun devanagariPrep(languageId: String): (String) -> String = when (languageId) {
        "ml" -> { dev -> buildString(dev.length) { for (c in dev) append(UNVOICED[c] ?: c) } }
        else -> { dev -> dev }
    }

    /**
     * Whether the index should break ties by closeness to what the rules
     * themselves spell: for a list with no frequencies to break them (Tamil's,
     * 1.9 million words at frequency 1, which put ஞாந ahead of நான் for "naan").
     * Tamil: 19% → 48% top-1. A list with real counts does better without it.
     */
    fun rerankFlatList(languageId: String): Boolean = languageId == "ta"

    private val UNVOICED: Map<Char, Char> = mapOf(
        'ग' to 'क', 'घ' to 'ख', 'द' to 'त', 'ध' to 'थ',
        'ड' to 'ट', 'ढ' to 'ठ', 'ब' to 'प', 'भ' to 'फ',
    )

    private fun dravidianDentals(input: String): String = input.replace("th", "t").replace("dh", "d")

    private fun closingM(input: String): String = if (input.endsWith("m")) input.dropLast(1) + "M" else input

    private val MALAYALAM_NT = Regex("nt(?!h)")

    private fun malayalamNasals(input: String): String =
        input.replace("zh", "l").replace("nj", "n").replace("ng", "n").replace(MALAYALAM_NT, "nr")

    private fun unvoiced(input: String): String = input.replace('d', 't').replace('g', 'k').replace('b', 'p')

    private fun tamil(input: String): String {
        val out = StringBuilder(input.length + 2)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val next = input.getOrNull(i + 1)
            when {
                c == 'z' && next == 'h' -> { out.append('l'); i += 2; continue }
                c == 'n' && next == 'j' -> { out.append('n'); i += 2; continue }
                c == 's' && next != 'h' -> out.append("ch")
                c == 'g' -> out.append('k')
                c == 'd' -> out.append('t')
                c == 'b' -> out.append('p')
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
