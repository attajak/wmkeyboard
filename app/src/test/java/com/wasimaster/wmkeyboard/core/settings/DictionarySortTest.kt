package com.wasimaster.wmkeyboard.core.settings

import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionarySortTest {

    private val words = listOf(
        "banana" to 5,
        "Zed" to 1,
        "apple" to 5,
        "Boston" to 9,
        "cherry" to 2,
    )

    // "cherry" has no entry, so it sorts as the oldest.
    private val added = mapOf("banana" to 3L, "Zed" to 1L, "apple" to 2L, "Boston" to 3L)

    private fun order(sort: DictionarySort): List<String> =
        sortDictionaryWords(words, sort, added, Collator.getInstance(Locale.ENGLISH)).map { it.first }

    @Test
    fun weightOrdersBreakTiesAlphabetically() {
        assertEquals(listOf("Boston", "apple", "banana", "cherry", "Zed"), order(DictionarySort.MOST_USED_FIRST))
        assertEquals(listOf("Zed", "cherry", "apple", "banana", "Boston"), order(DictionarySort.LEAST_USED_FIRST))
    }

    @Test
    fun alphabeticalOrdersPutCapitalsFirstBothWays() {
        assertEquals(listOf("Boston", "Zed", "apple", "banana", "cherry"), order(DictionarySort.A_TO_Z))
        assertEquals(listOf("Zed", "Boston", "cherry", "banana", "apple"), order(DictionarySort.Z_TO_A))
    }

    @Test
    fun dateOrdersPutWordsWithNoDateOldest() {
        assertEquals(listOf("banana", "Boston", "apple", "Zed", "cherry"), order(DictionarySort.NEWEST_FIRST))
        assertEquals(listOf("cherry", "Zed", "apple", "banana", "Boston"), order(DictionarySort.OLDEST_FIRST))
    }

    @Test
    fun everyKeyHasTwoDirectionsThatReverseEachOther() {
        for (key in DictionarySortKey.entries) {
            val first = DictionarySort.of(key)
            assertEquals(key, first.key)
            assertEquals(key, first.reversed().key)
            assertEquals(first, first.reversed().reversed())
            assertEquals(2, DictionarySort.entries.count { it.key == key })
        }
        assertEquals(DictionarySort.MOST_USED_FIRST, AppUiSettings().dictionarySort)
    }
}
