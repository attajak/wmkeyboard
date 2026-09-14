package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideReadingsTest {

    /**
     * Glides [word] (with [others] as the stroke's runners-up) at the end of
     * text [before] characters long, followed by a space, and reports the
     * caret the editor would echo back. Returns where the word starts.
     */
    private fun GlideReadings.glide(word: String, before: Int, vararg others: String): Int {
        remember(word, listOf(word, *others))
        onCaret(before + word.length + 1)
        return before
    }

    @Test
    fun aSwipedWordOffersItsStrokesReadingsWhereItStands() {
        val readings = GlideReadings()
        val at = readings.glide("form", 0, "from", "fork")
        assertEquals(listOf("from", "fork"), readings.readingsAt("form", at))
    }

    /** Issue #199: the same spelling somewhere else is a different word. */
    @Test
    fun theSameWordElsewhereIsNotTheSwipedOne() {
        val readings = GlideReadings()
        readings.glide("form", 0, "from")
        // A paragraph later, "form" is tapped out: no glide, no readings.
        readings.onCaret(300)
        assertTrue(readings.readingsAt("form", 295).isEmpty())
        assertEquals(listOf("from"), readings.readingsAt("form", 0))
    }

    @Test
    fun twoSwipesOfOneWordKeepTheirOwnReadings() {
        val readings = GlideReadings()
        val first = readings.glide("form", 0, "from")
        val second = readings.glide("form", 200, "fork")
        assertEquals(listOf("from"), readings.readingsAt("form", first))
        assertEquals(listOf("fork"), readings.readingsAt("form", second))
    }

    @Test
    fun aMessagesWorthOfSwipesIsKept() {
        val readings = GlideReadings()
        var end = 0
        repeat(GlideReadings.DEFAULT_CAPACITY + 1) { i ->
            readings.glide("w$i", end, "x$i")
            end += "w$i".length + 1
        }
        assertEquals(GlideReadings.DEFAULT_CAPACITY, readings.size)
        assertTrue("the oldest made room", readings.readingsAt("w0", 0).isEmpty())
        assertEquals(listOf("x1"), readings.readingsAt("w1", 3))
    }

    @Test
    fun aSingleReadingIsNotKept() {
        val readings = GlideReadings()
        readings.remember("form", listOf("form"))
        readings.onCaret(5)
        assertEquals(0, readings.size)
    }

    @Test
    fun deletingInFrontMovesTheWordBack() {
        val readings = GlideReadings()
        readings.glide("hello", 0, "jello")
        val at = readings.glide("form", 6, "from")
        // The whole first word and its space go: "form" now starts at 0.
        readings.onDeleted(0, 6)
        readings.onCaret(0)
        assertEquals(listOf("from"), readings.readingsAt("form", at - 6))
    }

    @Test
    fun backspacingIntoTheWordKeepsItsReadings() {
        val readings = GlideReadings()
        val at = readings.glide("form", 0, "from")
        // Back onto the word, then one letter off its end.
        readings.onCaret(4)
        readings.onDeleted(3, 4)
        readings.onCaret(3)
        assertEquals(listOf("from"), readings.readingsAt("form", at))
    }

    @Test
    fun aWordFixedInFrontMovesTheWordsBehindItExactly() {
        val readings = GlideReadings()
        readings.glide("teh", 0, "the")
        val at = readings.glide("form", 4, "from")
        // "teh" replaced by "there" from the strip: two characters longer.
        readings.onReplaced(0, 3, 5)
        readings.onCaret(5)
        assertEquals(listOf("from"), readings.readingsAt("form", at + 2))
    }

    @Test
    fun typingInFrontIsFollowed() {
        val readings = GlideReadings()
        readings.glide("the", 0, "tie")
        val at = readings.glide("form", 4, "from")
        // Back to the start, then "and then so " typed one keystroke at a
        // time: further than the slack alone forgives. Nobody reports an
        // insertion; only the caret moves.
        readings.onCaret(0)
        for (caret in 1..12) readings.onCaret(caret)
        assertEquals(listOf("from"), readings.readingsAt("form", at + 12))
        // Found, so re-anchored: exact again without any allowance.
        readings.onCaret(500)
        assertEquals(listOf("from"), readings.readingsAt("form", at + 12))
    }

    @Test
    fun aTapFarAwayIsNotAnEdit() {
        val readings = GlideReadings()
        readings.glide("form", 0, "from")
        readings.glide("words", 5, "wards")
        // Back to the top, then a tap two paragraphs down, where "form" has
        // been typed by hand. The jump says nothing about text moving.
        readings.onCaret(0)
        readings.onCaret(400)
        assertTrue(readings.readingsAt("form", 396).isEmpty())
    }

    @Test
    fun takingOneForgetsTheWordAtThatPlaceOnly() {
        val readings = GlideReadings()
        val first = readings.glide("form", 0, "from")
        val second = readings.glide("form", 200, "fork")
        readings.forget("form", first)
        assertTrue(readings.readingsAt("form", first).isEmpty())
        assertEquals(listOf("fork"), readings.readingsAt("form", second))
    }

    @Test
    fun aRangeSelectionAnchorsNothing() {
        val readings = GlideReadings()
        readings.remember("form", listOf("form", "from"))
        readings.onCaret(0, 10)
        readings.onCaret(5)
        assertEquals(listOf("from"), readings.readingsAt("form", 0))
    }
}
