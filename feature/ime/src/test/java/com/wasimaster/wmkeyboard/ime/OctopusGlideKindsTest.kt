package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OctopusDuringGlide
import com.wasimaster.wmkeyboard.core.settings.OctopusSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the keys carry while a finger is down: issue #209's gate, and issue
 * #166's lever over it.
 *
 * **#209.** "What may appear" ([OctopusSettings.kinds]) was read everywhere the
 * buffer's board is built and nowhere the stroke's board is, so a user who had
 * turned completions off still got a full board for as long as a finger was
 * down and a bare one the moment it lifted — the setting appearing to do
 * nothing, and the board flashing words at exactly the moment they are hardest
 * to ignore. The alternates a stroke offers are completions by the same
 * definition the idle board uses: each one carries on the word the finger is
 * drawing. So the gate is the same gate, asked in the one place it was missing.
 *
 * **#166.** That gate was also the *only* way to quieten a stroke, and it is
 * shared with tap typing: a glide typist who wanted bare keys under the finger
 * had to give up completions while typing by hand. [OctopusSettings.duringGlide]
 * is the stroke's own lever, and it defaults to showing nothing.
 *
 * Called directly rather than through `onGesturePreview`, which decodes before
 * it reaches any of this and wants a loaded engine no test in this module can
 * build. That is also why the next-word choice is only tested through its
 * gate: the board behind it comes out of the engine.
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

    private fun state(
        kinds: Set<OctopusKind> = OctopusKind.entries.toSet(),
        duringGlide: OctopusDuringGlide = OctopusDuringGlide.ALTERNATES,
    ) = glideReadyState(
        settings = KeyboardSettings(
            learnFromTyping = false,
            octopus = OctopusSettings(
                enabled = true,
                kinds = kinds,
                duringGlide = duringGlide,
            ),
        ),
    ).copy(layouts = LayoutSet(letters, letters, letters))

    @Test
    fun `a stroke floats its alternates when it is asked for them`() {
        val (service, _, _) = glideKeyboard()

        val board = runBlocking { service.octopusForGlide(state(), reading) }

        // The premise of the two tests below: with both settings open, this
        // board is the thing the user was seeing.
        assertEquals(
            mapOf('p'.code to "help", 'd'.code to "held"),
            board.allWords().associate { it.keyCodePoint to it.word },
        )
    }

    @Test
    fun `a board that may show only next words stays bare under the finger`() {
        val (service, _, _) = glideKeyboard()

        val board = runBlocking {
            service.octopusForGlide(state(kinds = setOf(OctopusKind.NEXT_WORD)), reading)
        }

        assertTrue(
            "the stroke floated completions onto a board that forbids them (#209)",
            board.isEmpty(),
        )
    }

    @Test
    fun `the default leaves the keys bare for as long as the finger is down`() {
        val (service, _, _) = glideKeyboard()

        val board = runBlocking {
            service.octopusForGlide(state(duringGlide = OctopusDuringGlide.NOTHING), reading)
        }

        assertTrue(
            "a stroke drew alternates on a board asked to stay quiet (#166)",
            board.isEmpty(),
        )
        assertEquals(
            "the quiet board is what an untouched install gets",
            OctopusDuringGlide.NOTHING,
            OctopusSettings().duringGlide,
        )
    }

    @Test
    fun `the stroke's next words obey what may appear too`() {
        val (service, _, _) = glideKeyboard()

        val board = runBlocking {
            service.octopusForGlide(
                state(
                    kinds = setOf(OctopusKind.COMPLETION),
                    duringGlide = OctopusDuringGlide.NEXT_WORD,
                ),
                reading,
            )
        }

        assertTrue(
            "the stroke offered next words on a board that forbids them (#166)",
            board.isEmpty(),
        )
    }
}
