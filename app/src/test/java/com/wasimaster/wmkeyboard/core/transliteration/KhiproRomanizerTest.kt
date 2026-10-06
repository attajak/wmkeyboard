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
            // The slicer: চন্দ্রবিন্দু and খণ্ড-ত.
            "চাঁদ", "হঠাৎ", "দাঁড়িয়ে", "উৎসব",
        )
        for (word in words) {
            val spellings = KhiproRomanizer.spellings(word)
            assertTrue("$word has no spelling", spellings.isNotEmpty())
            for (spelling in spellings) {
                assertTrue("$spelling has a key a swipe cannot reach", spelling.all { it in KhiproRomanizer.KEYS })
                assertEquals(spelling, precomposed(word), precomposed(Khipro.convert(spelling)))
            }
        }
    }

    @Test fun theInherentVowelMayBeWrittenOut() {
        assertEquals(listOf("ekhn", "ekhon"), KhiproRomanizer.spellings("এখন"))
    }

    /** চন্দ্রবিন্দু comes from the slicer, which the stroke passes through. */
    @Test fun theSlicerIsAKeyOfTheSpelling() {
        assertTrue(KhiproRomanizer.spellings("চাঁদ").all { '/' in it })
    }
}
