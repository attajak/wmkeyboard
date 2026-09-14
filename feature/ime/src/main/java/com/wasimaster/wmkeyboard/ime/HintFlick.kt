package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Whether a stroke is the short downward flick that types the key's corner
 * hint — its first long-press character — without waiting out the hold
 * (issue #178).
 *
 * The shape test lives beside [octopusFlick] and is built the same way, and for
 * the same reason: a gesture that has to be told apart from a glide must be
 * checkable without a live keyboard. Both callers — the glide loop, which asks
 * at its own lift, and the loop that runs for the strokes the glide loop never
 * claims — ask this one function.
 *
 * Judged at the lift. A flick is over before anything could be gained by
 * claiming the pointer early, and claiming it early would take every downward
 * stroke off the glide decoder instead of only the ones that turned out to be
 * flicks.
 *
 * What separates it from a glide is where the stroke went and how long it took:
 *
 * - **Down**, inside a cone about straight down. A glide toward the row below
 *   leans, and a flick does not.
 * - **Short.** From half a key height to [HINT_FLICK_MAX_TRAVEL_HEIGHTS] key
 *   heights. Any further and the finger is drawing, not flicking.
 * - **Straight.** The path may wander only a little past the straight line.
 * - **Quick.** Down to up inside [HINT_FLICK_MAX_MS]. This is the one rule the
 *   octopus flick deliberately does without, and it is here on purpose: the
 *   octopus judges *upward* strokes, which few words open with, while a short
 *   downward stroke is exactly what a two-letter word on neighbouring rows looks
 *   like. Total duration, not instantaneous speed — a flick decelerates as the
 *   finger leaves the glass, and a rule about its last samples would reject the
 *   gesture it is meant to accept.
 *
 * Even so, a fast enough glide of a word like "ed" can read as a flick. That is
 * the trade the setting's own text names, and why it ships off.
 *
 * @param points the stroke, oldest first, stamped with their arrival times
 * @param keyHeightPx the unit travel is measured in
 * @param minTravelPx the shortest stroke that counts; the caller floors it at
 *        the system's touch slop so a tiny key cannot turn a sloppy tap into a
 *        flick
 */
internal fun hintFlick(
    points: List<GesturePoint>,
    keyHeightPx: Float,
    minTravelPx: Float,
): Boolean {
    if (keyHeightPx <= 0f || points.size < 2) return false
    val first = points.first()
    val last = points.last()
    if (last.t - first.t > HINT_FLICK_MAX_MS) return false

    val dx = last.x - first.x
    val dy = last.y - first.y
    // Downward, and inside the cone.
    if (dy <= 0f) return false
    val direct = hypot(dx, dy)
    if (direct < minTravelPx) return false
    if (direct > keyHeightPx * HINT_FLICK_MAX_TRAVEL_HEIGHTS) return false
    if (angleOffDown(dx, dy) > HINT_FLICK_CONE_DEGREES) return false

    // One motion, not a path that happened to end lower than it started.
    var travelled = 0f
    for (i in 1 until points.size) {
        travelled += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }
    return travelled <= direct * HINT_FLICK_MAX_DETOUR
}

/** Degrees between the stroke and straight down. */
private fun angleOffDown(dx: Float, dy: Float): Float =
    Math.toDegrees(atan2(abs(dx).toDouble(), dy.toDouble())).toFloat()

/** Half-width of the downward cone, in degrees. */
internal const val HINT_FLICK_CONE_DEGREES = 30f

/** How much longer than the straight line the stroke may wander. */
internal const val HINT_FLICK_MAX_DETOUR = 1.4f

/** The shortest stroke that counts, in key heights. */
internal const val HINT_FLICK_MIN_TRAVEL_HEIGHTS = 0.5f

/** The longest stroke that counts, in key heights. */
internal const val HINT_FLICK_MAX_TRAVEL_HEIGHTS = 2f

/** Down to up, in ms. A flick of one key height takes well under half this. */
internal const val HINT_FLICK_MAX_MS = 250L
