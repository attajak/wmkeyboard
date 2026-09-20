package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HindiPhoneticIndexTest {

    // ल + ड + nukta + क + ा, the decomposed spelling word lists carry.
    private val ladka = "लड़का"
    private val zindagi = "ज़िंदगी"

    private val index = HindiPhoneticIndex(
        listOf(
            "करना" to 900, "कम" to 800, "काम" to 700, "नहीं" to 5000, "हाँ" to 600,
            "है" to 9000, "हैं" to 4000, "में" to 8000, "मैं" to 3000, ladka to 300,
            "अच्छा" to 1200, "ठीक" to 900, "तक" to 1000, "थक" to 100, "कई" to 700,
            "भाई" to 800, "और" to 9000, "कौन" to 1500, "कहाँ" to 900, "कहा" to 2000,
            "हिंदी" to 500, "हिन्दी" to 400, zindagi to 300, "जिंदगी" to 200,
            "पानी" to 700, "पता" to 900, "पत्ता" to 100, "ज्ञान" to 200, "कृपा" to 100,
            "लंबा" to 100, "जाओ" to 300, "कर" to 5000, "रहा" to 3000, "कुछ" to 2500,
        ),
    )

    private fun top(input: String) = index.lookup(input).firstOrNull()

    @Test fun aDroppedSchwaAndAConjunctLookAlike() {
        // The reason the index exists: the rules cannot know "rn" is two syllables.
        assertEquals("करना", top("karna"))
        assertEquals("krna", HindiPhoneticIndex.foldDevanagari("करना"))
        assertEquals("krna", HindiPhoneticIndex.foldRoman("karna").key)
    }

    @Test fun aLoneAFindsBothLengthsAndADoubledOneAsksForLong() {
        assertEquals(listOf("कम", "काम"), index.lookup("kam"))
        assertEquals("काम", top("kaam"))
        assertEquals("पानी", top("pani"))
    }

    @Test fun nasalisationNobodyTypes() {
        assertEquals("नहीं", top("nahi"))
        assertEquals("नहीं", top("nahin"))
        assertEquals("हाँ", top("ha"))
        assertEquals("हाँ", top("haan"))
        // A nasal that was typed is not thrown away: कहा stays ahead for "kaha".
        assertEquals("कहा", top("kaha"))
        assertEquals("कहाँ", top("kahan"))
    }

    @Test fun theDiphthongTellsMainFromMein() {
        assertEquals("में", top("mein"))
        assertEquals("में", top("me"))
        assertEquals("मैं", top("main"))
        assertEquals("मैं", top("mai"))
    }

    @Test fun anAGlidingIntoAnIndependentVowel() {
        assertEquals("कई", top("kai"))
        assertEquals("भाई", top("bhai"))
        assertEquals("और", top("aur"))
        assertEquals("और", top("or"))
        assertEquals("कौन", top("kaun"))
        assertEquals("कौन", top("kon"))
        assertEquals("जाओ", top("jao"))
    }

    @Test fun theFlapIsTypedAsDOrR() {
        assertEquals(ladka, top("ladka"))
        assertEquals(ladka, top("larka"))
    }

    @Test fun retroflexAndDentalAreOneKey() {
        assertEquals("ठीक", top("theek"))
        assertEquals("ठीक", top("thik"))
    }

    @Test fun aspirationTypedIsARequest() {
        // तक is ten times commoner, and "thak" still asked for the h.
        assertEquals("थक", top("thak"))
        assertEquals("तक", top("tak"))
    }

    @Test fun aDoubledConsonantIsOneKeyAndStillCounts() {
        for (spelling in listOf("accha", "acha", "achha")) assertEquals("अच्छा", top(spelling))
        assertEquals("पता", top("pata"))
        assertEquals("पत्ता", top("patta"))
    }

    @Test fun spellingsOfOneWordAreSiblings() {
        assertEquals(setOf("हिंदी", "हिन्दी"), index.lookup("hindi").toSet())
        assertEquals(setOf(zindagi, "जिंदगी"), index.lookup("zindagi").toSet())
        assertEquals(setOf(zindagi, "जिंदगी"), index.lookup("jindagi").toSet())
    }

    @Test fun chatSpellingWithNoVowelsAtAll() {
        assertEquals("कर", top("kr"))
        assertEquals("रहा", top("rha"))
        assertEquals("कुछ", top("kuch"))
    }

    @Test fun lettersThatAreSaidDifferentlyThanWritten() {
        assertEquals("ज्ञान", top("gyan"))
        assertEquals("कृपा", top("kripa"))
        assertEquals("लंबा", top("lamba"))
    }

    @Test fun aClosingAThatIsSaidAndNotWritten() {
        val tatsama = HindiPhoneticIndex(
            listOf("मित्र" to 900, "मित्रा" to 100, "सत्य" to 500, "खर्च" to 900, "खर्चा" to 200, "कम" to 900),
        )
        assertEquals("मित्र", tatsama.lookup("mitra").first())
        assertEquals("सत्य", tatsama.lookup("satya").first())
        // खर्च is said "kharch": its conjunct needs no vowel, so the typed one is real.
        assertEquals(listOf("खर्चा"), tatsama.lookup("kharcha"))
        assertTrue(tatsama.lookup("kama").isEmpty())
        // A doubled one asked for आ outright.
        assertEquals(listOf("मित्रा"), tatsama.lookup("mitraa"))
    }

    @Test fun tokensThatAreNotWordsAreLeftOut() {
        // Scraped lists carry है। with its danda attached, and commoner than है.
        val scraped = HindiPhoneticIndex(listOf("है।" to 9000, "है" to 7000, "2024" to 50, "OK" to 40))
        assertEquals(listOf("है"), scraped.lookup("hai"))
        assertEquals(0, scraped.frequencyOf("है।"))
    }

    @Test fun frequenciesAndEmptiness() {
        assertEquals(900, index.frequencyOf("करना"))
        assertEquals(0, index.frequencyOf("कर्ना"))
        assertFalse(index.isEmpty)
        assertTrue(HindiPhoneticIndex(emptyList()).isEmpty)
        assertTrue(index.lookup("xyzzy").isEmpty())
    }
}
