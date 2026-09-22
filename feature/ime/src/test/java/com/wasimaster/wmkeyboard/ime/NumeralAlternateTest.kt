package com.wasimaster.wmkeyboard.ime

import android.view.KeyEvent
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.VERBATIM_DIGITS
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The number row's other numeral system under a hold (#309): on an Arabic
 * board a tap types ٠-٩, and the held alternate types 0-9 as offered instead
 * of being rewritten back.
 */
@RunWith(RobolectricTestRunner::class)
class NumeralAlternateTest {

    @Test
    fun `a tapped digit types the language's own numerals`() {
        assertEquals("١", onArabic("1").text.toString())
    }

    @Test
    fun `a verbatim alternate types the ASCII digit`() {
        // An ASCII digit goes out as its key event rather than a commit, so
        // web forms that filter on keydown see it (see commitTypedCharacter).
        val editor = onArabic(VERBATIM_DIGITS + "1")
        assertTrue(editor.commits.isEmpty())
        assertTrue(KeyEvent.KEYCODE_1 in editor.keys)
    }

    @Test
    fun `a verbatim native digit types as it is`() {
        assertEquals("٧", onArabic(VERBATIM_DIGITS + "٧").text.toString())
    }

    private fun onArabic(text: String): RecordingEditor {
        val editor = RecordingEditor()
        val (service, _, _) = glideKeyboard(
            state = glideReadyState(settings = KeyboardSettings(learnFromTyping = false))
                .copy(language = LanguageRegistry.byId("ar")),
            editor = editor,
        )
        service.onText(text)
        settle { editor.text.isNotEmpty() || editor.keys.isNotEmpty() }
        return editor
    }
}
