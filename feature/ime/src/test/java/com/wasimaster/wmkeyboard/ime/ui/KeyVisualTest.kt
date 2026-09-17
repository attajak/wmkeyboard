package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.ui.graphics.Color
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.ime.EnterAction
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ShiftState
import com.wasimaster.wmkeyboard.ime.shiftCased
import com.wasimaster.wmkeyboard.core.prediction.OctopusKind
import com.wasimaster.wmkeyboard.core.prediction.OctopusWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The keys are handed a resolved [KeyVisual] instead of the whole
 * [KeyboardUiState] so that a keystroke — which publishes a fresh state carrying
 * new suggestions and a new composing preview — leaves every key's parameters
 * untouched, and all ~40 key bodies skip recomposition instead of re-running.
 *
 * That only holds while nothing per-keystroke leaks into the resolution, which
 * is a one-line mistake to make. These tests are the guard: the first one fails
 * the moment a key starts reading something a keypress changes.
 */
class KeyVisualTest {

    private val palette = KeyPalette(
        key = Color.White,
        keyText = Color.Black,
        modifierKey = Color.Gray,
        modifierKeyText = Color.DarkGray,
        enterKey = Color.Blue,
        enterKeyText = Color.Yellow,
        pressedKey = Color.LightGray,
        accent = Color.Magenta,
    )

