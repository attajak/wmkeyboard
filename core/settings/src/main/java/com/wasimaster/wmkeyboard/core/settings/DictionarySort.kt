package com.wasimaster.wmkeyboard.core.settings

import java.text.Collator

/** What the personal dictionary screen orders its words by (#194). */
enum class DictionarySortKey { WEIGHT, NAME, ADDED }

/**
 * The personal dictionary screen's order (#194): a [key] and a direction.
 * The first constant of each key is the direction selecting that key starts
 * in; [reversed] flips it.
 */
enum class DictionarySort(val key: DictionarySortKey) {
    MOST_USED_FIRST(DictionarySortKey.WEIGHT),
    LEAST_USED_FIRST(DictionarySortKey.WEIGHT),
    A_TO_Z(DictionarySortKey.NAME),
    Z_TO_A(DictionarySortKey.NAME),
    NEWEST_FIRST(DictionarySortKey.ADDED),
    OLDEST_FIRST(DictionarySortKey.ADDED),
    ;

    /** The same key, the other way round. */
    fun reversed(): DictionarySort = when (this) {
        MOST_USED_FIRST -> LEAST_USED_FIRST
        LEAST_USED_FIRST -> MOST_USED_FIRST
        A_TO_Z -> Z_TO_A
        Z_TO_A -> A_TO_Z
        NEWEST_FIRST -> OLDEST_FIRST
        OLDEST_FIRST -> NEWEST_FIRST
    }

    companion object {
        /** The direction [key] starts in. */
        fun of(key: DictionarySortKey): DictionarySort = entries.first { it.key == key }
    }
}

/**
 * [words] (spelling to weight) in [sort]'s order. [added] maps a spelling to
 * when it joined the dictionary, larger being newer; a word missing from it
 * sorts as the oldest.
 *
 * Both alphabetical orders put words that start with a capital letter first,
 * so names and other proper nouns sit together at the top whichever way the
 * letters run. Ties in weight or age fall back to A to Z, so the order is
 * stable from one visit to the next.
 *
 * Every spelling is turned into a collation key once, up front: the list
 * holds up to 10,000 words, and a collator compare per comparison is what
 * made a sort that size visible.
 */
fun sortDictionaryWords(
    words: List<Pair<String, Int>>,
    sort: DictionarySort,
    added: Map<String, Long>,
    collator: Collator = Collator.getInstance(),
): List<Pair<String, Int>> {
    class Row(val entry: Pair<String, Int>) {
        val key = collator.getCollationKey(entry.first)
        val capital = entry.first.firstOrNull()?.isUpperCase() == true
        val born = added[entry.first] ?: Long.MIN_VALUE
    }
    val aToZ = compareBy<Row> { it.key }
    val order: Comparator<Row> = when (sort) {
        DictionarySort.MOST_USED_FIRST -> compareByDescending<Row> { it.entry.second }.then(aToZ)
        DictionarySort.LEAST_USED_FIRST -> compareBy<Row> { it.entry.second }.then(aToZ)
        DictionarySort.A_TO_Z -> compareBy<Row> { !it.capital }.then(aToZ)
        DictionarySort.Z_TO_A -> compareBy<Row> { !it.capital }.then(aToZ.reversed())
        DictionarySort.NEWEST_FIRST -> compareByDescending<Row> { it.born }.then(aToZ)
        DictionarySort.OLDEST_FIRST -> compareBy<Row> { it.born }.then(aToZ)
    }
    return words.map(::Row).sortedWith(order).map { it.entry }
}
