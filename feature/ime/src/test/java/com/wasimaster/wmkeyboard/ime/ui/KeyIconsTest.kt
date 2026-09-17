package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

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

    @Test
    fun `an alias is searchable from the name the picker shows`() {
        assertTrue(KeyIcons.aliasesFor("paste").contains("clipboard"))
        assertTrue(KeyIcons.aliasesFor("language").contains("globe"))
        assertTrue(KeyIcons.aliasesFor("PASTE ").contains("clipboard"))
        assertTrue(KeyIcons.aliasesFor("SelectAll").isEmpty())
    }

    /**
     * Issue #223: a glyph the app draws somewhere but the picker never offers
     * reads as a missing icon. Every built-in drawing — tool, key, chrome,
     * emoji tab — has to be reachable from the picker.
     */
    @Test
    fun `every icon the app draws is offered by the picker`() {
        val offered = KeyIcons.pickerEntries.map { it.second }.toHashSet()
        for (tool in ToolbarTool.entries) {
            assertTrue(tool.name, IconDefaults.forTool(tool) in offered)
        }
        for ((slot, vector) in IconDefaults.bySlot) {
            assertTrue(slot, vector in offered)
        }
    }
}
