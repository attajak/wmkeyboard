package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.PinyinComposer
import com.wasimaster.wmkeyboard.core.input.composer.PinyinSyllables
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A conversion IME keeps its candidates in fields that silence the suggestion
 * strip (issue #260).
 *
 * For Chinese or Japanese the candidate list is not a guess the keyboard is
 * offering — it is the input method, the same way a transliterator's composing
 * buffer is. So the gates that quiet the strip (the setting, a field's
 * NO_SUGGESTIONS flag or FILTER/URI/email variation, a password box) cannot
 * also apply to it: the pinyin composes either way, and with no Hanzi behind it
 * the language is simply untypeable. Every search box in every app was in that
 * state.
 *
 * Driven like the other service tests here: a subclass with a context attached
 * and `onCreate` never called.
 */
@RunWith(RobolectricTestRunner::class)
class ConversionStripGateTest {

    /** The whole dictionary these tests need: one reading, one character. */
    @Before
    fun loadPinyinTable() {
        PinyinSyllables.valid = setOf("ni")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("ni\t你\t100"))
    }

    // Process globals, so they go back as they were for every other test.
    @After
    fun resetPinyinTable() {
        PinyinSyllables.valid = emptySet()
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
    }

    private fun pinyinState(
        suggestions: Boolean,
        fieldNoSuggestions: Boolean,
        secureField: Boolean = false,
    ) = glideReadyState(
        glideReady = false,
        settings = KeyboardSettings(
            learnFromTyping = false,
            suggestions = suggestions,
            haptics = HapticSettings(enabled = false),
        ),
        secureField = secureField,
        fieldNoSuggestions = fieldNoSuggestions,
    ).copy(composer = PinyinComposer)

    /** Types `ni` on a pinyin board into [state]'s field and hands back the strip. */
    private fun candidatesAfterTypingNi(state: KeyboardUiState): List<String> {
        val editor = RecordingEditor()
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        seedState(service, state)
        service.onKey(Key(label = "n"))
        service.onKey(Key(label = "i"))
        return service.uiState.value.suggestions
    }

    @Test
    fun `a field that asked for a quiet strip still gets its candidates`() {
        val candidates = candidatesAfterTypingNi(
            pinyinState(suggestions = true, fieldNoSuggestions = true),
        )
        assertEquals(listOf("你"), candidates)
    }

    @Test
    fun `the suggestion strip switched off does not switch Chinese off with it`() {
        val candidates = candidatesAfterTypingNi(
            pinyinState(suggestions = false, fieldNoSuggestions = false),
        )
        assertEquals(listOf("你"), candidates)
    }

    /**
     * A password field converts too — people whose language needs conversion
     * have to be able to type one. What it must not do is remember the pick,
     * and that is `learningAllowed`'s job, not the strip's: it never covers a
     * secure field.
     */
    @Test
    fun `a password field converts without remembering the reading`() {
        val service = GlideKeyboard(RecordingEditor())
        plantPersonalStores(service)
        seedState(service, pinyinState(suggestions = true, fieldNoSuggestions = true, secureField = true))
        service.onKey(Key(label = "n"))
        service.onKey(Key(label = "i"))

        assertEquals(listOf("你"), service.uiState.value.suggestions)
        // The buffer is the reading, not the characters: nothing has been
        // committed yet, so nothing has been learned from either.
        assertTrue(service.uiState.value.composingPreview.isNotEmpty())
    }

    /**
     * The chip that says the pack is missing, which is the other half of #260:
     * with an empty table the same keystrokes produce no candidates at all, and
     * something on screen has to say why.
     */
    @Test
    fun `an empty table puts the missing-pack chip up instead`() {
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        val service = GlideKeyboard(RecordingEditor())
        plantPersonalStores(service)
        seedState(service, pinyinState(suggestions = true, fieldNoSuggestions = true))
        service.onKey(Key(label = "n"))
        service.onKey(Key(label = "i"))

        assertEquals(emptyList<String>(), service.uiState.value.suggestions)
        assertEquals("pinyin", service.uiState.value.conversionPackOffer)
    }
}
