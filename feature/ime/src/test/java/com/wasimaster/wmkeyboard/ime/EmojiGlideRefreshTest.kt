package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Issue #125: a glide left the emoji chips behind.
 *
 * The same shape as #118 and its [OctopusGlideRefreshTest], one slot over. A
 * glide is the one commit that never calls `refreshSuggestions` — the strip has
 * to keep the stroke's own alternates so a misread word can be tapped away — and
 * `emojiSuggestions` was only ever written by that method. So `onGesture`
 * published the strip (and, since #118, the keys) and nothing else, and the
 * emoji beside the words stayed the ones the word *before* the stroke had
 * earned, swipe after swipe, until a space typed by hand finally ran a refresh.
 *
 * What this asserts is **the publish, not the content**, for the reasons spelled
 * out in [OctopusGlideRefreshTest]: a sentinel is planted on the strip before the
 * stroke, and the fix writes `emojiSuggestions` unconditionally, so the sentinel
 * is gone afterwards even where the list it writes is empty. That is what lets
 * the test run with no dictionary, no assets and no settings store.
 */
@RunWith(RobolectricTestRunner::class)
class EmojiGlideRefreshTest {

    /** The word the picker's verdict commits, so no decoder is needed. */
    private val word = "hello"

    /** Grid units, and the width a stroke is measured against. */
    private val keyWidth = 60f

    /**
     * Records what reached the field, and doubles as the tripwire: the commit
     * happens in the same coroutine as the publish and well ahead of it, so a
     * word in the field is what proves the glide job got that far. Without it a
     * throw anywhere in that job — the scope has no exception handler — reads
     * exactly like a reverted fix.
     */
    private class RecordingInputConnection(target: View) : BaseInputConnection(target, true) {
        val committed = StringBuilder()
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            committed.append(text ?: "")
            return true
        }
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence =
            committed.takeLast(n).toString()
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun finishComposingText(): Boolean = true
    }

    private class Keyboard(val ic: InputConnection) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = ic
    }

    /** The letter grid, straight off the default QWERTY — no Compose, no measure pass. */
    private fun letterKeys(): List<KeyCenter> {
        val layouts = KeyboardUiState().layouts
        val centers = buildMap {
            for ((rowIndex, row) in layouts.letters.rows.withIndex()) {
                var column = 0
                for (key in row) {
                    if (key.action == KeyAction.Text) {
                        (key.output ?: key.label).firstOrNull()?.let {
                            putIfAbsent(
                                it.lowercaseChar().code,
                                (column * keyWidth) to (rowIndex * keyWidth),
                            )
                        }
                    }
                    column++
                }
            }
        }
        return layouts.glideKeys { centers[it] }
    }

    /** A plain left-to-right stroke. Its shape is irrelevant: the verdict decides the word. */
    private fun stroke(): List<GesturePoint> =
        (0..5).map { GesturePoint(x = it * keyWidth, y = 0f, t = it * 20L) }

    /** The chips planted beside the strip before the stroke, and looked for afterwards. */
    private fun sentinel(): List<String> = listOf("🎂")

    /**
     * Writes the state a glide needs, without the half of the service that
     * builds it. `glideReady` is otherwise only set by a watcher that wants a
     * loaded dictionary, and `learnFromTyping` has to be off or the commit
     * dereferences the `lateinit` lexicon that only `onCreate` assigns.
     */
    private fun seed(service: WMKeyboardService, emoji: List<String>) {
        val field = WMKeyboardService::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(service) as kotlinx.coroutines.flow.MutableStateFlow<KeyboardUiState>
        // A glide's commit asks the snippet store whether the word is a trigger
        // (#170), and only onCreate attaches the store.
        plantPersonalStores(service)
        flow.value = KeyboardUiState(
            glideReady = true,
            // Not a gate a glide reads — allowsGestureTyping deliberately
            // ignores it — but insurance against the day something on the
            // commit path calls refreshSuggestions, which reaches a snippet
            // store that is one more lateinit only onCreate assigns.
            fieldNoSuggestions = true,
            emojiSuggestions = emoji,
            settings = KeyboardSettings(learnFromTyping = false),
        )
    }

    /** Runs the main looper until the glide's own thread hop has come back. */
    private fun settle(service: WMKeyboardService, before: List<String>) {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (service.uiState.value.emojiSuggestions != before) return
            Thread.sleep(SETTLE_STEP_MS)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a glided word refreshes the emoji chips`() {
        val ic = RecordingInputConnection(View(RuntimeEnvironment.getApplication()))
        val service = Keyboard(ic)
        val stale = sentinel()
        seed(service, stale)

        service.onGesture(stroke(), letterKeys(), keyWidth, GlideVerdict.Word(word))
        settle(service, stale)

        // First, that the glide actually ran. The publish is downstream of this,
        // so unchanged chips only mean something once the word has landed.
        assertEquals(word, ic.committed.toString().trim())
        // Then the fix itself: the slot was written, so the stale emoji is gone.
        assertNotEquals(
            "the emoji chips were left behind by the glide (#125)",
            stale,
            service.uiState.value.emojiSuggestions,
        )
    }

    private companion object {
        const val SETTLE_ROUNDS = 200
        const val SETTLE_STEP_MS = 10L
    }
}
