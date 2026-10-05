package com.wasimaster.wmkeyboard.core.transliteration

/**
 * The walk shared by every roman-to-Arabic-script transliterator here —
 * [UrduPhonetic], [PersianPhonetic], [ArabicPhonetic] — each of which is only a
 * [Table]: its own letters, its own consonants and vowels.
 *
 * What the three have in common is what the script does, not what the
 * languages do:
 *
 *  - **short vowels are not written** inside a word, because the script marks
 *    them with diacritics nobody types; a long vowel is a letter (ا و ی);
 *  - **a word-initial vowel takes its carrier** (ا, آ) and a word-final one is
 *    always a letter, because a word cannot end on a mark;
 *  - **a vowel letter after a vowel letter takes a hamza** carrier (ئ before
 *    the ye, ؤ for a و after ا);
 *  - **a doubled consonant is written once** — the shadda is not typed and the
 *    word lists do not carry it;
 *  - **a ی or و that is a consonant** ("yeh", "woh") and the vowel after it
 *    are one letter, not two.
 *
 * Only runs of roman letters (and, for Arabizi, the digits that stand for
 * letters) are read; everything else passes through. A run of digits on its
 * own stays a number even where digits spell letters: "2024" is a year, "3ala"
 * is على.
 *
 * Measured over Urdu, this reproduces the rules [UrduPhonetic] shipped with
 * character for character; Persian and Arabic are the same walk over other
 * tables.
 */
class ArabicScriptPhonetic(private val table: Table) {

    /**
     * One language's letters.
     *
     * @param ye the ye the language writes: Urdu and Persian ی, Arabic ي
     * @param finalE a letter that spells a closing "e" and takes a hamza after a
     *        vowel, as Urdu's ے does (گئے); null where there is none
     * @param consonants roman token to what it writes
     * @param vowels roman token to its opening, inner and closing spellings
     * @param khInitial what "kh" writes when it opens a word, where that differs
     *        from what it writes elsewhere (Urdu: خ, against کھ inside a word)
     * @param digits the digits that spell letters (Arabizi's 2 3 5 6 7 8 9), so
     *        they belong to the word they sit in
     * @param finalAVariants other spellings of a closing "a" the strip offers:
     *        Arabic's ة and ى, which no roman spelling tells apart from ا
     * @param hamzaYe what a vowel leaning on a vowel is carried by before a ye:
     *        ئ in Urdu and Arabic, a plain ی in Persian (کجایی, پاییز)
     * @param hamzaWao the same before a و after ا: ؤ, or Persian's plain و
     * @param prefixes word-initial prefixes written as a unit of their own,
     *        joined to the stem by a ZWNJ: Persian's verb prefixes می‌ and نمی‌
     *        (می‌خوام). Only split off a stem of at least [prefixMinStem] tokens,
     *        so "miz" stays میز
     * @param article spellings of the definite article ("al", "el", "il") that
     *        write [articleOut] when a consonant follows, and [sun], the letters
     *        whose doubling after an opening vowel is the article assimilated
     *        ("essalam" is السلام)
     * @param longVowels inner short vowels the strip may also offer long, and
     *        as what: Arabic's i/e as ي and u/o as و (كبير, يوم)
     */
    class Table(
        val ye: Char,
        val finalE: Char?,
        val consonants: List<Pair<String, String>>,
        val vowels: List<Vowel>,
        val khInitial: Char? = null,
        val digits: String = "",
        val finalAVariants: List<String> = emptyList(),
        val hamzaYe: Char = HAMZA_YE,
        val hamzaWao: Char = HAMZA_WAO,
        val prefixes: List<Pair<String, String>> = emptyList(),
        val prefixMinStem: Int = 2,
        val article: List<String> = emptyList(),
        val articleOut: String = "\u0627\u0644",
        val sun: List<String> = emptyList(),
        val longVowels: Map<String, String> = emptyMap(),
    )

    /** A vowel's spelling where it opens, sits inside and closes a word. */
    class Vowel(val match: String, val initial: String, val medial: String, val final: String)

    private class Rule(val match: String, val out: String = "", val forms: Vowel? = null) {
        val isVowel: Boolean get() = forms != null
    }

