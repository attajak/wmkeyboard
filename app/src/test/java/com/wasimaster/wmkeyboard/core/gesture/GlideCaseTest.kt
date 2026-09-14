package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shift detour cut out of a stroke, and the capitals it asked for (#163).
 *
 * The grid is the one `GlideBeamTest` draws on, with a shift key standing
 * where QWERTY puts it: the bottom row's first cell, left of `z`.
 */
class GlideCaseTest {

    private val keyWidth = 60f
    private val keys: List<KeyCenter> = buildList {
        "qwertyuiop".forEachIndexed { i, c -> add(KeyCenter(c, 30f + i * 60f, 30f)) }
        "asdfghjkl".forEachIndexed { i, c -> add(KeyCenter(c, 60f + i * 60f, 90f)) }
        "zxcvbnm".forEachIndexed { i, c -> add(KeyCenter(c, 90f + i * 60f, 150f)) }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    /** The shift key: 60px square, left of z. */
    private val shiftLeft = 0f
    private val shiftTop = 120f
    private val shiftRight = 60f
    private val shiftBottom = 180f
    private val shiftCenter = GesturePoint(30f, 150f)

    private val lexicon = listOf(
        "hello" to 900, "help" to 700, "held" to 300, "hell" to 200, "ho" to 400,
        "the" to 1000, "they" to 800, "then" to 700, "them" to 650, "there" to 900,
        "trashed" to 200, "tasted" to 300, "was" to 900, "what" to 850, "good" to 800,
        "lean" to 300, "leantype" to 100, "type" to 500,
    )

    private val beam = GlideBeam()
    private val workspace = GlideWorkspace()
    private val sources: List<FuzzyBeamSearch.WalkSource> = run {
        val trie = Trie().apply { lexicon.forEach { (word, frequency) -> trie(word, frequency) } }
        trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
    }

    private fun Trie.trie(word: String, frequency: Int) = insert(word, frequency)

    private fun decode(path: List<GesturePoint>): List<String> =
        beam.decode(path, grid, keyWidth, sources, workspace, 4).map { it.word }

    /**
     * A stroke through [stops], each either a letter or `'^'` for the shift
     * key, drawn as straight lines sampled every few pixels with a steady
     * clock. Points inside the shift key are dropped, as the keyboard drops
     * them, and each visit to it recorded as a cut — so this returns what the
     * screen hands the detour trimmer.
     */
    private fun stroke(stops: String): Pair<List<GesturePoint>, IntArray> {
        val anchors = stops.map { c ->
            if (c == '^') shiftCenter else centers.getValue(c.code).let { GesturePoint(it.x, it.y) }
        }
        val points = ArrayList<GesturePoint>()
        val cuts = ArrayList<Int>()
        var clock = 1000L
        var wasOverShift = false
        fun emit(x: Float, y: Float) {
            val overShift = x >= shiftLeft && x < shiftRight && y >= shiftTop && y < shiftBottom
            if (overShift && !wasOverShift) cuts.add(points.size)
            wasOverShift = overShift
            if (!overShift) points.add(GesturePoint(x, y, clock))
            clock += 8L
        }
        emit(anchors[0].x, anchors[0].y)
        for (i in 0 until anchors.size - 1) {
            val a = anchors[i]
            val b = anchors[i + 1]
            val steps = maxOf(1, (distance(a, b) / 4f).toInt())
            for (step in 1..steps) {
                val t = step / steps.toFloat()
                emit(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
            }
        }
        return points to cuts.toIntArray()
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    private fun trimmed(stops: String): GlideShiftDetour.Trimmed {
        val (points, cuts) = stroke(stops)
        return GlideShiftDetour.trim(points, cuts, keyWidth, key = shiftCenter)
    }

    private fun cased(stops: String, word: String, shout: Boolean = false): String {
        val trim = trimmed(stops)
        val alignment = beam.align(word, trim.points, grid, keyWidth, workspace)
        return GlideCase.Letters(trim.cuts, shout).caseWord(word, alignment)
    }

    @Test
    fun `the reporter's stroke reads there once the legs are cut`() {
        val (raw, cuts) = stroke("t^here")
        // Only the key's own points dropped: the legs spell the misread.
        assertNotEquals("there", decode(raw).firstOrNull())
        val trim = GlideShiftDetour.trim(raw, cuts, keyWidth, key = shiftCenter)
        assertEquals("there", decode(trim.points).first())
    }

    @Test
    fun `a crossing right after the first letter names that letter`() {
        assertEquals("There", cased("t^here", "there"))
    }

    @Test
    fun `a crossing mid-word names the letter the finger came from`() {
        assertEquals("hello", decode(trimmed("he^llo").points).first())
        assertEquals("hEllo", cased("he^llo", "hello"))
    }

    @Test
    fun `a doubled letter takes its capital from one crossing`() {
        assertEquals("hello", decode(trimmed("h^el^o").points).first())
        assertEquals("HeLLo", cased("h^el^o", "hello"))
    }

    @Test
    fun `a crossing after the last letter names it and the stroke still reads`() {
        val trim = trimmed("hello^")
        assertEquals("hello", decode(trim.points).first())
        assertEquals("hellO", cased("hello^", "hello"))
    }

    @Test
    fun `ending on shift shouts`() {
        assertEquals("HELLO", cased("hello^", "hello", shout = true))
    }

    @Test
    fun `two words drawn with mixed capitals`() {
        assertEquals("leantype", decode(trimmed("l^eant^ype").points).first())
        assertEquals("LeanType", cased("l^eant^ype", "leantype"))
    }

    @Test
    fun `a stroke with no crossing is handed back as it is`() {
        val (points, _) = stroke("hello")
        val trim = GlideShiftDetour.trim(points, IntArray(0), keyWidth)
        assertSame(points, trim.points)
        assertEquals(0, trim.cuts.size)
    }

    @Test
    fun `the cut sits where the letter was`() {
        val trim = trimmed("he^llo")
        assertEquals(1, trim.cuts.size)
        // h to e is one key of the h-e-l-o path's four; the cut is at e.
        val alignment = beam.align("hello", trim.points, grid, keyWidth, workspace)!!
        val eFraction = alignment.samples[1] / (GlideWorkspace.SAMPLE_POINTS - 1f)
        assertEquals(eFraction, trim.cuts[0], 0.08f)
    }

    @Test
    fun `the clock closes up over the cut`() {
        val (raw, cuts) = stroke("he^llo")
        val trim = GlideShiftDetour.trim(raw, cuts, keyWidth, key = shiftCenter)
        // The stroke is drawn at 4px a sample, 8ms a sample: half a pixel a
        // millisecond. The detour took hundreds of milliseconds; after the cut
        // no step may take much longer than its own length would at that
        // speed — the jump from e to l included, which is what the decoder
        // would otherwise read as a pause on e.
        var previous = trim.points.first()
        for (p in trim.points.drop(1)) {
            assertTrue("clock went backwards", p.t >= previous.t)
            val dt = p.t - previous.t
            val dd = distance(previous, p)
            // A little under the drawn speed: the stroke's own pace is read
            // with the key's dropped points out of its length but not out of
            // its time.
            assertTrue("a step of $dt ms over ${dd}px survived the cut", dt <= dd / 0.4f + 16f)
            previous = p
        }
        assertTrue(
            "the detour's time survived: ${trim.points.last().t - trim.points.first().t} ms",
            trim.points.last().t - trim.points.first().t < raw.last().t - raw.first().t - 500L,
        )
    }

    @Test
    fun `without an alignment the cut is read along the word`() {
        val letters = GlideCase.Letters(floatArrayOf(0f, 1f), shout = false)
        assertEquals("HellO", letters.caseWord("hello", null))
        assertEquals("heLlo", GlideCase.Letters(floatArrayOf(0.5f), false).caseWord("hello", null))
    }

    @Test
    fun `a shout needs no alignment`() {
        assertEquals("HELLO", GlideCase.Letters(FloatArray(0), shout = true).caseWord("hello", null))
    }
}
