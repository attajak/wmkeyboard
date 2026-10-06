package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Which arm of a flick key a finger is pulling towards (issue #410).
 *
 * A pure function of the finger's offset from the down, so the gesture branch,
 * the cross popup and a unit test all read the same answer. Built to extend
 * the four-arm rule a kana pad shipped with rather than replace it:
 *
 * - Inside [slopPx] nothing is chosen; the press is still a tap.
 * - Past it, the stroke's bearing is matched against the arms the key
 *   actually has ([hasArm]), and the nearest one wins if it is within
 *   [FLICK_ARM_CONE_DEGREES] of the stroke. On a key with the four edge arms
 *   that is exactly the old dominant-axis rule (the nearest axis is never more
 *   than 45° away); on a key with all eight it is 45° sectors centred on each
 *   arm; on a key with one arm the cone is generous enough that a swipe 45° off
 *   still reaches it.
 * - A stroke with no arm near it resolves to null, the centre tap, which is
 *   what a flick towards an empty arm has always done: a key with only up and
 *   down arms still ignores a swipe left.
 *
 * Distance is Euclidean. The old test was the larger of |dx| and |dy|, which
 * made a diagonal travel ~1.4× further than an edge before it counted.
 */
internal fun resolveFlickDirection(
    dx: Float,
    dy: Float,
    slopPx: Float,
    hasArm: (FlickDirection) -> Boolean,
): FlickDirection? {
    if (hypot(dx, dy) < slopPx) return null
    // Screen y grows downwards, so atan2(dx, -dy) is the compass bearing with
    // up at 0° and clockwise positive, the convention [FlickDirection.compassDegrees] uses.
    val bearing = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat().let { if (it < 0f) it + 360f else it }
    var best: FlickDirection? = null
    var bestGap = Float.MAX_VALUE
    for (direction in FlickDirection.entries) {
        if (!hasArm(direction)) continue
        val gap = angularGap(bearing, direction.compassDegrees)
        if (gap < bestGap) {
            bestGap = gap
            best = direction
        }
    }
    return best?.takeIf { bestGap <= FLICK_ARM_CONE_DEGREES }
}

/** The smaller angle between two bearings, 0–180. */
private fun angularGap(a: Float, b: Float): Float {
    val raw = abs(a - b) % 360f
    return if (raw > 180f) 360f - raw else raw
}

/**
 * Whether a finger that went out to an arm has come back far enough to mean
 * the arm's shifted form rather than the arm itself: the swipe-and-return
 * FlickBoard and Thumb-Key read as a capital. "Far enough" is more than
 * halfway back from the farthest point ([extremeDistPx] from the down) towards
 * where the finger started, measured as the distance travelled back from that
 * farthest point ([backFromExtremePx]).
 */
internal fun flickReturned(extremeDistPx: Float, backFromExtremePx: Float): Boolean =
    extremeDistPx > 0f && backFromExtremePx * backFromExtremePx > extremeDistPx * extremeDistPx / 4f

/**
 * How far off an arm's bearing a stroke may be and still take it. One and a
 * half 45° sectors: a four-arm key keeps its 90° cones, an eight-arm key gets
 * 45° ones, and a lone arm answers to a swipe up to 67.5° off it.
 */
internal const val FLICK_ARM_CONE_DEGREES = 67.5f
