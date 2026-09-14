package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #187: a key can wear any bundled app icon, and the picker lists each drawing once. */
class KeyIconsTest {

    @Test
    fun `a bundled app icon resolves by name in any case`() {
        val vector = BuiltinIcons.catalog.getValue("EmojiEmotions")
        assertSame(vector, KeyIcons.byName("EmojiEmotions"))
        assertSame(vector, KeyIcons.byName("emojiemotions"))
        assertSame(vector, KeyIcons.byName(" EMOJIEMOTIONS "))
    }

    @Test
    fun `a key glyph name still wins over a bundled icon of the same name`() {
        assertSame(KeyIcons.catalog.getValue("search"), KeyIcons.byName("Search"))
    }

    @Test
    fun `an unknown or blank name draws the label`() {
        assertNull(KeyIcons.byName("mik"))
        assertNull(KeyIcons.byName(" "))
        assertNull(KeyIcons.byName(null))
    }

    @Test
    fun `the picker offers every drawing once, key glyphs first`() {
        val entries = KeyIcons.pickerEntries
        assertEquals(entries.size, entries.map { it.first.lowercase() }.toSet().size)
        assertEquals(entries.size, entries.map { it.second }.toSet().size)
        assertEquals(KeyIcons.catalog.keys.toList(), entries.take(KeyIcons.catalog.size).map { it.first })
        assertTrue(entries.size > KeyIcons.catalog.size)
    }

    @Test
    fun `every picker name resolves to the drawing it shows`() {
        for ((name, vector) in KeyIcons.pickerEntries) {
            assertSame(name, vector, KeyIcons.byName(name))
        }
    }

    @Test
    fun `every advertised name resolves`() {
        for (name in KeyIcons.names) assertNotNull(name, KeyIcons.byName(name))
    }
}
