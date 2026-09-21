package com.wasimaster.wmkeyboard.core.prediction

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A downloaded AOSP list's 0..255 ratings, read back as the counts a counted list holds. */
class AospScoresTest {

    @Test
    fun `the top of en_US lands beside the counted English list's top words`() {
        // en_US rates `the` 222. The counted list's top words run 10 to 30 million.
        val the = AospScores.listCount(222)
        assertTrue("the = $the", the in 10_000_000..30_000_000)
    }

    @Test
    fun `the spread is a counted list's, not the linear import scale's`() {
        // The engine weighs ln(1 + f). The linear map puts the whole list
        // inside about two nats; a counted list spans well over ten.
        val spread = ln(1.0 + AospScores.listCount(222)) - ln(1.0 + AospScores.listCount(40))
        val linear = ln(1.0 + DictionaryLoader.scaleAospFrequency(222)) -
            ln(1.0 + DictionaryLoader.scaleAospFrequency(40))
        assertTrue("spread $spread", spread > 10.0)
        assertTrue("linear $linear", linear < 2.0)
    }

    @Test
    fun `every rating keeps its order and stays a word`() {
        var previous = 0
        for (p in 0..255) {
            val count = AospScores.listCount(p)
            assertTrue("$p -> $count fell below ${p - 1}", count >= previous)
            previous = count
        }
        // A word AOSP rated 0 is still a word it knows, not a hole in the list.
        assertEquals(1, AospScores.listCount(0))
        assertEquals(AospScores.listCount(255), AospScores.listCount(300))
    }
}
