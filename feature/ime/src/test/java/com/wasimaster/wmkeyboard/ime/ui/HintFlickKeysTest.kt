package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.ClipboardKeyAction
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which keys a flick down types the hint of (issue #178): the ones whose hold
 * opens a popup with a character first, minus every key that already owns a
 * drag of its own.
 */
class HintFlickKeysTest {

    @Test
    fun `a letter with alternates takes the flick`() {
        assertTrue(Key(label = "q", longPress = listOf("1")).takesHintFlick())
    }

    @Test
    fun `a key with no alternates has no hint to type`() {
        assertFalse(Key(label = "q").takesHintFlick())
    }

    @Test
    fun `the spacebar keeps its own swipes even with hold keys`() {
        assertFalse(
            Key(label = "space", action = KeyAction.Space, longPress = listOf("🙂")).takesHintFlick(),
        )
    }

    @Test
    fun `backspace keeps its delete swipe`() {
        assertFalse(
            Key(label = "⌫", action = KeyAction.Delete, longPress = listOf("x")).takesHintFlick(),
        )
    }

    @Test
    fun `shift keeps the chord drag`() {
        assertFalse(
            Key(label = "⇧", action = KeyAction.Shift, longPress = listOf("⇪")).takesHintFlick(),
        )
    }

    @Test
    fun `a kana key keeps its four arms`() {
        assertFalse(
            Key(
                label = "あ",
                longPress = listOf("ぁ"),
                flick = mapOf(FlickDirection.DOWN to "お"),
            ).takesHintFlick(),
        )
    }

    @Test
    fun `a clipboard shortcut key has given its hold away`() {
        assertFalse(
            Key(
                label = "v",
                longPress = listOf("1"),
                clipboardAction = ClipboardKeyAction.PASTE,
            ).takesHintFlick(),
        )
    }
}
