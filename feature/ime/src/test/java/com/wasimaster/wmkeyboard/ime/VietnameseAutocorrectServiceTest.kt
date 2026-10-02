package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.input.composer.composerFor
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a Vietnamese Telex buffer does when the space bar ends it: the composed
 * word goes to the engine, and the engine — a stand-in for the downloaded `vi`
 * word list — may hand back the spelling the user left a mark out of.
 *
 * Vietnamese is the only transliterator this can be asked of. Hangul's buffer
 * holds jamo and no list has heard of those, so it commits its reading
 * untouched; Telex and VNI compose into real Vietnamese words, which is the
 * one thing a word list knows more about than a composer does.
 *
 * The rule itself is `VietnameseCorrectionTest`'s, against the engine. This is
 * the wiring, and the wiring has one part the engine tests cannot reach: what
 * the undo puts back. The buffer holds keystrokes ("ngieeng"), the field holds
 * a word ("nghiêng"), and one backspace has to leave the user with the word —
 * not with the spelling they would have had to retype to get it back. That is
 * the discriminating case here, and it is the one that fails if the revert is
 * armed against the buffer instead of against what the buffer composed.
 */
@RunWith(RobolectricTestRunner::class)
class VietnameseAutocorrectServiceTest {

    /**
     * A word list standing in for the downloaded `vi` one.
     *
     * Small and deliberately lopsided: "nghiêng" and "nguyễn" are here so the
     * missing-letter and untouched-word cases have somewhere to land, and
     * "tiếng" is far enough ahead of everything else that the omitted tone is
     * restored well under the default confidence gate.
     *
     * [withWords] false is the empty list, which is the state the branch has to
     * be harmless in: the `vi` list is a download, so a Vietnamese keyboard
     * without it is the ordinary case rather than the edge one. A correction
     * that guessed anyway would be the worst possible failure — there is
     * nothing to guess from, so anything it produced would be invented.
     */
    private fun viEngine(withWords: Boolean = true) = SuggestionEngine(
        if (withWords) {
            Trie().apply {
                insert("tiếng", 9000)
                insert("nghiêng", 6000)
                insert("nguyễn", 7000)
            }
        } else {
            Trie()
        },
        BengaliPhoneticIndex(emptyList()),
        UserLexicon(null),
    )

    /**
     * The Vietnamese Telex layout over the engine above, with correction on as
     * it ships. The composer is planted rather than derived from a layout id —
     * the asset layouts are loaded from the APK's assets, which this module's
     * Robolectric tests do not carry — and it is the same object
     * `composerFor(LATIN, TELEX)` gives the running keyboard, which is what the
     * service asks by identity.
     */
    private fun keyboardOn(editor: RecordingEditor, withWords: Boolean = true): GlideKeyboard {
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        val engine = WMKeyboardService::class.java.getDeclaredField("suggestionEngine")
        engine.isAccessible = true
        engine.set(service, viEngine(withWords))
        val latin = ScriptRegistry[ScriptId.LATIN]
        seedState(
            service,
            glideReadyState(
                // learnFromTyping = false for the harness's reason: the commit
                // reaches a lexicon only onCreate assigns otherwise, and the
                // job that dies there dies silently.
                settings = KeyboardSettings(learnFromTyping = false),
                fieldNoSuggestions = false,
            ).copy(
                language = LanguageRegistry.byId("vi"),
                script = latin,
                composer = composerFor(latin, ComposerType.TELEX),
            ),
        )
        return service
    }

    private fun type(service: GlideKeyboard, keys: String) {
        for (key in keys) service.onKey(Key(label = key.toString()))
    }

    private fun space(service: GlideKeyboard) = service.onKey(Key(label = "space", action = KeyAction.Space))

    private fun backspace(service: GlideKeyboard) = service.onKey(Key(label = "⌫", action = KeyAction.Delete))

    /** An omitted tone is what the list is for: "tieng" is not a word, "tiếng" is. */
    @Test
    fun `a missing tone is restored on commit`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "tieng")
        // Nothing in the keystrokes supplies a tone; only the list can.
        assertEquals("tieng", service.uiState.value.composingPreview)
        space(service)

        assertEquals("tiếng ", editor.text.toString())
    }

    /**
     * A mark is a letter, not only a tone: "ngieeng" composes to "ngiêng",
     * which is not a word, and "nghiêng" — one letter away — is.
     */
    @Test
    fun `a missing letter is restored on commit`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "ngieeng")
        assertEquals("ngiêng", service.uiState.value.composingPreview)
        space(service)

        assertEquals("nghiêng ", editor.text.toString())
    }

    /**
     * The protection rule, and the reason this is safe to switch on: the
     * composer already produced a word the list holds, so the list has nothing
     * to say and the user's own spelling is what lands.
     */
    @Test
    fun `a word the list holds is committed as typed`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "nguyeenx")
        assertEquals("nguyễn", service.uiState.value.composingPreview)
        space(service)

        assertEquals("nguyễn ", editor.text.toString())
    }

    /**
     * The undo puts back the word, not the keystrokes.
     *
     * "ngieeng" is in the field as nobody would want to read it; what the
     * commit replaced was "ngiêng", the composer's own reading of those keys,
     * and that is what one backspace has to leave behind. Armed against the
     * buffer instead, the user's reward for taking a bad correction back would
     * be the gibberish they typed — with the correction's own keystrokes gone,
     * so re-typing the word costs the whole thing again.
     */
    @Test
    fun `the undo restores the composed word, not the keystrokes`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor)

        type(service, "ngieeng")
        space(service)
        assertEquals("nghiêng ", editor.text.toString())

        backspace(service)

        assertEquals("ngiêng ", editor.text.toString())
        assertFalse(
            "the keystrokes came back",
            editor.text.contains("ngieeng"),
        )
    }

    /**
     * No list, nothing to say: the composer's own reading lands, whole.
     *
     * The `vi` word list is a download, so this is what most Vietnamese
     * keyboards actually run. The branch has to be a no-op there — and a no-op
     * that is *exactly* a no-op, not merely a quiet one: the word must not be
     * rewritten (nothing in an empty list can be the word the user meant) and
     * must not be swallowed either, which is the failure a correction path
     * wired to a null engine result tends to have. Both words are checked
     * against the whole field, so a commit that ate the one before it is as
     * visible as one that rewrote it.
     */
    @Test
    fun `with no dictionary the composed word lands untouched`() {
        val editor = RecordingEditor()
        val service = keyboardOn(editor, withWords = false)

        type(service, "ngieeng")
        assertEquals("ngiêng", service.uiState.value.composingPreview)
        space(service)
        assertEquals("ngiêng ", editor.text.toString())

        // Nothing to restore, so nothing is restored — the plain reading, with
        // the first word still in front of it.
        type(service, "tieng")
        space(service)
        assertEquals("ngiêng tieng ", editor.text.toString())
    }
}
