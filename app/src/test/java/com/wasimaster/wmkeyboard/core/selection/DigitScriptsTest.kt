package com.wasimaster.wmkeyboard.core.selection

import com.wasimaster.wmkeyboard.core.script.NumeralSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DigitScriptsTest {

    @Test
    fun `ascii digits and letters are not foreign`() {
        for (text in listOf("2024", "abc", "আমি", "")) {
            assertFalse(text, DigitScripts.hasForeignDigits(text))
            assertNull(text, DigitScripts.toAsciiDigits(text))
        }
    }

    @Test
    fun `every script converts, the rest of the text untouched`() {
        assertEquals("01712-345678", DigitScripts.toAsciiDigits("০১৭১২-৩৪৫৬৭৮"))
        assertEquals("012", DigitScripts.toAsciiDigits("٠١٢"))
        assertEquals("1403", DigitScripts.toAsciiDigits("۱۴۰۳"))
        assertEquals("2024", DigitScripts.toAsciiDigits("२०२४"))
        assertEquals("৳1,234.50", DigitScripts.toAsciiDigits("৳১,২৩৪.৫০"))
        assertEquals("123", DigitScripts.toAsciiDigits("１２３"))
    }

    @Test
    fun `every numeral system round-trips its ten glyphs`() {
        for (system in NumeralSystem.entries) {
            val digits = system.digits ?: continue
            assertEquals(system.name, "0123456789", DigitScripts.toAsciiDigits(digits))
        }
    }
}
