package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.prediction.OctopusWord
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * "Never suggest" takes the word off the strip that is on screen, not off the
 * next one (issue #127).
 *
 * The blacklist itself is a preference: `onSuggestionHeld` writes it, DataStore
 * stores it, and the settings collector hands the new set to the suggestion
 * engine. That round trip is what the user was waiting for — the word they had
 * just banned sat in the strip until the next keystroke rebuilt it. So the
 * service now also filters what it has already published, and that filtering is
 * what these tests pin.
 *
 * They are deliberately about the *publish* and not about the engine: this
 * module has no word list and `suggestionEngine` is assigned only inside
 * `loadDictionariesAndEmoji`, past an asset this source set cannot open (see
 * `OctopusGlideRefreshTest`). Every word below is therefore seeded straight
 * into the state, which is also what makes the tests discriminating — before
 * the fix the state was untouched, so the seeded word survives the hold.
 *
 * The pref write itself still runs, into a real DataStore under Robolectric's
 * own files — the repository is handed over by reflection because it is a
 * `lateinit` that only `onCreate` assigns, and `onCreate` cannot be called here
 * (no `ShadowInputMethodService`). Nothing below waits for that write or reads
 * it back: it is seeded so the launch has something to call rather than dying
 * on the `lateinit` and printing a stack trace through every run. What the
 * stored blacklist then does to the *next* strip is `SuggestionEngineTest`'s,
 * and always was.
 */
@RunWith(RobolectricTestRunner::class)
class NeverSuggestStripTest {

    private class Keyboard : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
    }

    /**
     * Publishes [state] the way the suggestion job would.
     *
     * Haptics are off for the same reason as in `ServiceStateWiringTest`: a
     * hold buzzes, and the buzz resolves the platform vibrator through a
     * service that never booted.
     */
    private fun seed(service: WMKeyboardService, state: KeyboardUiState) {
        val repository = WMKeyboardService::class.java.getDeclaredField("settingsRepository")
        repository.isAccessible = true
        repository.set(service, SettingsRepository(RuntimeEnvironment.getApplication()))
        val field = WMKeyboardService::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(service) as MutableStateFlow<KeyboardUiState>
        flow.value = state.copy(
            settings = state.settings.copy(haptics = HapticSettings(enabled = false)),
        )
    }

    private fun keyboardShowing(state: KeyboardUiState): WMKeyboardService =
        Keyboard().also { seed(it, state) }

    @Test
    fun `a held word leaves the strip it was held on`() {
        val service = keyboardShowing(
            KeyboardUiState(
                suggestions = listOf(FIRST, BANNED, LAST),
                settings = KeyboardSettings(learnFromTyping = false),
            ),
        )

        service.onSuggestionHeld(BANNED)

        assertEquals(listOf(FIRST, LAST), service.uiState.value.suggestions)
    }

    /**
     * The blacklist matches on the lowercased word, so the strip has to as
     * well — the chip that was held is drawn in whatever case the engine chose,
     * and banning "Beta" has to take "beta" with it.
     */
    @Test
    fun `the word leaves whatever case it was offered in`() {
        val service = keyboardShowing(
            KeyboardUiState(
                suggestions = listOf(FIRST, BANNED.replaceFirstChar { it.uppercase() }),
                settings = KeyboardSettings(learnFromTyping = false),
            ),
        )

        service.onSuggestionHeld(BANNED)

        assertEquals(listOf(FIRST), service.uiState.value.suggestions)
    }

    /**
     * The colour on the strip is a promise that a space will commit that word
     * (#90). Banning the word breaks the promise, so the colour goes with it.
     */
    @Test
    fun `the autocorrect promise on the banned word is withdrawn`() {
        val service = keyboardShowing(
            KeyboardUiState(
                suggestions = listOf(BANNED, LAST),
                autocorrectWord = BANNED,
                settings = KeyboardSettings(learnFromTyping = false),
            ),
        )

        service.onSuggestionHeld(BANNED)

        assertNull(service.uiState.value.autocorrectWord)
    }

    /**
     * The keys are the other surface the same words are offered on (#102), and
     * a word banned off the strip that stays floating over a key is the same
     * complaint one row down.
     */
    @Test
    fun `the banned word comes off the keys as well`() {
        // Keyed by the words' own first letters, which differ — two entries
        // that collided on one key would leave a single-entry map that the
        // assertion below could not tell a filtered map from.
        val floating = OctopusWord(
            keyCodePoint = BANNED.first().code,
            word = BANNED,
            typedChars = 1,
            kind = OctopusKind.COMPLETION,
            rank = 0,
        )
        val kept = floating.copy(keyCodePoint = LAST.first().code, word = LAST)
        val service = keyboardShowing(
            KeyboardUiState(
                octopus = mapOf(
                    floating.keyCodePoint to listOf(floating),
                    kept.keyCodePoint to listOf(kept),
                ),
                settings = KeyboardSettings(learnFromTyping = false),
            ),
        )

        service.onSuggestionHeld(BANNED)

        assertEquals(mapOf(kept.keyCodePoint to listOf(kept)), service.uiState.value.octopus)
    }

    /**
     * A word nobody banned is not collateral: the hold names one word and the
     * filter is on that word alone.
     */
    @Test
    fun `holding one word leaves the rest of the strip alone`() {
        val strip = listOf(FIRST, BANNED, LAST)
        val service = keyboardShowing(
            KeyboardUiState(
                suggestions = strip,
                settings = KeyboardSettings(learnFromTyping = false),
            ),
        )

        service.onSuggestionHeld("  ")

        assertEquals(strip, service.uiState.value.suggestions)
    }

    private companion object {
        const val FIRST = "alpha"
        const val BANNED = "beta"
        const val LAST = "gamma"
    }
}
