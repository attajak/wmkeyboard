package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.gesture.GlideCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What a stroke asked of the shift key rides home on the picker's verdict
 * (#115, #163), because `ServiceKeyboardContent` has no room for another
 * callback. That makes this the one place a glide's "capitalize this" can be
 * lost, so it is checked.
 */
class GlideVerdictTest {

    @Test
    fun anOrdinaryGlideCarriesNoCapitalsAndAllocatesNothing() {
        val leader = GlideVerdict.Leader()
        assertEquals(GlideCase.None, leader.caseAt(0))
        assertSame(leader, leader.withCases(emptyList()))
        assertSame(leader, leader.withCases(listOf(GlideCase.None)))
        val word = GlideVerdict.Word("mark")
        assertSame(word, word.withCases(emptyList()))
    }

    @Test
    fun crossingTheShiftKeyRidesHomeOnEitherAnswer() {
        val once = listOf(GlideCase.Word(1))
        assertEquals(GlideVerdict.Leader(once), GlideVerdict.Leader().withCases(once))
        val shout = listOf(GlideCase.Word(2))
        assertEquals(GlideVerdict.Word("mark", shout), GlideVerdict.Word("mark").withCases(shout))
        // The picker's word survives the crossing: they are two answers about
        // the same stroke, not competing ones.
        assertEquals("mark", (GlideVerdict.Word("mark").withCases(once) as GlideVerdict.Word).word)
    }

    @Test
    fun eachSegmentKeepsItsOwnCase() {
        val letters = GlideCase.Letters(floatArrayOf(0.5f), shout = false)
        val verdict = GlideVerdict.Leader().withCases(listOf(GlideCase.None, letters))
        assertEquals(GlideCase.None, verdict.caseAt(0))
        assertEquals(letters, verdict.caseAt(1))
        // Past the end is an ordinary word, not a crash.
        assertEquals(GlideCase.None, verdict.caseAt(2))
    }

    @Test
    fun aCancelledStrokeTypesNothingSoItCapitalizesNothing() {
        assertSame(GlideVerdict.Cancel, GlideVerdict.Cancel.withCases(listOf(GlideCase.Word(3))))
        assertEquals(GlideCase.None, GlideVerdict.Cancel.caseAt(0))
    }
}
