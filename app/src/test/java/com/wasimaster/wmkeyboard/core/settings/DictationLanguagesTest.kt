package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which languages a dictation listens for (#416), the first being the one it starts in. */
class DictationLanguagesTest {

    @Test
    fun `no choice follows the keyboard`() {
        assertEquals(listOf("bn"), WhisperSettings().dictationLanguages("bn"))
    }

    @Test
    fun `one language holds whatever the layout`() {
        assertEquals(listOf("en"), WhisperSettings(languages = listOf("en")).dictationLanguages("bn"))
    }

    @Test
    fun `a combination starts in the keyboard's language when it is one of them`() {
        val settings = WhisperSettings(languages = listOf("en", "bn", "hi"))
        assertEquals(listOf("bn", "en", "hi"), settings.dictationLanguages("bn"))
        assertEquals(listOf("en", "bn", "hi"), settings.dictationLanguages("en"))
    }

    @Test
    fun `a combination without the keyboard's language starts in its first`() {
        assertEquals(listOf("en", "bn"), WhisperSettings(languages = listOf("en", "bn")).dictationLanguages("de"))
    }
}
