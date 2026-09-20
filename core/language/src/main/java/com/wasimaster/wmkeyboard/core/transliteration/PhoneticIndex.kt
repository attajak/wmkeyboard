package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Reverse-phonetic lookup from a romanized spelling to the dictionary words it
 * could stand for, for a layout whose keys are Latin and whose text is not.
 *
 * Romanized spelling is loose in every language that has one, so an index
 * folds both sides — what was typed, and each dictionary word — to a lenient
 * key where the confusable sounds collapse, and words sharing a key are
 * siblings. How the fold works is the language's own business
 * ([BengaliPhoneticIndex], [HindiPhoneticIndex]); what the suggestion engine
 * needs from it is only this.
 */
interface PhoneticIndex {

    /** Dictionary words phonetically matching the romanized [input], best first. */
    fun lookup(input: String): List<String>

    /** Dictionary frequency of a native-script [word], 0 when unknown. */
    fun frequencyOf(word: String): Int

    /** Whether there is no word behind this index at all — no list installed. */
    val isEmpty: Boolean

    companion object {
        /** No dictionary: every lookup misses, and the rules stand alone. */
        val EMPTY: PhoneticIndex = object : PhoneticIndex {
            override fun lookup(input: String): List<String> = emptyList()
            override fun frequencyOf(word: String): Int = 0
            override val isEmpty: Boolean get() = true
        }
    }
}