    private val rules: List<Rule> = (
        table.consonants.map { (match, out) -> Rule(match, out) } +
            table.vowels.map { Rule(it.match, forms = it) }
        ).sortedByDescending { it.match.length }

    /** Bucketed by first char and still length-descending, as in [AvroPhonetic]. */
    private val rulesByFirstChar: Map<Char, List<Rule>> = rules.groupBy { it.match[0] }

    /** The letters a following vowel cannot simply sit next to. */
    private val vowelLetters: Set<Char> = buildSet {
        add(ALIF); add(MADDA); add(WAO); add(table.ye); add(table.hamzaYe); add(table.hamzaWao)
        table.finalE?.let { add(it) }
    }

    /** Transliterates roman text — one word or a sentence — into the script. */
    fun transliterate(input: String): String = render(input, Options())

    /**
     * Every reading of [input] worth offering, the literal one first: the
     * spelling with its last, every or first inner "a" long; then the table's
     * other closing spellings of an "a", alone and with the long-a readings;
     * then the inner vowels read long; and last, for a table with prefixes, the
     * readings that do not split one off (میوه, not می‌وه).
     */
    fun variants(input: String): List<String> {
        val out = LinkedHashSet<String>()
        val readings = listOf(LongA.NONE, LongA.LAST, LongA.ALL, LongA.FIRST)
        for (longA in readings) out.add(render(input, Options(longA = longA)))
        for (closing in table.finalAVariants) out.add(render(input, Options(finalA = closing)))
        for (longA in listOf(LongA.LAST, LongA.ALL)) {
            for (closing in table.finalAVariants) out.add(render(input, Options(longA = longA, finalA = closing)))
        }
        if (table.longVowels.isNotEmpty()) {
            out.add(render(input, Options(longInner = true)))
            table.finalAVariants.firstOrNull()?.let { out.add(render(input, Options(longInner = true, finalA = it))) }
        }
        if (table.prefixes.isNotEmpty()) {
            for (longA in readings) out.add(render(input, Options(longA = longA, prefix = false)))
        }
        return out.toList()
    }

    /** One way of reading a word; [variants] walks through several. */
    private class Options(
        val longA: LongA = LongA.NONE,
        val finalA: String? = null,
        val longInner: Boolean = false,
        val prefix: Boolean = true,
    )

    /** Which inner "a"s are read as a long ا; see [UrduPhonetic]'s LongA. */
    private enum class LongA { NONE, LAST, FIRST, ALL }

    private fun isWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '\'' || c in table.digits

