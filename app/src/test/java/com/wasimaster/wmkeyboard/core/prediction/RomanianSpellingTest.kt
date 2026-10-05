package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Romanian is written with a comma below, not a cedilla, and the word lists
 * it is read from are written both ways. Everything here is spelt in escapes:
 * the two letters are a pixel apart on screen and a test that cannot say
 * which one it means tests nothing.
 */
class RomanianSpellingTest {

    @get:Rule
    val temp = TemporaryFolder()

    // U+015F, U+0163 and their capitals: the Turkish letters a Romanian list
    // reaches for when its source predates Unicode's own.
    private val cedillaAnd = "\u015Fi"
    private val cedillaYouLive = "tr\u0103ie\u015Fti"
    private val cedillaCountry = "\u0163ara"
    private val cedillaShout = "\u015EI"

    // U+0219, U+021B and their capitals: what Romanian actually writes.
    private val commaAnd = "\u0219i"
    private val commaYouLive = "tr\u0103ie\u0219ti"
    private val commaCountry = "\u021Bara"
    private val commaShout = "\u0218I"

    @Test
    fun `both letters are respelt, in both cases`() {
        assertEquals(commaAnd, RomanianSpelling.canonical(cedillaAnd))
        assertEquals(commaYouLive, RomanianSpelling.canonical(cedillaYouLive))
        assertEquals(commaCountry, RomanianSpelling.canonical(cedillaCountry))
        assertEquals(commaShout, RomanianSpelling.canonical(cedillaShout))
    }

    @Test
    fun `a word already right is handed straight back`() {
        // Not merely equal: this runs per word over a list of a million, and
        // all but a twentieth of them take this path.
        assertSame(commaYouLive, RomanianSpelling.canonical(commaYouLive))
        assertSame("casa", RomanianSpelling.canonical("casa"))
        assertSame("", RomanianSpelling.canonical(""))
    }

    @Test
    fun `every probe is a word only a cedilla list holds`() {
        for (probe in RomanianSpelling.PROBES) {
            assertTrue(probe, probe.any(RomanianSpelling::isCedilla))
            assertFalse(probe, RomanianSpelling.canonical(probe).any(RomanianSpelling::isCedilla))
        }
    }

    @Test
    fun `no other language is respelt`() {
        assertTrue(RomanianSpelling.appliesTo("ro"))
        // Turkish, Azerbaijani, Gagauz, Zazaki and Kurmanji all write a real
        // cedilla; theirs is not a spelling to repair.
        for (langId in listOf("tr", "az", "gag", "diq", "ku", "rup", "mo")) {
            assertFalse(langId, RomanianSpelling.appliesTo(langId))
        }
    }

    private fun list(langId: String, text: String): File {
        val dir = CustomDictionaries.languageDir(temp.root, langId).apply { mkdirs() }
        return File(dir, "mine.txt").apply { writeText(text) }
    }

    @Test
    fun `an imported Romanian list is read with the comma letters`() {
        list("ro", "$cedillaAnd 100\n$cedillaYouLive 50\ncasa 10\n")
        val entries = CustomDictionaries.entries(temp.root, "ro")
        assertEquals(listOf(commaAnd, commaYouLive, "casa"), entries.map { it.first })
        assertEquals(listOf(100, 50, 10), entries.map { it.second })
        val trie = CustomDictionaries.trie(temp.root, "ro")
        assertTrue(trie.contains(commaYouLive))
        assertFalse(trie.contains(cedillaYouLive))
    }

    @Test
    fun `an imported Turkish list keeps its cedilla`() {
        list("tr", "$cedillaAnd 100\n")
        assertEquals(listOf(cedillaAnd), CustomDictionaries.entries(temp.root, "tr").map { it.first })
    }
}
