package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OctopusSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #209, second half: the keys lit up in the middle of a swipe on a board
 * whose owner had allowed only next words onto them.
 *
 * "What may appear" ([OctopusSettings.kinds]) was read everywhere the buffer's
 * board is built and nowhere the stroke's board is, so a user who had turned
 * completions off still got a full board for as long as a finger was down and a
 * bare one the moment it lifted — the setting appearing to do nothing, and the
 * board flashing words at exactly the moment they are hardest to ignore.
 *
 * The alternates a stroke offers are completions by the same definition the
 * idle board uses: each one carries on the word the finger is drawing. So the
 * gate is the same gate, asked in the one place it was missing.
 *
 * Called directly rather than through `onGesturePreview`, which decodes before
 * it reaches any of this and wants a loaded engine no test in this module can
 * build.
 */
@RunWith(RobolectricTestRunner::class)
class OctopusGlideKindsTest {

    /** The leader the stroke is drawing, and two alternates that leave it. */
    private val reading = listOf("hello", "help", "held")

    /** A letters layer with a key per letter the words above need. */
    private val letters = KeyboardLayout(
        name = "test",
        rows = listOf("helodp".map { Key(it.toString()) }),
    )

    private fun state(kinds: Set<OctopusKind>) = glideReadyState(
        settings = KeyboardSettings(
            learnFromTyping = false,
            octopus = OctopusSettings(enabled = true, kinds = kinds),
        ),
    ).copy(layouts = LayoutSet(letters, letters, letters))

    @Test
    fun `a stroke floats its alternates when completions are allowed`() {
        val (service, _, _) = glideKeyboard()

        val board = service.octopusForGlide(state(OctopusKind.entries.toSet()), reading)

        // The premise of the test below: with the setting open, this board is
        // the thing the user was seeing.
        assertEquals(
            mapOf('p'.code to "help", 'd'.code to "held"),
            board.allWords().associate { it.keyCodePoint to it.word },
        )
    }

    @Test
    fun `a board that may show only next words stays bare under the finger`() {
        val (service, _, _) = glideKeyboard()

        val board = service.octopusForGlide(state(setOf(OctopusKind.NEXT_WORD)), reading)

        assertTrue(
            "the stroke floated completions onto a board that forbids them (#209)",
            board.isEmpty(),
        )
    }
}
