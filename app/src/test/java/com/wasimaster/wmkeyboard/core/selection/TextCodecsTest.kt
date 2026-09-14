package com.wasimaster.wmkeyboard.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCodecsTest {

    @Test
    fun `ordinary words are not base64`() {
        for (text in listOf("Password", "Football", "ABCDEFGH", "12345678", "Aa1Bb2Cc", "01712345678", "hello", "")) {
            assertFalse(text, TextCodecs.looksBase64(text))
        }
    }

    @Test
    fun `base64 text decodes, padded or not, in either alphabet`() {
        assertTrue(TextCodecs.looksBase64("SGVsbG8gd29ybGQ="))
        assertEquals("Hello world", TextCodecs.base64Decode("SGVsbG8gd29ybGQ="))
        assertEquals("hello", TextCodecs.base64Decode("aGVsbG8"))
        assertEquals("ক", TextCodecs.base64Decode("4KaV"))
        assertEquals("a?b", TextCodecs.base64Decode("YT9i"))
        assertEquals("a>?", TextCodecs.base64Decode("YT4_"))
    }

    @Test
    fun `percent encoding is detected and decoded, pluses left alone`() {
        assertFalse(TextCodecs.isUrlEncoded("50% off"))
        assertFalse(TextCodecs.isUrlEncoded("100%"))
        assertNull(TextCodecs.urlDecode("100%"))
        assertEquals("hello world", TextCodecs.urlDecode("hello%20world"))
        assertEquals("C++ & friends", TextCodecs.urlDecode("C++ %26 friends"))
        assertEquals("ক", TextCodecs.urlDecode("%E0%A6%95"))
        assertNull(TextCodecs.urlDecode("%ZZ"))
        assertNull(TextCodecs.urlDecode("%E0%A6"))
    }
}
