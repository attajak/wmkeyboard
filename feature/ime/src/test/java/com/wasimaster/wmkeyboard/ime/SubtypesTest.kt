package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the subtype↔layout bridge that lets the OS switcher round-trip layout
 * selection. The [android.view.inputmethod.InputMethodSubtype] construction
 * itself needs the framework, so the tests target the pure helpers the writer
 * and reader both go through: the extra-value format and the stable id.
 */
class SubtypesTest {

    @Test
    fun `layout id round-trips through the extra-value format`() {
        for (id in listOf("qwerty", "bn_avro", "sr-cyrl.custom", "und", "a")) {
            assertEquals(id, layoutIdFromExtraValue(layoutExtraValue(id)))
        }
    }

    @Test
    fun `absent, empty or unrelated extra value yields no layout id`() {
        assertNull(layoutIdFromExtraValue(null))
        assertNull(layoutIdFromExtraValue(""))
        assertNull(layoutIdFromExtraValue("foo=bar"))
        // A subtype that is not ours and a truncated value carry no id.
        assertNull(layoutIdFromExtraValue("layoutId="))
    }

    @Test
    fun `layout id survives alongside other extra-value pairs`() {
        assertEquals("qwerty", layoutIdFromExtraValue("foo=bar,layoutId=qwerty"))
        assertEquals("qwerty", layoutIdFromExtraValue("layoutId=qwerty,foo=bar"))
    }

    @Test
    fun `layout id still reads back when a switcher label rides along`() {
        val value = subtypeExtraValue("hi_rom", "Hinglish · Hindi (Romanized)")
        assertEquals("hi_rom", layoutIdFromExtraValue(value))
        assertTrue(value.endsWith("=Hinglish · Hindi (Romanized)"))
        // No label, no second pair: the value is exactly what it always was.
        assertEquals(layoutExtraValue("qwerty"), subtypeExtraValue("qwerty", null))
        assertEquals(layoutExtraValue("qwerty"), subtypeExtraValue("qwerty", "  "))
    }

    /** The pairs are comma-separated and `=`-split, so a label may hold neither. */
    @Test
    fun `switcher label cannot break the extra-value pairs`() {
        val value = subtypeExtraValue("x", "a, b=c")
        assertEquals("x", layoutIdFromExtraValue(value))
        assertTrue(value.endsWith("=a  b c"))
    }

    @Test
    fun `only romanized languages override the switcher label`() {
        assertEquals(
            "Hinglish · Hindi (Romanized)",
            subtypeNameOverride("hi_rom", "Hinglish · Hindi (Romanized)"),
        )
        assertNull(subtypeNameOverride("hi", "हिन्दी"))
        assertNull(subtypeNameOverride("en", "English"))
        assertNull(subtypeNameOverride("bn_rom", ""))
    }

    @Test
    fun `subtype id is stable, non-negative and layout-specific`() {
        assertEquals(stableSubtypeId("qwerty"), stableSubtypeId("qwerty"))
        assertTrue(stableSubtypeId("qwerty") >= 0)
        assertTrue(stableSubtypeId("bn_avro") >= 0)
        assertNotEquals(stableSubtypeId("qwerty"), stableSubtypeId("azerty"))
        assertNotEquals(stableSubtypeId("bn_avro"), stableSubtypeId("bn_probhat"))
    }

    /**
     * 0 is the framework's "no explicit id" sentinel: a subtype carrying it
     * falls back to hashing its fields, and the hash we then hand
     * `setExplicitlyEnabledInputMethodSubtypes` matches nothing.
     */
    @Test
    fun `subtype id is never the unspecified sentinel`() {
        // The empty id is the reachable case; the real ids stand in for any
        // that might land on 0 once masked.
        for (id in listOf("", "qwerty", "bn_avro")) {
            assertNotEquals(0, stableSubtypeId(id))
        }
    }
}
