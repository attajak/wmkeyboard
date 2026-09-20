package com.wasimaster.wmkeyboard.core.prediction

import java.io.InputStream

/**
 * Spellings that resolve to a fixed native-script form, for a phonetic input
 * mode — one map per language that has one ([PhoneticScheme.spellingAssets]).
 * The examples below are Avro's, which is where the map began.
 *
 * Two kinds of spelling need this, and neither is reachable from phonetic
 * rules:
 *
 *  - **English loanwords.** "keyboard" is written কিবোর্ড and "chair" চেয়ার by
 *    convention, not by sound-for-sound transliteration. Left to the rules,
 *    "table" would come out তাবলে rather than টেবিল.
 *  - **Romanized Bengali as people actually type it.** Chat spelling drops
 *    vowels wholesale — "tmr" for তোমার, "amk" for আমাকে, "trpr" for তারপর.
 *    No fold recovers a vowel that was never typed, so these are listed
 *    outright. Colloquial and regional inflections ("boltiso" → বলতিসো) are
 *    here for the same reason.
 *
 * The suggestion engine consults this before anything else, so a wrong entry
 * is worse than a missing one: it outranks both the dictionary and the literal
 * transliteration.
 *
 * The backing assets ([load]) are two-column TSV (`spelling<TAB>bengali`). A
 * single spelling may map to several Bengali forms (ki → কি / কী); they are
 * returned in file order, most-preferred first. Where two assets disagree the
 * earlier one leads, which is how the hand-written list stays ahead of the
 * generated one.
 */
class SpellingMap private constructor(
    private val byWord: Map<String, List<String>>,
    private val loanwords: Set<String> = emptySet(),
) {

    /** Bengali forms for [spelling], best first; empty if unmapped. */
    fun lookup(spelling: String): List<String> =
        byWord[spelling.trim().lowercase()].orEmpty()

    /** True when [spelling] has at least one mapped Bengali form. */
    fun contains(spelling: String): Boolean =
        byWord.containsKey(spelling.trim().lowercase())

    /**
     * Whether [spelling] came from a loanword list: the spelling is itself a
     * word of another language ("keyboard"), and the mapped form is only how
     * that word is written in this script. A listed romanization ("tmr") says
     * the typist meant this language; a listed loanword does not, which is what
     * a caller deciding between the two scripts needs to know.
     */
    fun isLoanword(spelling: String): Boolean =
        spelling.trim().lowercase() in loanwords

    val size: Int get() = byWord.size

    /** Every spelling this map knows, in file order — the curated romanized
     * vocabulary, which is also what a glide over Avro is decoded against. */
    val spellings: Set<String> get() = byWord.keys

    companion object {
        /** Empty map, used as the default when no asset is supplied (tests). */
        val EMPTY = SpellingMap(emptyMap())

        /**
         * Languages that ship a spelling map, by
         * [com.wasimaster.wmkeyboard.core.script.LanguageDef.id]. Only these
         * get the settings row — a language with no lists behind it would be
         * offering a switch that does nothing.
         */
        val LANGUAGES: Set<String> = PhoneticSchemes.all
            .filter { it.spellingAssets.isNotEmpty() }
            .mapTo(LinkedHashSet()) { it.languageId }

        /**
         * Parses `spelling<TAB>bengali` TSVs. Blank lines and `#` comments are
         * skipped; malformed lines (no tab, empty side) are ignored so the
         * assets survive hand editing. Duplicate keys accumulate their Bengali
         * forms in the order the streams are given, de-duplicated — so passing
         * the curated list first leaves it outranking the generated one.
         *
         * The first [loanwordStreams] of them are loanword lists (see
         * [isLoanword]); [PhoneticScheme.loanwordAssetCount] is how many of a
         * scheme's assets are.
         */
        fun load(vararg streams: InputStream, loanwordStreams: Int = 0): SpellingMap {
            val byWord = LinkedHashMap<String, MutableList<String>>()
            val loanwords = HashSet<String>()
            for ((position, stream) in streams.withIndex()) {
                stream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val tab = trimmed.indexOf('\t')
                        if (tab <= 0) continue
                        val spelling = trimmed.substring(0, tab).trim().lowercase()
                        val bengali = trimmed.substring(tab + 1).trim()
                        if (spelling.isEmpty() || bengali.isEmpty()) continue
                        val forms = byWord.getOrPut(spelling) { mutableListOf() }
                        if (bengali !in forms) forms.add(bengali)
                        if (position < loanwordStreams) loanwords.add(spelling)
                    }
                }
            }
            return SpellingMap(byWord, loanwords)
        }
    }
}
