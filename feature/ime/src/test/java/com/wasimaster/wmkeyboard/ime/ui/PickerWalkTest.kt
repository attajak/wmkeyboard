package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The step arithmetic a spacebar hold-drag uses to walk the language picker
 * (issue #150). The list feeds it vertical travel and its row height, the
 * carousel feeds it horizontal travel and the swipe's 44 dp step; the maths
 * has to be the same for both, or the two shapes would feel different under
 * the same finger.
 */
class PickerWalkTest {

    @Test
    fun `travel short of a step moves nothing and is kept`() {
        assertEquals(PickerWalk(2, 30f), walkPicker(index = 2, travel = 30f, stepPx = 40f, last = 5))
    }

    @Test
    fun `each whole step advances one entry and spends itself`() {
        assertEquals(PickerWalk(4, 10f), walkPicker(index = 2, travel = 90f, stepPx = 40f, last = 5))
    }

    @Test
    fun `negative travel walks back`() {
        assertEquals(PickerWalk(1, -10f), walkPicker(index = 2, travel = -50f, stepPx = 40f, last = 5))
    }

    @Test
    fun `the ends clamp and still spend the travel`() {
        // Three steps of travel past the last entry: parks on it, banks none of
        // the overshoot, so a reversal answers after one step.
        assertEquals(PickerWalk(5, 5f), walkPicker(index = 4, travel = 125f, stepPx = 40f, last = 5))
        assertEquals(PickerWalk(0, -5f), walkPicker(index = 1, travel = -125f, stepPx = 40f, last = 5))
    }

    @Test
    fun `a single entry never moves`() {
        // A step needs travel strictly past its size, so an exact multiple
        // keeps its last step unspent — the same rule the swipe ring uses.
        assertEquals(PickerWalk(0, 40f), walkPicker(index = 0, travel = 400f, stepPx = 40f, last = 0))
    }
}
