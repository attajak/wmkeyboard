package com.wasimaster.wmkeyboard.core.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpacedPunctuationTest {

    private val marks = SpacedPunctuation.FRENCH_STYLE
    private val nb = SpacedPunctuation.SPACE

    @Test
    fun `a mark straight after a word gets the space inserted`() {
        assertEquals(0, SpacedPunctuation.spacesToReplace("Bonjour", '!', marks))
        assertEquals(0, SpacedPunctuation.spacesToReplace("Quoi", '?', marks))
        assertEquals(0, SpacedPunctuation.spacesToReplace("voici", ':', marks))
        assertEquals(0, SpacedPunctuation.spacesToReplace("donc", ';', marks))
    }

    @Test
    fun `a space already there is replaced, whatever kind and however many`() {
        assertEquals(1, SpacedPunctuation.spacesToReplace("Bonjour ", '!', marks))
        assertEquals(2, SpacedPunctuation.spacesToReplace("Bonjour  ", '!', marks))
        assertEquals(1, SpacedPunctuation.spacesToReplace("Bonjour\u202F", '!', marks))
        assertEquals(1, SpacedPunctuation.spacesToReplace("Bonjour\u2009", '!', marks))
    }

    @Test
    fun `the right space already there is left completely alone`() {
        assertNull(SpacedPunctuation.spacesToReplace("Bonjour$nb", '!', marks))
    }

    @Test
    fun `a mark this language does not space is none of its business`() {
        assertNull(SpacedPunctuation.spacesToReplace("Bonjour", '.', marks))
        assertNull(SpacedPunctuation.spacesToReplace("Bonjour", ',', marks))
        assertNull(SpacedPunctuation.spacesToReplace("Bonjour", '!', ""))
    }

    @Test
    fun `a mark that does not follow a word is whatever the user meant`() {
        assertNull(SpacedPunctuation.spacesToReplace("", '!', marks))
        assertNull(SpacedPunctuation.spacesToReplace("Bonjour\n", '!', marks))
        assertNull(SpacedPunctuation.spacesToReplace("Bonjour !", '!', marks))
        assertNull(SpacedPunctuation.spacesToReplace("Bonjour.", '!', marks))
    }

    @Test
    fun `a run that fills the whole read may continue past it`() {
        assertNull(SpacedPunctuation.spacesToReplace("    ", '!', marks))
    }

    @Test
    fun `numbers and closing brackets earn the space too`() {
        assertEquals(0, SpacedPunctuation.spacesToReplace("il en reste 3", '!', marks))
        assertEquals(0, SpacedPunctuation.spacesToReplace("(oui)", '?', marks))
        assertEquals(0, SpacedPunctuation.spacesToReplace("« oui »", '?', marks))
    }

    @Test
    fun `a colon on a digit is a time, not a clause mark`() {
        assertNull(SpacedPunctuation.spacesToReplace("5", ':', marks))
        assertNull(SpacedPunctuation.spacesToReplace("il est 16", ':', marks))
        // Only when they are up against each other, and only for the colon.
        assertEquals(1, SpacedPunctuation.spacesToReplace("il en reste 3 ", ':', marks))
        assertEquals(0, SpacedPunctuation.spacesToReplace("il en reste 3", '!', marks))
        assertEquals(0, SpacedPunctuation.spacesToReplace("il en reste 3", ';', marks))
    }

    @Test
    fun `the closing guillemet takes the space too`() {
        assertEquals(0, SpacedPunctuation.spacesToReplace("« oui", '»', marks))
        assertEquals(1, SpacedPunctuation.spacesToReplace("« oui ", '»', marks))
        // Nothing quoted yet: an opener is not a word the mark can hug.
        assertNull(SpacedPunctuation.spacesToReplace("«", '»', marks))
    }

    @Test
    fun `the opening guillemet is on the other list, not this one`() {
        assertNull(SpacedPunctuation.spacesToReplace("il dit", '«', marks))
        assertEquals("«", SpacedPunctuation.FRENCH_STYLE_OPENERS)
    }

    @Test
    fun `every language written to the French standard carries the marks`() {
        val spaced = LanguageRegistry.all
            .filter { it.spacedPunctuation.isNotEmpty() }
            .map { it.id }
            .toSet()
        assertEquals(setOf("fr", "br", "oc", "wa", "pcd", "nrf", "frp"), spaced)
        spaced.forEach {
            assertEquals(SpacedPunctuation.FRENCH_STYLE, LanguageRegistry.byId(it).spacedPunctuation)
            assertEquals(
                SpacedPunctuation.FRENCH_STYLE_OPENERS,
                LanguageRegistry.byId(it).spacedOpeners,
            )
        }
    }

    @Test
    fun `languages that only look French are left out`() {
        listOf("ht", "ca", "gl", "co", "rm", "lb", "vi", "en").forEach {
            assertEquals("", LanguageRegistry.byId(it).spacedPunctuation)
            assertEquals("", LanguageRegistry.byId(it).spacedOpeners)
        }
    }
}
