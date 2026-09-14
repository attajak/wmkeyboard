package com.wasimaster.wmkeyboard.core.gesture

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a stroke said about case by drawing through the shift key.
 *
 * Two readings of the same detour, chosen by a setting (#163):
 *
 *  - [Word] is the ladder tapping shift walks up (#115): cross once for a
 *    capital, twice for a shout. It overrides the board's own shift rather
 *    than combining with it — the user drew the instruction after they saw
 *    the board.
 *  - [Letters] reads each crossing as "capitalize the letter I just left", so
 *    a stroke can write `HeLLo` or `LeanType`, and a stroke that *ends* on the
 *    shift key shouts the whole word — one trip instead of two. Combines with
 *    the board's shift: a sentence-start capital and a drawn one are two
 *    different letters.
 *
 * Both are read off a stroke the detour has already been cut out of — see
 * [GlideShiftDetour] — because the walk out to the key and back is an
 * instruction, not letters, and left in the path it spells whatever lies
 * between the word and the key (t→shift→here read as "trashed").
 */
sealed interface GlideCase {

    /** The stroke never touched shift. */
    data object None : GlideCase

    /**
     * Crossed shift [times]: once for a capital, twice or more for a shout —
     * the same ladder tapping the key walks up.
     */
    data class Word(val times: Int) : GlideCase

    /**
     * Each cut capitalizes the letter before it; [shout] when the finger
     * lifted on the shift key, which capitalizes every letter instead.
     *
     * [cuts] are where along the stroke each crossing left for the key, as
     * fractions of the trimmed stroke's arc length, ascending. A fraction
     * rather than a point index because the decoder places letters on an
     * arc-length resampling of the stroke — see [GlideBeam.Alignment] — so
     * this is the one coordinate the two sides share.
     */
    class Letters(val cuts: FloatArray, val shout: Boolean) : GlideCase {

        /**
         * [word] with the letters the cuts asked for in capitals, or all of
         * them when the stroke ended on shift.
         *
         * [alignment] is where the decoder laid the word's keys along the
         * stroke, when it could — each cut takes the visit that sits nearest
         * to it, and every character that visit wrote (a doubled letter is one
         * visit, so `HeLLo` gets both its Ls from one crossing). Without one —
         * a romanized stroke whose answer is in another script, a word the
         * grid cannot spell — the cut is read proportionally along the word,
         * which is right whenever the letters are spread evenly and close
         * enough otherwise.
         */
        fun caseWord(word: String, alignment: GlideBeam.Alignment?): String {
            if (word.isEmpty()) return word
            if (shout) return word.uppercase()
            if (cuts.isEmpty()) return word
            val ranges = cuts.map { cut -> letterRangeAt(word, alignment, cut) }
                .distinct()
                .sortedByDescending { it.first }
            val out = StringBuilder(word)
            // Back to front, so an uppercase that changes length (ß → SS)
            // never moves a range still to be replaced.
            for ((start, end) in ranges) {
                out.replace(start, end, word.substring(start, end).uppercase())
            }
            return out.toString()
        }

        /** The chars of the letter [cut] capitalizes: start inclusive, end exclusive. */
        private fun letterRangeAt(word: String, alignment: GlideBeam.Alignment?, cut: Float): Pair<Int, Int> {
            if (alignment != null && alignment.size > 0 && alignment.chars.size == alignment.size) {
                val last = (GlideWorkspace.SAMPLE_POINTS - 1).toFloat()
                var best = 0
                var bestGap = Float.MAX_VALUE
                for (m in 0 until alignment.size) {
                    val gap = abs(alignment.samples[m] / last - cut)
                    // Strictly closer only, so a tie goes to the earlier visit:
                    // the letter *before* the cut is the one that was asked for.
                    if (gap < bestGap) {
                        bestGap = gap
                        best = m
                    }
                }
                val start = alignment.chars[best].coerceIn(0, word.length)
                val end = if (best + 1 < alignment.size) alignment.chars[best + 1] else word.length
                return start to end.coerceIn(start, word.length)
            }
            val count = word.codePointCount(0, word.length)
            val index = (cut * (count - 1)).roundToInt().coerceIn(0, count - 1)
            val start = word.offsetByCodePoints(0, index)
            val end = start + Character.charCount(word.codePointAt(start))
            return start to end
        }

        override fun equals(other: Any?): Boolean =
            other is Letters && other.shout == shout && other.cuts.contentEquals(cuts)

        override fun hashCode(): Int = 31 * cuts.contentHashCode() + shout.hashCode()

        override fun toString(): String = "Letters(cuts=${cuts.contentToString()}, shout=$shout)"
    }
}

/**
 * Cuts the walk out to the shift key, and back, out of a stroke.
 *
 * Dropping only the points *over* the key was not enough (#163): a stroke
 * drawn t→shift→h→e→r→e still carried the leg down from `t` to the key and
 * the leg back up to `h`, and the decoder read the letters those legs pass
 * over — "trashed", for "there". The reporter's finding was that the misread
 * is worst when the key comes right after the first letter or right after the
 * last, which is exactly when a leg is longest.
 *
 * A leg is a straight run: the finger goes from the letter to the key and
 * from the key to the next letter, turning only at the letters. So the leg
 * is the last straight run of the stroke before the key, and the first after
 * it, where "straight" is Douglas–Peucker's test: no point of the run sits
 * more than [DEFAULT_TOLERANCE] off the chord between its ends. The run
 * before the key is found the way that method simplifies a polyline, split
 * at the point farthest off the chord, and split again on the key's side
 * until the piece against the key is straight; the last split is the bend,
 * which is the letter, and it is kept while everything between it and the
 * key goes. Chosen for the reason the method is robust: a chord is fitted
 * over a whole run, so the tremor of a real finger, which points every which
 * way between adjacent samples, does not read as a turn. (A test on the local
 * heading did, and either stopped on tremor or ran through a letter whose
 * approach lay within its angle of the leg.) The chord runs to the key's
 * own centre when the caller knows it, not to the edge of its cell: a letter
 * beside the key has a leg half a key long, and its bend sits too little off
 * a chord that short to be seen.
 *
 * A leg that never bends is kept whole at its far end: a finger that left the
 * key, went straight to `p` and lifted was writing `p`, and a stroke that went
 * straight from its first letter to the key started on that letter. A letter
 * that lies on the leg's own line is the one case nothing here can see, and
 * is cut with the leg.
 *
 * The clock is closed up over the cut: the jump from bend to bend is given
 * the time its length would take at the stroke's own speed, so the decoder,
 * which reads a long step in time over a short step in distance as a pause
 * on a key, sees neither the detour's duration nor a standstill.
 */
object GlideShiftDetour {

    /** How far off a leg's chord a point may sit before the leg has bent, in key widths. */
    const val DEFAULT_TOLERANCE = 0.35f

    /** A stroke with the detours cut out, and where each cut sits along it. */
    class Trimmed(
        val points: List<GesturePoint>,
        /** Arc-length fraction of each crossing's letter along [points], ascending. */
        val cuts: FloatArray,
    )

    /**
     * [points] with each detour cut out. [cuts] are indices into [points]:
     * for each crossing, the index of the first point after the finger left
     * the shift key, ascending — [points].size when the stroke ended there.
     * The points over the key itself are expected to be gone already. [key]
     * is the shift key's centre, in the same space as [points], when known.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
    fun trim(
        points: List<GesturePoint>,
        cuts: IntArray,
        keyWidth: Float,
        tolerance: Float = DEFAULT_TOLERANCE,
        key: GesturePoint? = null,
    ): Trimmed {
        if (cuts.isEmpty() || points.size < 2 || keyWidth <= 0f) {
            return Trimmed(points, FloatArray(0))
        }
        val tolerancePx = tolerance * keyWidth
        val keep = BooleanArray(points.size) { true }
        // The first point after each crossing: the clock is closed up there
        // whether or not a leg was cut, since the key's own points are
        // already gone and their time would land on this step.
        val boundary = BooleanArray(points.size)
        for (cut in cuts) if (cut in points.indices) boundary[cut] = true
        val corners = IntArray(cuts.size)
        // Where the previous crossing's exit leg ended: this crossing's entry
        // leg may not reach back past it, or the one letter between two
        // crossings would be cut from both sides.
        var floor = 0
        for ((k, cut) in cuts.withIndex()) {
            val entryEnd = minOf(cut - 1, points.lastIndex)
            var corner = minOf(floor, points.lastIndex)
            if (entryEnd >= floor) {
                corner = bendBefore(points, entryEnd, floor, tolerancePx, key)
                for (i in corner + 1..entryEnd) keep[i] = false
            }
            corners[k] = corner
            floor = if (cut <= points.lastIndex) {
                val exit = bendAfter(points, cut, tolerancePx, key)
                for (i in cut until exit) keep[i] = false
                exit
            } else {
                points.size
            }
        }
        // Speed the jump across a cut is charged at: the stroke's own.
        val rawSpan = (points.last().t - points.first().t).toFloat()
        val speed = if (rawSpan > 0f) arcOf(points) / rawSpan else 0f

        val out = ArrayList<GesturePoint>(points.size)
        val outIndex = IntArray(points.size) { -1 }
        var clockShift = 0L
        var previous: GesturePoint? = null
        var previousRaw = -1
        for (i in points.indices) {
            if (!keep[i]) continue
            val p = points[i]
            val before = previous
            if (before != null && (previousRaw != i - 1 || boundary[i]) && speed > 0f) {
                // The gap between the entry bend and the exit bend: give it
                // the time its straight-line distance would have taken.
                val removed = p.t - clockShift - before.t
                val wanted = (distance(before, p) / speed).toLong()
                if (removed > wanted) clockShift += removed - wanted
            }
            val placed = if (clockShift == 0L) p else GesturePoint(p.x, p.y, p.t - clockShift)
            outIndex[i] = out.size
            out.add(placed)
            previous = placed
            previousRaw = i
        }
        val fractions = FloatArray(cuts.size)
        val total = arcOf(out)
        if (total > 0f) {
            val arc = FloatArray(out.size)
            for (i in 1 until out.size) arc[i] = arc[i - 1] + distance(out[i - 1], out[i])
            for (k in corners.indices) {
                val at = outIndex[corners[k]].takeIf { it >= 0 } ?: out.lastIndex
                fractions[k] = arc[at] / total
            }
        }
        return Trimmed(out, fractions)
    }

    /**
     * The index the entry leg ending at [end] began from: the last bend of
     * the stroke before [end], found by splitting the run from [floor] at its
     * farthest point off the chord, then the key-side piece again, until
     * that piece is straight. [floor] itself when the run never bends. The
     * chord runs on to [key] when it is known, and [end] itself then counts
     * as a point of the run.
     */
    private fun bendBefore(
        points: List<GesturePoint>,
        end: Int,
        floor: Int,
        tolerance: Float,
        key: GesturePoint?,
    ): Int {
        val to = key ?: points[end]
        val last = if (key != null) end else end - 1
        var from = floor
        var bend = floor
        while (true) {
            val split = farthestOffChord(points, from + 1, last, points[from], to)
            if (split < 0 || offChord(points[split], points[from], to) <= tolerance) return bend
            bend = split
            from = split
        }
    }

    /**
     * The index the exit leg starting at [start] ends on: the first bend of
     * the stroke after [start]. The last index when the run never bends. The
     * chord runs back to [key] when it is known, and [start] itself then
     * counts as a point of the run.
     */
    private fun bendAfter(points: List<GesturePoint>, start: Int, tolerance: Float, key: GesturePoint?): Int {
        val from = key ?: points[start]
        val first = if (key != null) start else start + 1
        var to = points.lastIndex
        var bend = points.lastIndex
        while (true) {
            val split = farthestOffChord(points, first, to - 1, from, points[to])
            if (split < 0 || offChord(points[split], from, points[to]) <= tolerance) return bend
            bend = split
            to = split
        }
    }

    /** The index in [first]..[last] farthest off the chord from [a] to [b], or -1 for none. */
    private fun farthestOffChord(points: List<GesturePoint>, first: Int, last: Int, a: GesturePoint, b: GesturePoint): Int {
        var farthest = -1
        var off = -1f
        for (i in first..last) {
            val d = offChord(points[i], a, b)
            if (d > off) {
                off = d
                farthest = i
            }
        }
        return farthest
    }

    /** How far [p] sits off the chord from [a] to [b]: the distance to the segment. */
    private fun offChord(p: GesturePoint, a: GesturePoint, b: GesturePoint): Float {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val len2 = vx * vx + vy * vy
        if (len2 <= 0f) return distance(p, a)
        val t = (((p.x - a.x) * vx + (p.y - a.y) * vy) / len2).coerceIn(0f, 1f)
        val qx = a.x + t * vx
        val qy = a.y + t * vy
        val dx = p.x - qx
        val dy = p.y - qy
        return sqrt(dx * dx + dy * dy)
    }

    private fun arcOf(points: List<GesturePoint>): Float {
        var total = 0f
        for (i in 1 until points.size) total += distance(points[i - 1], points[i])
        return total
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
