package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.layout.ClipboardKeyAction
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.LongPressLetterActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 🌐 key's two Gboard-patches extras: the tap ignored straight out of
 * typing, and the slide onto a shortcut letter. Plus the upward key flick the
 * capital rides on, which shares the hint flick's shape test.
 */
class GlobeKeyTest {

    @Test
    fun `a tap inside the window after typing is ignored`() {
        assertTrue(globeTapGuarded(now = 10_150, lastTypedAt = 10_000, guardMs = 200))
    }

    @Test
    fun `a tap after the window switches`() {
        assertFalse(globeTapGuarded(now = 10_200, lastTypedAt = 10_000, guardMs = 200))
        assertFalse(globeTapGuarded(now = 20_000, lastTypedAt = 10_000, guardMs = 200))
    }

    @Test
    fun `the guard off or nothing typed never ignores a tap`() {
        assertFalse(globeTapGuarded(now = 10_010, lastTypedAt = 10_000, guardMs = 0))
        assertFalse(globeTapGuarded(now = 150, lastTypedAt = 0, guardMs = 1000))
    }

    @Test
    fun `a clock behind the last keystroke does not guard`() {
        assertFalse(globeTapGuarded(now = 9_000, lastTypedAt = 10_000, guardMs = 1000))
    }

    @Test
    fun `only keys that change the text arm the guard`() {
        assertTrue(KeyAction.Text.typesIntoField())
        assertTrue(KeyAction.Space.typesIntoField())
        assertTrue(KeyAction.Delete.typesIntoField())
        assertTrue(KeyAction.Enter.typesIntoField())
        assertFalse(KeyAction.Shift.typesIntoField())
        assertFalse(KeyAction.Symbols.typesIntoField())
        assertFalse(KeyAction.LanguageSwitch.typesIntoField())
    }

    @Test
    fun `sliding onto the six letters runs their shortcuts`() {
        val letters = LongPressLetterActions()
        assertEquals(ClipboardKeyAction.SELECT_ALL, globeDragAction(Key(label = "a"), letters))
        assertEquals(ClipboardKeyAction.COPY, globeDragAction(Key(label = "c"), letters))
        assertEquals(ClipboardKeyAction.PASTE, globeDragAction(Key(label = "V"), letters))
        assertEquals(ClipboardKeyAction.CUT, globeDragAction(Key(label = "x"), letters))
        assertEquals(ClipboardKeyAction.UNDO, globeDragAction(Key(label = "z"), letters))
        assertEquals(ClipboardKeyAction.REDO, globeDragAction(Key(label = "y"), letters))
    }

    @Test
    fun `any other key runs nothing`() {
        val letters = LongPressLetterActions()
        assertNull(globeDragAction(Key(label = "q"), letters))
        assertNull(globeDragAction(Key(label = "ch"), letters))
        assertNull(globeDragAction(Key(label = "⇧", action = KeyAction.Shift), letters))
    }

    @Test
    fun `rebound letters move the shortcuts with them`() {
        // A Bengali grid with the six on keys it draws.
        val letters = LongPressLetterActions(letters = "অকবখজয")
        assertEquals(ClipboardKeyAction.COPY, globeDragAction(Key(label = "ক"), letters))
        assertNull(globeDragAction(Key(label = "c"), letters))
    }

    @Test
    fun `only the globe starts the slide, and only when it is on`() {
        val globe = Key(label = "🌐", action = KeyAction.LanguageSwitch)
        assertTrue(globe.startsGlobeDrag(enabled = true))
        assertFalse(globe.startsGlobeDrag(enabled = false))
        assertFalse(Key(label = "a").startsGlobeDrag(enabled = true))
    }

    private fun straight(dy: Float, durationMs: Long = 90L): List<GesturePoint> =
        (0..6).map { GesturePoint(200f, 400f + dy * it / 6, durationMs * it / 6) }

    @Test
    fun `an upward flick is the up direction only`() {
        val up = straight(-100f)
        assertTrue(keyFlick(up, 100f, 50f, KeyFlickDirection.UP))
        assertFalse(keyFlick(up, 100f, 50f, KeyFlickDirection.DOWN))
        assertFalse(keyFlick(straight(100f), 100f, 50f, KeyFlickDirection.UP))
    }

    @Test
    fun `a slow or long stroke up is not a flick`() {
        assertFalse(keyFlick(straight(-100f, durationMs = 400L), 100f, 50f, KeyFlickDirection.UP))
        assertFalse(keyFlick(straight(-300f), 100f, 50f, KeyFlickDirection.UP))
        assertFalse(keyFlick(straight(-20f), 100f, 50f, KeyFlickDirection.UP))
    }
}
