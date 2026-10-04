package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mistype tolerance (#385) scales the tap scatter the touch model assumes. */
class MistypeToleranceTest {

    private val centers = mapOf('d' to TouchPoint(2.5f, 1.5f), 'e' to TouchPoint(2.0f, 0.5f))
    private val tap = TouchPoint(2.3f, 1.0f)

    private fun gap(model: KeyTouchModel) = model.logLikelihood(tap, 'd') - model.logLikelihood(tap, 'e')

    @Test
    fun `default sigma reads exactly as before`() {
        assertEquals(gap(KeyTouchModel(centers)), gap(KeyTouchModel(centers, KeyTouchModel.SIGMA)), 1e-12)
    }

    @Test
    fun `a wider tolerance narrows the gap between the hit key and its neighbour`() {
        val normal = kotlin.math.abs(gap(KeyTouchModel(centers)))
        val loose = kotlin.math.abs(gap(KeyTouchModel(centers, KeyTouchModel.SIGMA * 2)))
        val strict = kotlin.math.abs(gap(KeyTouchModel(centers, KeyTouchModel.SIGMA * 0.5)))
        assertTrue(loose < normal)
        assertTrue(strict > normal)
    }

    @Test
    fun `the best key does not depend on the tolerance`() {
        assertEquals(KeyTouchModel(centers).bestKey(tap), KeyTouchModel(centers, 1.0).bestKey(tap))
    }
}