    private fun state(settings: KeyboardSettings = KeyboardSettings()) = KeyboardUiState(
        settings = settings,
        layouts = LayoutSet(
            BuiltInLayouts.QWERTY.compile(LayoutLayer.LETTERS),
            BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS),
            BuiltInLayouts.QWERTY.compile(LayoutLayer.SYMBOLS_SHIFTED),
        ),
    )

    /** Every key of the letters layer, resolved. */
    private fun visuals(state: KeyboardUiState) =
        currentLayout(state).rows.flatten().map { keyVisual(it, state, palette) }

    private fun keyLabelled(state: KeyboardUiState, label: String) =
        keyVisual(Key(label), state, palette)

    @Test
    fun `a keystroke changes nothing any key draws`() {
        val before = state()
        // What the service publishes on a keypress: a word committed to the
        // composing buffer, fresh suggestions, a fresh next-letter distribution.
        val after = before.copy(
            suggestions = listOf("hello", "help", "held"),
            composingPreview = "hel",
            nextLetterBias = mapOf('l' to 0.8f, 'p' to 0.3f),
            glideWord = "hello",
            expandedCandidates = listOf("hello"),
            octopus = mapOf(
                'l'.code to listOf(OctopusWord('l'.code, "hello", 3, OctopusKind.COMPLETION, 0)),
            ),
        )
        assertNotEquals(before, after)
        assertEquals(visuals(before), visuals(after))
    }

    /**
     * The counterweight to the test above: if resolution ignored the state
     * outright the first test would pass vacuously, so check that the things a
     * key *does* track still come through.
     */
    @Test
    fun `shift recases the letter labels`() {
        val off = state()
        val on = off.copy(shiftState = ShiftState.ON)
        assertEquals("q", keyLabelled(off, "q").label)
        assertEquals("Q", keyLabelled(on, "q").label)
        assertNotEquals(visuals(off), visuals(on))
    }

    /**
     * Fancy Text: the layout stores plain letters and the style restyles them
     * at draw time — the session override included, which is why it sits in
     * rememberKeyGrid's key list.
     */
    @Test
    fun `the fancy style restyles the letter labels, and only for fancy`() {
        val fancy = state().copy(language = LanguageRegistry.byId("fancy"))
        assertEquals("the persisted default (bold)", "𝐪", keyLabelled(fancy, "q").label)
        assertEquals(
            "the session override wins over the persisted pick",
            "𝔮",
            keyLabelled(fancy.copy(activeFancyStyleId = "fraktur"), "q").label,
        )
        assertEquals(
            "shift reaches the style's capital through the plain uppercase",
            "𝐐",
            keyLabelled(fancy.copy(shiftState = ShiftState.ON), "q").label,
        )
        assertEquals("every other language is untouched", "q", keyLabelled(state(), "q").label)
    }

    /**
     * The spoken name is compared as a [SpokenLabel], not as English: this test
     * runs on the JVM with no resources to resolve against, and naming the
     * resource is what the assertion is really about. Wording changes in the
     * XML; which resource a key points at is the behaviour.
     */
    @Test
    fun `the shift key's own icon and spoken name track the shift state`() {
        val shift = Key("⇧", action = KeyAction.Shift)
        val off = keyVisual(shift, state(), palette)
        assertEquals(IconSlots.KEY_SHIFT, off.iconSlot)
        assertEquals(SpokenLabel(R.string.ime_key_shift), off.spoken)
        assertEquals(false, off.iconActive)

        val on = keyVisual(shift, state().copy(shiftState = ShiftState.ON), palette)
        assertEquals(SpokenLabel(R.string.ime_key_shift_on), on.spoken)

        val locked = keyVisual(shift, state().copy(shiftState = ShiftState.CAPS_LOCK), palette)
        assertEquals(IconSlots.KEY_SHIFT_LOCK, locked.iconSlot)
        assertEquals(SpokenLabel(R.string.ime_key_caps_lock_on), locked.spoken)
        assertEquals(true, locked.iconActive)
    }

    /**
     * An app-supplied actionLabel is drawn as wording, not an icon — that is the
     * whole point of it, and no icon can stand in for what the app chose.
     */
    @Test
    fun `an app-supplied enter label wins over the enter icon`() {
        val enter = Key("⏎", action = KeyAction.Enter)
        val custom = keyVisual(
            enter,
            state().copy(enterAction = EnterAction.CUSTOM, enterActionLabel = "Post"),
            palette,
        )
        assertEquals("Post", custom.enterLabel)
        // Wording the app chose is not ours to translate, so it rides in the
        // label's own text rather than in a resource.
        assertEquals(SpokenLabel(text = "Post"), custom.spoken)

        val search = keyVisual(enter, state().copy(enterAction = EnterAction.SEARCH), palette)
        assertNull(search.enterLabel)
        assertEquals(IconSlots.KEY_ENTER_SEARCH, search.iconSlot)
        assertEquals(SpokenLabel(R.string.ime_enter_search), search.spoken)
    }

    /**
     * Enter is the one key whose label colour flips under the finger: its own
     * text colour is picked for its accented face, which the press paints over.
     */
    @Test
    fun `only the enter key recolours its label on press`() {
        val enter = keyVisual(Key("⏎", action = KeyAction.Enter), state(), palette)
        assertEquals(palette.enterKeyText, enter.contentColor)
        assertEquals(palette.modifierKeyText, enter.pressedContentColor)

        val letter = keyLabelled(state(), "q")
        assertEquals(letter.contentColor, letter.pressedContentColor)
    }

    @Test
    fun `shift capitalises a key's alternates, and only on a key that has some`() {
        val accents = Key("e", longPress = listOf("3", "é", "è"))
        assertEquals(false, keyVisual(accents, state(), palette).alternatesShifted)
        assertEquals(
            true,
            keyVisual(accents, state().copy(shiftState = ShiftState.ON), palette).alternatesShifted,
        )
        assertEquals(
            true,
            keyVisual(accents, state().copy(shiftState = ShiftState.CAPS_LOCK), palette).alternatesShifted,
        )
        // A bare key must not change under shift, or a shift press recomposes it.
        val plain = Key(",")
        assertEquals(
            keyVisual(plain, state(), palette).alternatesShifted,
            keyVisual(plain, state().copy(shiftState = ShiftState.ON), palette).alternatesShifted,
        )
        assertEquals("É", shiftCased("é", shifted = true))
        assertEquals("3", shiftCased("3", shifted = true))
        assertEquals("é", shiftCased("é", shifted = false))
    }
}
