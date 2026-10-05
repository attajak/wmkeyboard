package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrduPhoneticIndexTest {

    /**
     * Every word at frequency 1, which is what the downloadable Urdu list
     * actually ships: the ordering here is the detail mask's work and nothing
     * else's.
     */
    private val index = UrduPhoneticIndex(
        listOf(
            "کرنا", "کم", "کام", "کتاب", "کتب", "کتابیں", "دن", "دین", "روٹی", "روتی",
            "بڑا", "برا", "پانی", "پنی", "ہے", "ہیں", "بچہ", "بچا", "نہیں", "نہی",
            "خوش", "دوست", "مرد", "کوئی", "کیا", "میں", "ٹھیک", "تھیک", "سڑک", "سرک",
            "گھر", "غار", "خبر", "صبح", "ضرور", "زرور",
        ).map { it to 1 },
    )

    private fun first(input: String) = index.lookup(input).firstOrNull()

    @Test fun theKeyIsTheConsonantSkeleton() {
        // Both sides agree, which is the whole contract: a vowel the script
        // does not write cannot be in the key.
        assertEquals(UrduPhoneticIndex.foldUrdu("کتاب"), UrduPhoneticIndex.foldRoman("kitab").key)
        assertEquals(UrduPhoneticIndex.foldUrdu("کتاب"), UrduPhoneticIndex.foldRoman("ktab").key)
        assertEquals(UrduPhoneticIndex.foldUrdu("کرنا"), UrduPhoneticIndex.foldRoman("karna").key)
        assertEquals(UrduPhoneticIndex.foldUrdu("کیا"), UrduPhoneticIndex.foldRoman("kya").key)
        assertEquals(UrduPhoneticIndex.foldUrdu("دعا"), UrduPhoneticIndex.foldRoman("dua").key)
        assertEquals("ktb", UrduPhoneticIndex.foldUrdu("کتاب"))
    }

    @Test fun shortVowelsFindTheWordsThatWriteThem() {
        assertEquals("کرنا", first("karna"))
        assertEquals("کتب", first("kitab"))
        assertTrue("کتاب" in index.lookup("kitab"))
        assertEquals("دوست", first("dost"))
        assertEquals("مرد", first("mard"))
        // Chat spelling with the vowels left out altogether still lands.
        assertTrue("کتاب" in index.lookup("ktab"))
    }

    @Test fun aDoubledVowelAsksForTheLongOne() {
        assertEquals("کم", first("kam"))
        assertEquals("کام", first("kaam"))
        assertEquals("دن", first("din"))
        assertEquals("دین", first("deen"))
    }

    @Test fun theRetroflexRowIsFoldedAndTheCapitalAsksForIt() {
        // No roman spelling marks ٹ ڈ ڑ, so both readings are siblings — and
        // typing the capital puts the retroflex first.
        assertTrue("روٹی" in index.lookup("roti"))
        assertTrue("بڑا" in index.lookup("bara"))
        assertEquals("تھیک", first("theek"))
        assertEquals("ٹھیک", first("Theek"))
        assertEquals("سرک", first("sarak"))
        assertEquals("سڑک", first("saRak"))
    }

    @Test fun theArabicLettersAreSiblingsOfTheUrduOnes() {
        assertEquals("خوش", first("khush"))
        assertEquals("خبر", first("khabar"))
        assertEquals("صبح", first("subah"))
        assertTrue("ضرور" in index.lookup("zaroor"))
        assertEquals("ضرور", first("Zaroor"))
        // گھ and غ are one key with aspiration as its detail, so "ghar" can
        // reach either — and the aspirate is what was typed.
        assertEquals("گھر", first("ghar"))
    }

    @Test fun nasalisationAndAClosingHeAreFiledBothWays() {
        // ہیں typed "hain", and typed "hai" without the nasal at all.
        assertEquals("ہیں", first("hain"))
        assertEquals("میں", first("mein"))
        assertTrue("ہیں" in index.lookup("hai"))
        assertEquals("ہے", first("hai"))
        // بچہ is "bacha" far more often than "bachah".
        assertTrue("بچہ" in index.lookup("bacha"))
        assertTrue("نہیں" in index.lookup("nahi"))
    }

    @Test fun aVowelOnlyWordIsReachedThroughItsCarrier() {
        assertEquals("کوئی", first("koi"))
        assertEquals("کیا", first("kya"))
    }

    @Test fun frequencyStillLeadsWhereTheListHasIt() {
        // The flat list is Urdu's own problem, not this index's: given real
        // counts they order the siblings as everywhere else.
        val ranked = UrduPhoneticIndex(listOf("کام" to 9000, "کم" to 100))
        assertEquals("کام", ranked.lookup("kam").firstOrNull())
        assertEquals(9000, ranked.frequencyOf("کام"))
        assertEquals(9000, ranked.maxFrequency)
    }

    @Test fun underscoredCompoundsAreNotWords() {
        // ur_full spells its compounds with an underscore; folded, they would
        // be filed under the first word's key.
        val withCompound = UrduPhoneticIndex(listOf("آؤ_بھگت" to 1, "بھگت" to 1))
        assertEquals(listOf("بھگت"), withCompound.lookup("bhagat"))
    }

    @Test fun anEmptyListKnowsItIsEmpty() {
        val empty = UrduPhoneticIndex(emptyList())
        assertTrue(empty.isEmpty)
        assertTrue(empty.lookup("kitab").isEmpty())
        assertEquals(0, empty.frequencyOf("کتاب"))
        assertFalse(index.isEmpty)
    }
}
