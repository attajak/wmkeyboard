package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How long a spacebar language switch stays on screen after the lift. A flick
 * never saw its preview and gets the echo; a slow swipe already watched the
 * language land and gets none.
 */
class LanguageEchoTest {

    @Test
    fun `a flick that lifts at once echoes in full`() {
        assertEquals(700L, languageEchoMs(0L))
    }

    @Test
    fun `a brief preview tops the echo up to the full time`() {
        assertEquals(670L, languageEchoMs(30L))
        assertEquals(451L, languageEchoMs(249L))
    }

    @Test
    fun `a language on screen for the seen threshold echoes nothing`() {
        assertEquals(0L, languageEchoMs(250L))
        assertEquals(0L, languageEchoMs(2_000L))
    }

    @Test
    fun `a clock that runs backwards counts as unseen`() {
        assertEquals(700L, languageEchoMs(-5L))
    }
}
