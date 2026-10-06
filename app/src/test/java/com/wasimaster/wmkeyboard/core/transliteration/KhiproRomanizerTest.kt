package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Khipro run backwards (#541): every spelling it gives must convert back to the word. */
class KhiproRomanizerTest {

    private fun precomposed(word: String) = word
        .replace("ড়", "ড়")
        .replace("ঢ়", "ঢ়")
        .replace("য়", "য়")

    @Test fun everySpellingConvertsBackToTheWord() {
        val words = listOf(
            "আমি", "এখন", "আপনি", "করছি", "সময়", "কোথায়", "বাংলাদেশ", "ক্ষিপ্র",
            "দুর্বল", "বর্ষা", "পার্টি", "শনি", "হাসপাতাল", "ধন্যবাদ", "বন্ধুদের",
        )
        for (word in words) {
            val spellings = KhiproRomanizer.spellings(word)
            assertTrue("$word has no spelling", spellings.isNotEmpty())
            for (spelling in spellings) {
                assertTrue("$spelling is not all letters", spelling.all { it in 'a'..'z' })
                assertEquals(spelling, precomposed(word), precomposed(Khipro.convert(spelling)))
            }
        }
    }

    @Test fun theInherentVowelMayBeWrittenOut() {
        assertEquals(listOf("ekhn", "ekhon"), KhiproRomanizer.spellings("এখন"))
    }

    /** চন্দ্রবিন্দু takes the slicer, `/`, which a swipe cannot draw. */
    @Test fun aWordNeedingANonLetterKeyHasNoSpelling() {
        assertEquals(emptyList<String>(), KhiproRomanizer.spellings("চাঁদ"))
    }
}
