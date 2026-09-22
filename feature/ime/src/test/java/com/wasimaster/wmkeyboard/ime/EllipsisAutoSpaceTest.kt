package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.AutoTextSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A run of marks typed with the automatic space after punctuation on (#307).
 */
@RunWith(RobolectricTestRunner::class)
class EllipsisAutoSpaceTest {

    @Test
    fun `three full stops stay together`() {
        assertEquals("hello... ", typed("hello", "..."))
    }

    @Test
    fun `mixed marks stay together`() {
        assertEquals("what?! ", typed("what", "?!"))
    }

    @Test
    fun `three full stops stay together with suggestions on`() {
        assertEquals("hello... ", typed("hello", "...", noSuggestions = false))
    }

    @Test
    fun `three full stops typed after a word stay together`() {
        assertEquals("hello... ", typed("", "hello...", noSuggestions = false))
    }

    private fun typed(initial: String, marks: String, noSuggestions: Boolean = true): String {
        val editor = RecordingEditor(initial = initial)
        val (service, _, _) = glideKeyboard(
            state = glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    autoText = AutoTextSettings(spaceAfterPunctuation = true),
                ),
                fieldNoSuggestions = noSuggestions,
            ),
            editor = editor,
        )
        for (ch in marks) {
            val old = editor.text.length
            service.onText(ch.toString())
            val now = editor.text.length
            service.onUpdateSelection(old, old, now, now, -1, -1)
            settle { false }
        }
        return editor.text.toString()
    }
}
