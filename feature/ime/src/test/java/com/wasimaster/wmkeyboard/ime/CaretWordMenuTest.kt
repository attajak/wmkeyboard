package com.wasimaster.wmkeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The word the held-word menu is about, when nothing is being typed (#263).
 *
 * `Add "yourword"` has always been about the word the user is working on
 * rather than the chip they held, and while a word is being typed that is the
 * composing buffer. A caret parked inside a word is the other half of it:
 * proofreading is exactly when the missing spelling turns up, and there is no
 * buffer then, so the menu offered no way in. These pin the fall-through.
 *
 * Deliberately about `typedWord` alone. The menu item past it asks the
 * personal dictionary whether the word is already in, and the strip that
 * carries the chip asks the engine for the rest of the row; neither exists in
 * this source set (see `NeverSuggestStripTest` for why), and neither is what
 * was wrong.
 */
@RunWith(RobolectricTestRunner::class)
class CaretWordMenuTest {

    private class Keyboard : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
    }

    /** The private `caretWord`, built and set by hand: nothing here can type. */
    private fun WMKeyboardService.parkCaretIn(head: String, tail: String) {
        val type = Class.forName("com.wasimaster.wmkeyboard.ime.WMKeyboardService\$CaretWord")
        val constructor = type.getDeclaredConstructor(
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        constructor.isAccessible = true
        val field = WMKeyboardService::class.java.getDeclaredField("caretWord")
        field.isAccessible = true
        field.set(this, constructor.newInstance(head, tail, head.length))
    }

    private fun WMKeyboardService.compose(word: String) {
        val field = WMKeyboardService::class.java.getDeclaredField("composing")
        field.isAccessible = true
        field.set(this, StringBuilder(word))
    }

    private fun WMKeyboardService.typedWord(): String {
        val method = WMKeyboardService::class.java.getDeclaredMethod("typedWord")
        method.isAccessible = true
        return method.invoke(this) as String
    }

    @Test
    fun `the word the caret is inside is the word the menu is about`() {
        val service = Keyboard()
        service.parkCaretIn("wmkey", "board")
        assertEquals("wmkeyboard", service.typedWord())
    }

    @Test
    fun `a word being typed still wins over the one the caret was in`() {
        // The buffer is the newer answer: the caret moved into this word and
        // the user typed on, which is the resume path, not the read-only one.
        val service = Keyboard()
        service.parkCaretIn("wmkey", "board")
        service.compose("hello")
        assertEquals("hello", service.typedWord())
    }

    @Test
    fun `a caret touching no word leaves the menu nothing to add`() {
        assertEquals("", Keyboard().typedWord())
    }
}
