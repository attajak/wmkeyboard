package com.wasimaster.wmkeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.prediction.SpellingMap
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Avro with English among Bengali's secondary languages and "Type English words
 * as English" on: the field shows, and the space bar commits, an English word
 * as English — and a backspace straight after flips a word both languages have.
 *
 * The rule itself is `PhoneticEnglishMixTest`'s, against the engine. This is
 * the wiring: that the preview, the commit and the revert all ask it.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneticEnglishServiceTest {

    private class AvroKeyboard(
        private val editor: InputConnection,
        private val info: EditorInfo,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = info
    }

    private val plainField = EditorInfo().also { it.inputType = InputType.TYPE_CLASS_TEXT }

    private fun engine(autoEnglish: Boolean) = SuggestionEngine(
        Trie().apply {
            insert("the", 10000)
            insert("to", 9800)
            insert("hello", 830)
            insert("hell", 600)
        },
        BengaliPhoneticIndex(listOf("তো" to 5200, "কেমন" to 5000, "হ্যালো" to 1900)),
        UserLexicon(null),
        SpellingMap.load(
            "hello\tহ্যালো\n".byteInputStream(Charsets.UTF_8),
            "to\tতো\nkemon\tকেমন\n".byteInputStream(Charsets.UTF_8),
            loanwordStreams = 1,
        ),
    ).apply {
        primaryLanguageId = "bn"
        englishSources = false
        englishAsSecondary = true
        phoneticAutoEnglish = autoEnglish
    }

    private fun keyboardOn(editor: RecordingEditor, autoEnglish: Boolean = true): AvroKeyboard {
        val service = AvroKeyboard(editor, plainField)
        plantPersonalStores(service)
        val field = WMKeyboardService::class.java.getDeclaredField("suggestionEngine")
        field.isAccessible = true
        field.set(service, engine(autoEnglish))
        val avro = BuiltInLayouts.AVRO
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    haptics = HapticSettings(enabled = false),
                    secondaryLanguages = mapOf("bn" to listOf("en")),
                    suggestionStrip = SuggestionStripSettings(phoneticAutoEnglish = autoEnglish),
                ),
                fieldNoSuggestions = false,
            ).copy(
                language = avro.language(),
                script = avro.script(),
                composer = composerFor(avro.script(), avro.composerType()),
                layoutId = avro.id,
            ),
        )
        return service
    }

    private fun type(service: AvroKeyboard, word: String) {
        for (letter in word) service.onKey(Key(label = letter.toString()))
    }

    private fun space(service: AvroKeyboard) = service.onKey(Key(label = "space", action = KeyAction.Space))

    private fun backspace(service: AvroKeyboard) = service.onKey(Key(label = "⌫", action = KeyAction.Delete))

    @Test
    fun `an english word shows and commits as english`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "hel")
        // Not a word of either language yet: Avro's own reading.
        assertEquals("হেল", service.uiState.value.composingPreview)
        type(service, "lo")
        assertEquals("hello", service.uiState.value.composingPreview)
        space(service)

        assertEquals("hello ", editor.text.toString())
    }

    @Test
    fun `a bengali word is untouched`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "kemon")
        assertEquals("কেমন", service.uiState.value.composingPreview)
        space(service)

        assertEquals("কেমন ", editor.text.toString())
    }

    @Test
    fun `with the switch off english is never committed`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor, autoEnglish = false)

        type(service, "hello")
        assertEquals("হ্যালো", service.uiState.value.composingPreview)
        space(service)

        assertEquals("হ্যালো ", editor.text.toString())
    }

    @Test
    fun `backspace straight after flips a word both languages have`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "hello")
        space(service)
        assertEquals("hello ", editor.text.toString())
        backspace(service)

        assertEquals("হ্যালো ", editor.text.toString())
    }

    @Test
    fun `switching it off in the middle of a word takes the word back`() {
        // What the toolbar's switch is for: the preview has gone Latin under a
        // word that was not English, and it has to come back before the space.
        val editor = RecordingEditor()
        val service = keyboardOn(editor)
        type(service, "hello")
        assertEquals("hello", service.uiState.value.composingPreview)

        // The settings collector's half of the toggle, once the write lands.
        val sync = WMKeyboardService::class.java
            .getDeclaredMethod("syncPhoneticAutoEnglish", KeyboardSettings::class.java)
        sync.isAccessible = true
        sync.invoke(service, KeyboardSettings(suggestionStrip = SuggestionStripSettings(phoneticAutoEnglish = false)))

        assertEquals("হ্যালো", service.uiState.value.composingPreview)
        space(service)
        assertEquals("হ্যালো ", editor.text.toString())
    }

    @Test
    fun `backspace after a word only one language has just deletes`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "kemon")
        space(service)
        backspace(service)

        assertEquals("কেমন", editor.text.toString())
    }
}
