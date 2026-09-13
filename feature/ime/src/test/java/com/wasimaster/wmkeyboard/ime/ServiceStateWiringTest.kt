package com.wasimaster.wmkeyboard.ime

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * One branch of [WMKeyboardService], and the three other entry points that have
 * to agree with it about when it fires.
 *
 * The branch is in `onSuggestionTapped`: with nothing composing and a word
 * behind the caret that the keyboard put there itself, a tap on the strip
 * *deletes* that word — space included — before committing the pick, because
 * the strip is showing the alternates of the swipe that wrote it. That is
 * correct there and wrong nearly everywhere else, and the service carries three
 * separate answers to it:
 *
 *  - `onOctopusPick` exists as its own path rather than a detour through
 *    `onSuggestionTapped` precisely so a word taken off the *keys* is not
 *    caught by it (discussion #102). Glide "hello", flick `w` for "world", and
 *    the detour would leave "world" standing where "hello " was.
 *  - the same method then arms `lastGestureWord` with its own word — one
 *    backspace takes the pick back whole — but *not* `stripReplacesGestureWord`:
 *    the pick ends with an ordinary refresh, so the strip holds what may follow
 *    the word rather than what it might have been, and a tap there lands after
 *    the pick (#175). Only a glide, which puts the stroke's own alternates on
 *    the strip, arms both.
 *  - `onCursorMove` clears the flag, because once the caret has moved the strip
 *    is no longer talking about the word behind it.
 *
 * All four tests turn on the same handful of characters reaching the field, so
 * each is written to fail loudly on the one-line change that would break it —
 * named in the KDoc above each. The middle one is the anchor: it is the test
 * that would go red if this harness could not make the branch fire at all,
 * which is what would otherwise let the other three pass for the wrong reason.
 *
 * What this file deliberately leaves alone: the spacing and casing rules
 * themselves (`GlideSpaceTest`, `CaretSpacingTest` own them as pure functions —
 * the interest here is only in which text survives a commit, so every
 * assertion is trimmed), the decode (there is no suggestion engine and no word
 * list in this module, so every stroke reads as nothing and the words below all
 * arrive as explicit picks), and anything that needs a caret the field actually
 * moves — see [RecordingEditor].
 *
 * The service is driven the way `OctopusGlideRefreshTest` drives it: a subclass
 * that attaches a context and hands back a recording connection, with `onCreate`
 * deliberately never called — Robolectric has no shadow for `InputMethodService`.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceStateWiringTest {

    /** The word every glide below commits, since no decoder is available to find one. */
    private val glided = "hello"

    /** The word every pick below takes, off the keys or off the strip. */
    private val picked = "world"

    /**
     * A service holding a glided word, and the field that recorded it.
     *
     * The seeded state carries three things that read as tidiness and are not.
     * [glideReadyState] documents the first two; the third is haptics, off
     * because a pick buzzes, the buzz resolves the platform vibrator through
     * the application context, and none of that is what these tests ask about.
     *
     * The settle waits on the strip rather than the field: it is what the glide
     * job publishes last, after the decode, the commit and the hop that samples
     * the stroke's shape, so a non-empty one means the whole job finished and
     * not merely that it started.
     */
    private fun glideOnce(): Pair<WMKeyboardService, RecordingEditor> {
        val (service, editor, _) = glideKeyboard(
            glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    haptics = HapticSettings(enabled = false),
                ),
            ),
        )
        service.onGesture(straightStroke(), glideKeyGrid(), GLIDE_KEY_WIDTH, GlideVerdict.Word(glided))
        settle { service.uiState.value.suggestions.isNotEmpty() }
        // Every test below reads what happened *to* this word, so a job that
        // died on its way here has to be told apart from one that ran and left
        // the word alone.
        assertEquals("the glide never reached the field", glided, editor.text.toString().trim())
        return service to editor
    }
    /**
     * Reddens if `onOctopusPick`'s body is replaced by a call to
     * `onSuggestionTapped`, which is the shape it deliberately is not.
     */
    @Test
    fun `a word taken off the keys is added to the glided word, not put in its place`() {
        val (service, editor) = glideOnce()

        service.onOctopusPick(picked, OctopusSource.FLICK)

        assertEquals("$glided $picked", editor.text.toString().trim())
    }

    /**
     * The anchor: it is what says the deletion branch can fire in this harness
     * at all, so the three tests that assert it did *not* fire mean something.
     *
     * Reddens if the `lastGestureWord` branch is dropped from
     * `onSuggestionTapped`, or if the glide stops arming `lastGestureWord`.
     */
    @Test
    fun `a word taken off the strip is put in the glided word's place`() {
        val (service, editor) = glideOnce()

        service.onSuggestionTapped(picked)

        assertEquals(picked, editor.text.toString().trim())
    }

    /**
     * Reddens if `lastGestureWord = null` is dropped from `onCursorMove`, or if
     * the direction of the arrow it sends is flipped.
     *
     * The caret does not really move here — the recording connection has no
     * caret to move — so what is under test is the flag and not the arithmetic
     * behind it. That is the right half to pin: on a real field the text before
     * a caret nudged one place left still ends in the glided word, so the flag
     * is the only thing standing between a scrubbed caret and a strip tap that
     * eats a word the user has moved away from.
     */
    @Test
    fun `moving the caret after a glide leaves the next strip pick standing on its own`() {
        val (service, editor) = glideOnce()

        service.onCursorMove(CARET_LEFT)

        // First that the move ran at all: everything it is meant to have
        // cleared is invisible until something asks for it.
        assertTrue("no arrow reached the editor", KeyEvent.KEYCODE_DPAD_LEFT in editor.keys)
        assertFalse("the caret went the wrong way", KeyEvent.KEYCODE_DPAD_RIGHT in editor.keys)

        service.onSuggestionTapped(picked)

        assertEquals("$glided $picked", editor.text.toString().trim())
    }

    /**
     * The other half of the octopus pick (#175): it hands its word over for
     * the backspace, but the strip it leaves behind holds next-word
     * predictions, not alternates, so a tap there follows the pick rather
     * than replacing it. Pick "hello" off a key, tap "world" on the strip:
     * "hello world", not "world".
     *
     * Reddens if `onOctopusPick` arms `stripReplacesGestureWord`, or if the
     * `onSuggestionTapped` branch stops reading it.
     */
    @Test
    fun `a word taken off the keys is followed by the next strip pick, not replaced by it`() {
        val (service, editor, _) = glideKeyboard()

        service.onOctopusPick(glided, OctopusSource.TAP)
        assertEquals("the pick never reached the field", glided, editor.text.toString().trim())

        service.onSuggestionTapped(picked)

        assertEquals("$glided $picked", editor.text.toString().trim())
    }

    private companion object {
        /** [WMKeyboardService.onCursorMove]'s argument for one step left. */
        const val CARET_LEFT = -1
    }
}
