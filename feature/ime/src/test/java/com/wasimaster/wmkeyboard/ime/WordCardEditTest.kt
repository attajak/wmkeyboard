package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The held-word card edits the word, not just its rank (issue #138).
 *
 * The card is a window over the whole keyboard, so it cannot be typed into: a
 * respelling hands the keys to a bar in the suggestion strip's row, and the
 * card comes back on whatever that bar spells. These tests are about that
 * hand-over — that the keys reach the draft and nothing else, and that
 * applying one moves the word in the personal dictionary — because that is the
 * half a Compose preview cannot show and the half that could quietly type into
 * the user's text.
 *
 * The personal dictionary is a real [UserLexicon] over no file, planted the way
 * `NeverSuggestStripTest` plants the settings repository: it is a `lateinit`
 * that only `onCreate` assigns, and `onCreate` cannot run here (no
 * `ShadowInputMethodService`). Everything else the respell touches — the rank
 * offsets, the swipe shapes, the waiting room — is already a fileless instance
 * on a fresh service.
 */
@RunWith(RobolectricTestRunner::class)
class WordCardEditTest {

    private class Keyboard(private val editor: RecordingEditor) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection() = editor
    }

    /**
     * A keyboard with a personal dictionary, a field, and the card already
     * open on [word].
     *
     * Haptics are off for the reason `NeverSuggestStripTest` gives: every
     * action here buzzes, and the buzz resolves a vibrator through a service
     * that never booted. `fieldNoSuggestions` keeps `refreshSuggestions` out of
     * the snippet store, one more `lateinit` of `onCreate`'s.
     */
    private fun cardOn(
        word: String,
        seed: (UserLexicon) -> Unit = {},
    ): Triple<Keyboard, RecordingEditor, UserLexicon> {
        val editor = RecordingEditor()
        val service = Keyboard(editor)
        val lexicon = UserLexicon(null).also(seed)
        plant(service, "userLexicon", lexicon)
        plant(service, "settingsRepository", SettingsRepository(RuntimeEnvironment.getApplication()))
        val field = WMKeyboardService::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(service) as MutableStateFlow<KeyboardUiState>
        flow.value = KeyboardUiState(
            settings = KeyboardSettings(
                learnFromTyping = false,
                haptics = HapticSettings(enabled = false),
            ),
            fieldNoSuggestions = true,
        )
        service.onWordMenuAction(WordMenuAction.Open(word))
        return Triple(service, editor, lexicon)
    }

    private fun plant(service: WMKeyboardService, name: String, value: Any) {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(service, value)
    }

    private val backspace = Key(label = "⌫", action = KeyAction.Delete)
    private val shift = Key(label = "⇧", action = KeyAction.Shift)

    @Test
    fun `the card opens knowing whether the capitals are pinned`() {
        val (service, _, _) = cardOn(PINNED) { it.addWord(PINNED, caseEvidence = true) }
        assertTrue(service.uiState.value.wordCard?.casePinned == true)
    }

    @Test
    fun `editing the spelling gives the keys to the bar, starting from the word`() {
        val (service, _, _) = cardOn(TYPO)
        service.onWordCardAction(WordCardAction.EditSpelling)
        assertEquals(TYPO, service.uiState.value.wordSpell?.draft)
        // The card steps aside for the keys, but is still what comes back.
        assertTrue(service.uiState.value.keysTakenByKeyboard)
        assertEquals(TYPO, service.uiState.value.wordCard?.word)
    }

    @Test
    fun `what is typed at the bar reaches the draft and not the field`() {
        val (service, editor, _) = cardOn(TYPO)
        service.onWordCardAction(WordCardAction.EditSpelling)
        service.onKey(backspace)
        service.onKey(backspace)
        service.onText("e")
        assertEquals("te", service.uiState.value.wordSpell?.draft)
        assertEquals("", editor.text.toString())
    }

    @Test
    fun `space and enter at the bar never reach the field`() {
        val (service, editor, _) = cardOn(TYPO)
        service.onWordCardAction(WordCardAction.EditSpelling)
        service.onKey(Key(label = "space", action = KeyAction.Space))
        assertEquals(TYPO, service.uiState.value.wordSpell?.draft)
        // Enter is the bar's tick: it applies the draft, which is the word
        // itself here, so nothing moves and the bar closes.
        service.onKey(Key(label = "⏎", action = KeyAction.Enter))
        assertNull(service.uiState.value.wordSpell)
        assertEquals("", editor.text.toString())
    }

    @Test
    fun `applying a respelling moves the word in the personal dictionary`() {
        val (service, _, lexicon) = cardOn(TYPO) { it.addWord(TYPO, caseEvidence = true) }
        service.onWordCardAction(WordCardAction.EditSpelling)
        service.onKey(backspace)
        service.onKey(backspace)
        service.onKey(backspace)
        service.onText("the")
        service.onWordCardAction(WordCardAction.CommitSpelling)
        assertTrue(lexicon.contains("the"))
        assertFalse(lexicon.contains(TYPO))
        // The card comes back on the word that is now there.
        assertEquals("the", service.uiState.value.wordCard?.word)
        assertNull(service.uiState.value.wordSpell)
    }

    @Test
    fun `a respelling that only moves capitals pins the new spelling`() {
        val (service, _, lexicon) = cardOn("boston") {
            // Learned off ordinary typing, so its capitals are still voted on.
            it.addWord("boston", caseEvidence = false)
        }
        service.onWordCardAction(WordCardAction.EditSpelling)
        repeat("boston".length) { service.onKey(backspace) }
        service.onText("Boston")
        service.onWordCardAction(WordCardAction.CommitSpelling)
        assertTrue(lexicon.isCasePinned("boston"))
        assertEquals("Boston", lexicon.displayOf("boston"))
    }

    @Test
    fun `respelling a word the dictionary does not have adds the new spelling`() {
        val (service, _, lexicon) = cardOn("iphone")
        service.onWordCardAction(WordCardAction.EditSpelling)
        repeat("iphone".length) { service.onKey(backspace) }
        service.onText("iPhone")
        service.onWordCardAction(WordCardAction.CommitSpelling)
        assertTrue(lexicon.contains("iphone"))
        assertEquals("iPhone", lexicon.displayOf("iphone"))
    }

    @Test
    fun `cancelling leaves the word spelled as it was`() {
        val (service, _, lexicon) = cardOn(TYPO) { it.addWord(TYPO, caseEvidence = true) }
        service.onWordCardAction(WordCardAction.EditSpelling)
        repeat(3) { service.onKey(backspace) }
        service.onText("the")
        service.onWordCardAction(WordCardAction.CancelSpelling)
        assertNull(service.uiState.value.wordSpell)
        assertTrue(lexicon.contains(TYPO))
        assertFalse(lexicon.contains("the"))
    }

    @Test
    fun `the keep-capitals switch pins and releases the spelling`() {
        val (service, _, lexicon) = cardOn(PINNED) { it.addWord(PINNED, caseEvidence = false) }
        service.onWordCardAction(WordCardAction.SetCasePinned(true))
        assertTrue(lexicon.isCasePinned(PINNED))
        assertTrue(service.uiState.value.wordCard?.casePinned == true)
        service.onWordCardAction(WordCardAction.SetCasePinned(false))
        assertFalse(lexicon.isCasePinned(PINNED))
        assertFalse(service.uiState.value.wordCard?.casePinned == true)
    }

    @Test
    fun `a spacebar scrub moves the bar's caret and never the field's`() {
        val (service, editor, _) = cardOn(TYPO)
        service.onWordCardAction(WordCardAction.EditSpelling)
        service.onCursorMove(-1)
        service.onText("x")
        assertEquals("texh", service.uiState.value.wordSpell?.draft)
        assertEquals("", editor.text.toString())
        assertTrue(editor.keys.isEmpty())
    }

    @Test
    fun `backspace deletes at the bar's caret`() {
        val (service, _, _) = cardOn(TYPO)
        service.onWordCardAction(WordCardAction.EditSpelling)
        service.onCursorMove(-1)
        service.onKey(backspace)
        assertEquals("th", service.uiState.value.wordSpell?.draft)
    }

    @Test
    fun `shift re-cases a selection drawn on the bar`() {
        val (service, editor, _) = cardOn(TYPO)
        service.onWordCardAction(WordCardAction.EditSpelling)
        service.onWordCardAction(WordCardAction.SelectSpelling(0, TYPO.length))
        service.onKey(shift)
        assertEquals("Teh", service.uiState.value.wordSpell?.draft)
        service.onKey(shift)
        assertEquals("TEH", service.uiState.value.wordSpell?.draft)
        // Spent on the selection, not armed for the next letter.
        assertEquals(ShiftState.OFF, service.uiState.value.shiftState)
        assertEquals("", editor.text.toString())
    }

    @Test
    fun `typing over a selection on the bar replaces it`() {
        val (service, _, _) = cardOn(TYPO)
        service.onWordCardAction(WordCardAction.EditSpelling)
        service.onWordCardAction(WordCardAction.SelectSpelling(1, 3))
        service.onText("he")
        assertEquals("the", service.uiState.value.wordSpell?.draft)
    }

    @Test
    fun `an empty draft is not a delete by the back door`() {
        val (service, _, lexicon) = cardOn(TYPO) { it.addWord(TYPO, caseEvidence = true) }
        service.onWordCardAction(WordCardAction.EditSpelling)
        repeat(TYPO.length) { service.onKey(backspace) }
        assertEquals("", service.uiState.value.wordSpell?.draft)
        service.onWordCardAction(WordCardAction.CommitSpelling)
        assertNull(service.uiState.value.wordSpell)
        assertTrue(lexicon.contains(TYPO))
    }
}

/** The word the issue is about: learned with the wrong letters. */
private const val TYPO = "teh"

/** A word whose capitals are the point. */
private const val PINNED = "Wasi"
