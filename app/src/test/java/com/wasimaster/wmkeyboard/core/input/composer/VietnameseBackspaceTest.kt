package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What one backspace takes off a Telex buffer.
 *
 * The buffer holds keys and the field holds the word they spelled, and the two
 * do not shrink together: one press has to take back one *letter*, with a tone
 * key going only when the letter it marked goes with it. `hì` is two presses —
 * `h`, then nothing — and `hướng` is five, the tone riding `ơ` surviving the
 * presses that take `g` and `n` off ahead of it.
 */
class VietnameseBackspaceTest {

    /** The output after each of [steps] backspaces, from the buffer [typed]. */
    private fun walk(composer: Composer, typed: String, steps: Int): List<String> {
        var buffer = typed
        return (1..steps).map {
            buffer = composer.backspaceBuffer(buffer)
            composer.composeBuffer(buffer)
        }
    }

    @Test
    fun `a tone comes off with the letter it is on`() {
        val c = VietnameseTelexComposer
        // `hif` is `hì`; the first press takes the whole `ì`, tone and all,
        // rather than spending itself on the `f` and leaving `hi` behind.
        assertEquals(listOf("h", ""), walk(c, "hif", 2))
    }

    @Test
    fun `a coda comes off before the tone that rode an earlier letter`() {
        val c = VietnameseTelexComposer
        // `huowngs` is `hướng`. The tone key is spent on the `ơ`, not on the
        // `g`, so it stays while the coda is taken off — and each press is one
        // letter: five presses for a five-letter word, where the old rule took
        // one key each and needed eight.
        assertEquals(
            listOf("hướn", "hướ", "hư", "h", ""),
            walk(c, "huowngs", 5),
        )
    }

    @Test
    fun `a letter mark goes with its letter`() {
        val c = VietnameseTelexComposer
        // `â` is two keys but one letter, and the horn on `ư` is not a letter
        // of its own: `đường` is four presses, not seven.
        assertEquals(listOf("đườn", "đườ", "đư", "đ", ""), walk(c, "dduowngf", 5))
        assertEquals(listOf("đ", ""), walk(c, "ddaa", 2))
    }

    @Test
    fun `a run of w comes off one letter at a time`() {
        val c = VietnameseTelexComposer
        // `wwwwwwww` reads as seven `w`s, so the presses take seven letters off.
        assertEquals(
            listOf("wwwwww", "wwwww", "wwww", "www", "ww", "w", ""),
            walk(c, "wwwwwwww", 7),
        )
    }

    @Test
    fun `a latin word is taken off one letter at a time`() {
        val c = VietnameseTelexComposer
        assertEquals(listOf("hell", "hel", "he", "h", ""), walk(c, "hello", 5))
    }

    @Test
    fun `vni takes off letters the same way`() {
        val c = VietnameseVniComposer
        assertEquals(listOf("hướn", "hướ", "hư", "h", ""), walk(c, "hu7o7ng1", 5))
    }

    @Test
    fun `an empty buffer stays empty`() {
        assertEquals("", VietnameseTelexComposer.backspaceBuffer(""))
        assertEquals("", VietnameseVniComposer.backspaceBuffer(""))
    }
}
