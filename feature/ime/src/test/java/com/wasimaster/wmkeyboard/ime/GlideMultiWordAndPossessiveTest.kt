package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.settings.GestureSettings
import com.wasimaster.wmkeyboard.core.settings.GlideApostropheKey
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * The two commits that are not one stroke, one word: [WMKeyboardService.onGestureWords],
 * which lands a stroke that crossed the spacebar, and the possessive swipe
 * ([WMKeyboardService.onPossessiveFlick], #169), which extends the word behind
 * the caret — glided or typed.
 *
 * Both are wired to rules that are tested as pure functions elsewhere —
 * `PossessiveFlickTest` owns the flick's geometry, `GlideSpaceTest` the spacing —
 * so nothing here re-asks a shape question. What is asked here is whether the
 * service is wired to those answers: which segment a held shift and a picked word
 * reach, whether the swipe edits the field it was told to, and
 * whether either path leaves the words on the keys behind (#118).
 *
 * Every test asserts what reached the [InputConnection] before it asserts state.
 * The glide scope carries no exception handler, so a throw inside a commit is
 * silent and reads exactly like a reverted fix; the recording connection is the
 * tripwire that tells the two apart.
 *
 * Deliberately out of reach here, and left untested rather than faked: this
 * module has no `SuggestionEngine` and no word list — they are `:app` assets, and
 * `:app` depends on this module rather than the other way round — so `glideDecode`
 * answers nothing on every stroke. Only a segment the picker explicitly answered
 * for can commit, and the picker's answer belongs to the last segment alone. So a
 * chained stroke commits exactly one word here, and the *sequence* a chain is for
 * — several words in order, spaced between them, the trailing space landing once
 * after the last — cannot be observed. The board assertions are about the publish
 * and not its content for the same reason: with no engine the map written is empty.
 *
 * The service is driven the way [OctopusGlideRefreshTest] drives it: a subclass
 * that attaches a context and hands back a recording connection, with `onCreate`
 * deliberately never called, because Robolectric has no shadow for
 * `InputMethodService` and none of this needs one.
 */
@RunWith(RobolectricTestRunner::class)
class GlideMultiWordAndPossessiveTest {

    /** The word every verdict here commits, so no decoder is needed. */
    private val word = "hello"

    /** What the field holds when a glide has landed [word] and spaced it. */
    private val glidedField = "$word "

    /** The default QWERTY set. Declared first: the centres below are read off it. */
    private val layouts = KeyboardUiState().layouts

    /** Every text key's centre, punctuation included — the same map the keyboard builds. */
    private val centers: Map<Int, Pair<Float, Float>> = buildMap {
        for ((rowIndex, row) in layouts.letters.rows.withIndex()) {
            var column = 0
            for (key in row) {
                if (key.action == KeyAction.Text) {
                    (key.output ?: key.label).firstOrNull()?.let {
                        putIfAbsent(
                            it.lowercaseChar().code,
                            (column * KEY_WIDTH) to (rowIndex * KEY_WIDTH),
                        )
                    }
                }
                column++
            }
        }
    }

    /** The glide grid, straight off the default QWERTY — no Compose, no measure pass. */
    private fun glideGrid(): List<KeyCenter> = layouts.glideKeys { centers[it] }

    /**
     * A plain left-to-right stroke along [row]. Its shape is irrelevant — nothing
     * in this module decodes one — but two segments are drawn along different rows
     * so a chained stroke is not two copies of the same gesture.
     */
    private fun stroke(row: Int): List<GesturePoint> = (0..STROKE_STEPS).map {
        GesturePoint(x = it * KEY_WIDTH, y = row * KEY_WIDTH, t = it * STROKE_STEP_MS)
    }

    /** A service, a field, and the board planted on it before the stroke. */
    private fun keyboard(
        octopus: OctopusBoard = emptyMap(),
        shiftState: ShiftState = ShiftState.OFF,
        gesture: GestureSettings = GestureSettings(),
        initial: String = "",
    ): Triple<WMKeyboardService, RecordingEditor, OctopusBoard> {
        val (service, editor, _) = glideKeyboard(
            glideReadyState(
                shiftState = shiftState,
                settings = KeyboardSettings(learnFromTyping = false, gesture = gesture),
                octopus = octopus,
            ),
            RecordingEditor(initial = initial),
        )
        return Triple(service, editor, octopus)
    }

    /**
     * Arms what a possessive flick reads behind it: the word it extends, and
     * whether the space in front of the caret is one the keyboard typed.
     *
     * Written straight into the fields rather than glided into place, because a
     * real glide first would put two strokes in one test: the second cancels the
     * first's job on entry while the first's publish sits in `NonCancellable`, so
     * which of the two lands last is not the test's to decide.
     */
    private fun armGlideTail(service: WMKeyboardService, glided: String, keyboardTypedSpace: Boolean) {
        val wordField = WMKeyboardService::class.java.getDeclaredField("lastGestureWord")
        wordField.isAccessible = true
        wordField.set(service, glided)
        val spaceField = WMKeyboardService::class.java.getDeclaredField("pendingWordSpace")
        spaceField.isAccessible = true
        spaceField.setBoolean(service, keyboardTypedSpace)
    }

    /**
     * The shift the board is holding reaches a chained stroke at all.
     *
     * On its own this is half a rule; it is here so the two tests below cannot
     * pass by the capitalisation having quietly gone away altogether.
     *
     * Reddens if `onGestureWords` stops asking about the shift — `val word = leader`
     * in place of the `when (shiftAtGesture)` block.
     */
    @Test
    fun `a one-segment chained stroke takes the shift the board was holding`() {
        val (service, editor, stale) = keyboard(octopus = octopusSentinel(), shiftState = ShiftState.ON)

        service.onGestureWords(listOf(stroke(0)), glideGrid(), KEY_WIDTH, GlideVerdict.Word(word))
        settle { service.uiState.value.octopus != stale }

        assertEquals("Hello ", editor.text.toString())
    }

    /**
     * The picker answered about the segment the finger stopped on, which is the
     * last one. A stroke can only pause to ask on the word it is drawing, so the
     * pick must not be spent again on the segments behind it.
     *
     * With no decoder the earlier segment has nothing else to commit, so the field
     * holding one word — and one trailing space — is the whole assertion.
     *
     * Reddens if the pick stops being scoped to the last segment:
     * `val picked = chosen` in place of `chosen?.takeIf { index == segments.lastIndex }`
     * commits it twice, and the leading-space rule puts a gap between them, so the
     * field reads "hello hello ".
     */
    @Test
    fun `the picker's word reaches the last segment of a chained stroke and no other`() {
        val (service, editor, stale) = keyboard(octopus = octopusSentinel())

        service.onGestureWords(
            listOf(stroke(0), stroke(1)),
            glideGrid(),
            KEY_WIDTH,
            GlideVerdict.Word(word),
        )
        settle { service.uiState.value.octopus != stale }

        assertEquals(glidedField, editor.text.toString())
    }

    /**
     * Only the first word of a chained stroke is the one the shift was put up for.
     * The words after it were never in front of that decision — the finger had
     * already crossed the spacebar — so they land in whatever case the word list
     * gave them.
     *
     * Reddens if the guard on the capitalisation goes: `if (index == 0)` dropped
     * from `onGestureWords` shifts the last segment too and the field reads
     * "Hello ". The test above is what stops this one passing by the
     * capitalisation having disappeared instead.
     */
    @Test
    fun `a held shift does not reach a later segment of a chained stroke`() {
        val (service, editor, stale) = keyboard(octopus = octopusSentinel(), shiftState = ShiftState.ON)

        service.onGestureWords(
            listOf(stroke(0), stroke(1)),
            glideGrid(),
            KEY_WIDTH,
            GlideVerdict.Word(word),
        )
        settle { service.uiState.value.octopus != stale }

        assertEquals(glidedField, editor.text.toString())
    }

    /**
     * Issue #118 on the chained path: a glide never calls `refreshSuggestions`,
     * which was the only writer of the words floating over the keys, so the keys
     * carried whatever they had been carrying before the stroke.
     *
     * The publish is asserted, not its content: the fix writes the board
     * unconditionally, so the sentinel is gone even though the map written here is
     * empty. Reddens if `octopus = floating` is dropped from the final
     * `_uiState.update` of `onGestureWords`.
     */
    @Test
    fun `a chained stroke refreshes the words on the keys`() {
        val (service, editor, stale) = keyboard(octopus = octopusSentinel())

        service.onGestureWords(listOf(stroke(0)), glideGrid(), KEY_WIDTH, GlideVerdict.Word(word))
        settle { service.uiState.value.octopus != stale }

        // First, that the commit ran at all. The publish is the line after it, so
        // an unchanged board only means something once the word has landed.
        assertEquals(glidedField, editor.text.toString())
        assertNotEquals(
            "a chained stroke left the words on the keys behind (#118)",
            stale,
            service.uiState.value.octopus,
        )
    }

    /**
     * The swipe appends `'s` to the word behind the caret, and the glide's own
     * trailing space is taken back first so the possessive goes inside it rather
     * than after it: "hello " becomes "hello's ", never "hello 's" (#169).
     *
     * Asserted with no settle, and that is itself part of the claim: the swipe is
     * answered on the caller's thread, because there is nothing to decode.
     *
     * Reddens if the take-back goes — `ic.deleteSurroundingText(spaces, 0)`
     * deleted from `onPossessiveFlick` leaves the field reading "hello 's ".
     */
    @Test
    fun `the possessive swipe puts the glide's space behind the 's`() {
        val (service, editor, _) = keyboard(
            gesture = GestureSettings(possessiveKey = GlideApostropheKey.COMMA),
            initial = glidedField,
        )
        armGlideTail(service, word, keyboardTypedSpace = true)

        assertTrue(service.onPossessiveFlick())

        assertEquals("${word}'s ", editor.text.toString())
    }

    /**
     * Issue #169's own case: the word was tapped out and the user typed the space
     * after it themselves. The swipe is a gesture in its own right, not a glide's
     * tail, so the word behind the caret is enough — no glide has to have
     * happened — and the space moves behind the possessive all the same. The
     * request was explicit that a space after the word must not split it off.
     *
     * Reddens if the swipe goes back to asking for `lastGestureWord` first: the
     * field is left at "hello " and the call answers false.
     */
    @Test
    fun `the possessive swipe extends a typed word and moves its space`() {
        val (service, editor, _) = keyboard(
            gesture = GestureSettings(possessiveKey = GlideApostropheKey.COMMA),
            initial = glidedField,
        )

        assertTrue(service.onPossessiveFlick())

        assertEquals("${word}'s ", editor.text.toString())
    }

    /** A word with nothing after it takes the possessive straight on the end. */
    @Test
    fun `the possessive swipe extends a word with no space after it`() {
        val (service, editor, _) = keyboard(
            gesture = GestureSettings(possessiveKey = GlideApostropheKey.COMMA),
            initial = word,
        )

        assertTrue(service.onPossessiveFlick())

        assertEquals("${word}'s", editor.text.toString())
    }

    /**
     * With no word behind the caret the swipe answers false and touches nothing,
     * which is what lets the grid hand the lift back to the key it began on.
     */
    @Test
    fun `the possessive swipe declines an empty field`() {
        val (service, editor, _) = keyboard(
            gesture = GestureSettings(possessiveKey = GlideApostropheKey.COMMA),
            initial = "",
        )

        assertFalse(service.onPossessiveFlick())

        assertEquals("", editor.text.toString())
    }

    /** Off is off: the default key adds nothing, whatever is behind the caret. */
    @Test
    fun `the possessive swipe is inert while no key is chosen`() {
        val (service, editor, _) = keyboard(initial = glidedField)

        assertFalse(service.onPossessiveFlick())

        assertEquals(glidedField, editor.text.toString())
    }

    /**
     * Issue #118 on the possessive path. The swipe rewrote the word behind the
     * caret, so the keys are answering about a word that is no longer there — the
     * one case where the board is stale without any decode having happened at all.
     *
     * Reddens if the `nextWordOctopus` publish is dropped from `onPossessiveFlick`.
     */
    @Test
    fun `the possessive swipe refreshes the words on the keys`() {
        val (service, editor, stale) = keyboard(
            octopus = octopusSentinel(),
            gesture = GestureSettings(possessiveKey = GlideApostropheKey.COMMA),
            initial = glidedField,
        )
        armGlideTail(service, word, keyboardTypedSpace = true)

        assertTrue(service.onPossessiveFlick())
        settle { service.uiState.value.octopus != stale }

        // The swipe's own edit first: without it the stroke was never taken as a
        // possessive at all, and the board would be untouched for that reason.
        assertEquals("${word}'s ", editor.text.toString())
        assertNotEquals(
            "the possessive swipe left the words on the keys behind (#118)",
            stale,
            service.uiState.value.octopus,
        )
    }

    private companion object {
        /** Grid units, and the width a stroke is measured against. */
        const val KEY_WIDTH = 60f

        /** Sample count of every synthetic stroke, and the gap between samples. */
        const val STROKE_STEPS = 5
        const val STROKE_STEP_MS = 20L

    }
}
