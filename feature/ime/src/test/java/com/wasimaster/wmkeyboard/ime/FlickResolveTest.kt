package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which arm a flick resolves to (issue #410): the eight-way rule has to agree
 * with the four-way dominant-axis rule a kana pad shipped with, and with the
 * centre-tap fallback for an arm the key does not have.
 */
class FlickResolveTest {

    private val slop = 22f

    private val edges = setOf(FlickDirection.LEFT, FlickDirection.UP, FlickDirection.RIGHT, FlickDirection.DOWN)

    /** A stroke of [length] at compass [bearing] degrees (up = 0, clockwise). */
    private fun at(bearing: Float, length: Float = 60f, arms: Set<FlickDirection>): FlickDirection? {
        val radians = Math.toRadians(bearing.toDouble())
        val dx = (sin(radians) * length).toFloat()
        val dy = (-cos(radians) * length).toFloat()
        return resolveFlickDirection(dx, dy, slop) { it in arms }
    }

    /** The rule the kana pad shipped with, for the four edge arms. */
    private fun dominantAxis(bearing: Float): FlickDirection {
        val radians = Math.toRadians(bearing.toDouble())
        val dx = sin(radians).toFloat()
        val dy = (-cos(radians)).toFloat()
        return when {
            abs(dx) >= abs(dy) -> if (dx < 0) FlickDirection.LEFT else FlickDirection.RIGHT
            else -> if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
        }
    }

    @Test
    fun `inside the slop nothing is chosen`() {
        assertNull(resolveFlickDirection(10f, 10f, slop) { true })
        assertNull(resolveFlickDirection(0f, -21f, slop) { true })
    }

    @Test
    fun `a diagonal needs the same travel as an edge`() {
        // 22 px along the diagonal is 15.6 px on each axis; the old test asked
        // for 22 on one axis, so a corner flick had to travel further.
        assertEquals(FlickDirection.UP_RIGHT, resolveFlickDirection(16f, -16f, slop) { true })
    }

    @Test
    fun `eight arms split the compass into 45 degree sectors`() {
        val all = FlickDirection.entries.toSet()
        assertEquals(FlickDirection.UP, at(0f, arms = all))
        assertEquals(FlickDirection.UP, at(22f, arms = all))
        assertEquals(FlickDirection.UP_RIGHT, at(23f, arms = all))
        assertEquals(FlickDirection.UP_RIGHT, at(67f, arms = all))
        assertEquals(FlickDirection.RIGHT, at(68f, arms = all))
        assertEquals(FlickDirection.DOWN_RIGHT, at(135f, arms = all))
        assertEquals(FlickDirection.DOWN, at(180f, arms = all))
        assertEquals(FlickDirection.DOWN_LEFT, at(225f, arms = all))
        assertEquals(FlickDirection.LEFT, at(270f, arms = all))
        assertEquals(FlickDirection.UP_LEFT, at(315f, arms = all))
        assertEquals(FlickDirection.UP, at(350f, arms = all))
    }

    @Test
    fun `four edge arms resolve exactly as the dominant axis did`() {
        var bearing = 0f
        while (bearing < 360f) {
            // Skip the exact diagonals, where the old rule's >= tie-break is
            // arbitrary and nobody flicks to a hundredth of a degree.
            if ((bearing - 45f) % 90f != 0f) {
                assertEquals("at $bearing°", dominantAxis(bearing), at(bearing, arms = edges))
            }
            bearing += 1f
        }
    }

    @Test
    fun `an arm the key does not have falls back to the centre`() {
        // A kana や key flicks up and down only; a swipe left is a tap.
        val upDown = setOf(FlickDirection.UP, FlickDirection.DOWN)
        assertNull(at(270f, arms = upDown))
        assertNull(at(90f, arms = upDown))
        assertEquals(FlickDirection.UP, at(30f, arms = upDown))
        assertEquals(FlickDirection.DOWN, at(200f, arms = upDown))
        assertNull(at(0f, arms = emptySet()))
    }

    @Test
    fun `a lone corner arm answers to a swipe up to 67 degrees off it`() {
        // MessagEase's a-key carries only v, at the bottom right.
        val one = setOf(FlickDirection.DOWN_RIGHT)
        assertEquals(FlickDirection.DOWN_RIGHT, at(135f, arms = one))
        assertEquals(FlickDirection.DOWN_RIGHT, at(90f, arms = one))
        assertEquals(FlickDirection.DOWN_RIGHT, at(180f, arms = one))
        assertEquals(FlickDirection.DOWN_RIGHT, at(200f, arms = one))
        assertNull(at(45f, arms = one))
        assertNull(at(270f, arms = one))
    }

    @Test
    fun `neighbouring arms split the gap between them`() {
        // o on MessagEase has up and up-right: 22.5° is the boundary.
        val two = setOf(FlickDirection.UP, FlickDirection.UP_RIGHT)
        assertEquals(FlickDirection.UP, at(20f, arms = two))
        assertEquals(FlickDirection.UP_RIGHT, at(25f, arms = two))
        // The far side of each is still generous, as a lone arm's would be.
        assertEquals(FlickDirection.UP, at(300f, arms = two))
        assertEquals(FlickDirection.UP_RIGHT, at(110f, arms = two))
    }

    @Test
    fun `the return rule wants more than halfway back`() {
        assertFalse(flickReturned(100f, 0f))
        assertFalse(flickReturned(100f, 50f))
        assertTrue(flickReturned(100f, 51f))
        assertTrue(flickReturned(100f, 100f))
        // Never returned from nowhere.
        assertFalse(flickReturned(0f, 10f))
    }
}