    private fun render(input: String, options: Options): String {
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (isWordChar(input[i])) {
                var j = i
                while (j < input.length && isWordChar(input[j])) j++
                val run = input.substring(i, j)
                // A number is a number, even where digits spell letters.
                if (run.all { it.isDigit() }) out.append(run) else word(run, options, out)
                i = j
            } else {
                out.append(input[i])
                i++
            }
        }
        return out.toString()
    }

    private fun exactRuleAt(input: String, at: Int): Rule? =
        rulesByFirstChar[input[at]]?.firstOrNull { input.startsWith(it.match, at) }

    /**
     * [exactRuleAt], falling back to the lowercase reading of a capital that
     * spells nothing of its own — caps lock, or a shift the key before latched.
     */
    private fun ruleAt(input: String, at: Int): Rule? {
        exactRuleAt(input, at)?.let { return it }
        if (input[at] !in 'A'..'Z') return null
        return exactRuleAt(input.substring(at).lowercase(), 0)
    }

    private fun tokenize(word: String): List<Rule> {
        val tokens = ArrayList<Rule>(word.length)
        var i = 0
        while (i < word.length) {
            val rule = ruleAt(word, i)
            if (rule == null) {
                // A character no rule reads still has to advance the walk.
                tokens.add(Rule(word[i].toString(), word[i].toString()))
                i++
            } else {
                tokens.add(rule)
                i += rule.match.length
            }
        }
        return tokens
    }

    /**
     * The prefix [word] opens with and the stem after it, when a table prefix
     * leaves a stem long enough to be a word of its own; else null.
     */
    private fun splitPrefix(word: String): Pair<String, String>? {
        val lower = word.lowercase()
        for ((roman, native) in table.prefixes) {
            if (!lower.startsWith(roman)) continue
            val stem = word.substring(roman.length)
            if (stem.isNotEmpty() && tokenize(stem).size >= table.prefixMinStem) return native to stem
        }
        return null
    }

    /**
     * The article [word] opens with and the rest of it, or null: "al", "el" or
     * "il" before a consonant, or an opening vowel and a doubled sun letter
     * ("essalam", the article assimilated to the s).
     */
    private fun splitArticle(word: String): Pair<String, String>? {
        val lower = word.lowercase()
        for (spelling in table.article) {
            if (lower.startsWith(spelling) && word.length > spelling.length + 1) {
                val next = ruleAt(word, spelling.length)
                if (next != null && !next.isVowel) return table.articleOut to word.substring(spelling.length)
            }
        }
        if (table.sun.isNotEmpty() && word.length > 4 && lower[0] in "aei") {
            for (letter in table.sun) {
                if (lower.startsWith(letter + letter, 1) && word.length > 1 + 2 * letter.length + 1) {
                    return table.articleOut to word.substring(1 + letter.length)
                }
            }
        }
        return null
    }

    private fun word(whole: String, options: Options, out: StringBuilder) {
        val start = out.length
        var word = whole
        if (options.prefix) {
            splitPrefix(word)?.let { (prefix, stem) ->
                out.append(prefix)
                // The stem is a word of its own after the prefix's ZWNJ.
                word(stem, Options(options.longA, options.finalA, options.longInner, prefix = false), out)
                return
            }
        }
        splitArticle(word)?.let { (article, rest) ->
            out.append(article)
            word = rest
        }
        val tokens = tokenize(word)
        val inner = tokens.indices.filter { i -> tokens[i].match == "a" && i != 0 && i != tokens.lastIndex }
        val long: Set<Int> = when {
            inner.isEmpty() -> emptySet()
            options.longA == LongA.ALL -> inner.toSet()
            options.longA == LongA.LAST -> setOf(inner.last())
            options.longA == LongA.FIRST -> setOf(inner.first())
            else -> emptySet()
        }
        val longInner: Set<Int> = if (options.longInner) {
            tokens.indices.filter { i -> i != 0 && i != tokens.lastIndex && tokens[i].isVowel && tokens[i].match in table.longVowels }.toSet()
        } else {
            emptySet()
        }
        val finalA = options.finalA
        var prevWasVowel = false
        for ((index, token) in tokens.withIndex()) {
            if (!token.isVowel) {
                // The shadda nobody types: one letter, not two.
                val written = out.length - start
                if (!prevWasVowel && written >= token.out.length && out.endsWith(token.out)) continue
                val kh = table.khInitial
                if (kh != null && out.length == start && token.match == "kh") out.append(kh) else out.append(token.out)
                prevWasVowel = false
                continue
            }
            val forms = token.forms!!
            val opening = out.length == start
            var form = when {
                opening -> forms.initial
                index == tokens.lastIndex -> if (token.match == "a" && finalA != null) finalA else forms.final
                index in long -> ALIF.toString()
                index in longInner -> table.longVowels.getValue(token.match)
                else -> forms.medial
            }
            if (form.isNotEmpty() && !opening) {
                // A vowel cannot lean on another vowel, so it takes a hamza
                // carrier. Only after a vowel: the ی of "yar" and the و of
                // "wada" are consonants, and nothing hangs off them.
                if (prevWasVowel && out.last() in vowelLetters) {
                    form = when {
                        form[0] == WAO && (out.last() == ALIF || out.last() == MADDA) -> table.hamzaWao + form.substring(1)
                        form[0] == table.ye || form[0] == table.finalE -> table.hamzaYe + form
                        else -> form
                    }
                }
                // One letter, not two: the consonant and the vowel written with
                // the same letter ("yeh", "woh").
                if (form.length == 1 && out.last() == form[0]) form = ""
            }
            out.append(form)
            prevWasVowel = true
        }
    }

    companion object {
        const val ALIF = 'ا'
        const val MADDA = 'آ'
        const val WAO = 'و'
        const val HAMZA_YE = 'ئ'
        const val HAMZA_WAO = 'ؤ'
    }
}
