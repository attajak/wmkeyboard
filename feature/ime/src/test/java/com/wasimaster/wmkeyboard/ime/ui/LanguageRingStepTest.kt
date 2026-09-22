package com.wasimaster.wmkeyboard.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spacebar language swipe's step rule. A flick must land on the next
 * language and no further, however far it travels, so the middle of a three
 * language ring stays reachable; a swipe that keeps moving walks on.
 */
class LanguageRingStepTest {

    private fun step(index: Int, travel: Float, lastDir: Int = 0, settled: Boolean = true, last: Int = 2) =
        stepLanguageRing(index, travel, stepPx = 40f, wrapPx = 100f, last = last, lastDir = lastDir, settled = settled)

    @Test
    fun `travel short of a step moves nothing and is kept`() {
        assertEquals(RingStep(0, 30f, 0), step(0, 30f))
    }

    @Test
    fun `a settled step advances one language and spends its travel`() {
        assertEquals(RingStep(1, 20f, 1), step(0, 60f, lastDir = 1))
    }

    @Test
    fun `one step per call however much travel there is`() {
        assertEquals(RingStep(1, 160f, 1), step(0, 200f))
    }

    @Test
    fun `a same-way step before the dwell is held and banks at most one step`() {
        // The flick already switched once; its leftover travel must not fire
        // a second switch the moment the dwell ends.
        assertEquals(RingStep(1, 40f, 0), step(1, 150f, lastDir = 1, settled = false))
        // Then the finger has to keep moving: exactly one step is not past it.
        assertEquals(RingStep(1, 40f, 0), step(1, 40f, lastDir = 1, settled = true))
        assertEquals(RingStep(2, 1f, 1), step(1, 41f, lastDir = 1, settled = true))
    }

    @Test
    fun `a reversal answers without waiting`() {
        assertEquals(RingStep(0, -5f, -1), step(1, -45f, lastDir = 1, settled = false))
    }

    @Test
    fun `the ends wrap only past the detent`() {
        assertEquals(RingStep(2, 80f, 0), step(2, 80f))
        assertEquals(RingStep(0, 10f, 1), step(2, 110f))
        assertEquals(RingStep(2, -10f, -1), step(0, -110f))
    }

    @Test
    fun `a held step at an end banks up to the detent`() {
        assertEquals(RingStep(2, 100f, 0), step(2, 300f, lastDir = 1, settled = false))
    }

    @Test
    fun `a single language never moves`() {
        assertEquals(RingStep(0, 400f, 0), step(0, 400f, last = 0))
    }
}
