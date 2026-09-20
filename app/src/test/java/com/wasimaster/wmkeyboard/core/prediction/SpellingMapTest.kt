package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpellingMapTest {

    private fun map(text: String) =
        SpellingMap.load(text.byteInputStream(Charsets.UTF_8))

    @Test fun mapsLoanword() {
        val m = map("keyboard\tকিবোর্ড\nchair\tচেয়ার\n")
        assertEquals(listOf("কিবোর্ড"), m.lookup("keyboard"))
        assertEquals(listOf("চেয়ার"), m.lookup("chair"))
        assertTrue(m.contains("keyboard"))
    }

    @Test fun lookupIsCaseInsensitiveAndTrimmed() {
        val m = map("chair\tচেয়ার\n")
        assertEquals(listOf("চেয়ার"), m.lookup("Chair"))
        assertEquals(listOf("চেয়ার"), m.lookup("  CHAIR "))
    }

    @Test fun duplicateKeysAccumulateInOrder() {
        val m = map("ticket\tটিকেট\nticket\tটিকিট\nticket\tটিকেট\n")
        assertEquals(listOf("টিকেট", "টিকিট"), m.lookup("ticket"))
    }

    @Test fun commentsBlanksAndMalformedSkipped() {
        val m = map(
            """
            # comment

            keyboard	কিবোর্ড
            noTabHere
            	leadingTabEmptyKey
            table
            """.trimIndent(),
        )
        assertEquals(1, m.size)
        assertEquals(listOf("কিবোর্ড"), m.lookup("keyboard"))
        assertFalse(m.contains("table"))
        assertFalse(m.contains("notabhere"))
    }

    @Test fun unmappedReturnsEmpty() {
        assertTrue(SpellingMap.EMPTY.lookup("anything").isEmpty())
        assertTrue(map("chair\tচেয়ার").lookup("mouse").isEmpty())
    }

    @Test fun severalAssetsMergeWithTheFirstLeading() {
        // How the app loads it: the curated loanword list, then the generated
        // romanized one. Both contribute, and a spelling in both keeps the
        // curated form in front.
        val m = SpellingMap.load(
            "keyboard\tকিবোর্ড\npic\tপিক\n".byteInputStream(Charsets.UTF_8),
            "tmr\tতোমার\npic\tছবি\n".byteInputStream(Charsets.UTF_8),
        )
        assertEquals(listOf("কিবোর্ড"), m.lookup("keyboard"))
        assertEquals(listOf("তোমার"), m.lookup("tmr"))
        assertEquals(listOf("পিক", "ছবি"), m.lookup("pic"))
    }

    @Test fun theShippedAssetIsWellFormed() {
        // Guards the generated file: a stray Latin letter in the Bengali column
        // means a spelling was echoed back instead of translated, and it would
        // be committed verbatim ahead of every other suggestion.
        val lines = java.io.File("src/main/assets/dictionaries/bn_rom.tsv")
            .readLines()
            .filterNot { it.isBlank() || it.startsWith("#") }
        assertTrue("asset looks empty", lines.size > 10_000)
        for (line in lines) {
            val parts = line.split("\t")
            assertEquals("malformed line: $line", 2, parts.size)
            val spelling = parts[0]
            val bengali = parts[1]
            assertTrue("non-ascii key: $line", spelling.all { it in 'a'..'z' })
            assertTrue("empty form: $line", bengali.isNotBlank())
            assertFalse(
                "latin in bengali column: $line",
                bengali.any { it in 'a'..'z' || it in 'A'..'Z' },
            )
            // Nukta letters must be the precomposed code points the rest of the
            // codebase compares against; the decomposed pair never matches.
            assertFalse("decomposed nukta: $line", bengali.contains('\u09BC'))
        }
    }
}
