// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSample
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeSource
import com.wasimaster.wmkeyboard.core.gesture.GlideShapeStore
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.gesture.KeyOffsets
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import org.junit.Test

/**
 * Issue #213's ladder: a word whose first letter sits next to another word's,
 * drawn from further and further over that neighbour, corrected back every
 * time. How far the correction can carry the reading is what the reporter was
 * measuring by hand, and what this measures on the corpus.
 *
 * The step is a *start drift*: the stroke for the intended word, with its
 * touch-down pulled toward the neighbour key and the pull faded out by the
 * time the finger reaches the second letter. That is the one thing the
 * reporter varied ("start closer to the v"), and it leaves the rest of the
 * stroke the hand's own.
 *
 * At each rung the user's correction is replayed exactly as the service
 * replays it on a strip pick: the reading that lost is rejected against its own
 * shape, the stroke is learned as the word that was picked instead, and the
 * hand model is retaught against that word (see
 * `WMKeyboardService.onSuggestionPicked`).
 *
 * **What the two columns are worth.** `cold` is a correction that teaches
 * nothing, `taught` one that teaches all three. Before issue #213 that was not
 * a hypothetical split but the difference between two ways of making the same
 * correction: tapping the right word on the strip took the `taught` path, and
 * backspacing first — then redrawing, or picking off the retry strip — took the
 * `cold` one, because the undo threw the stroke away. The gap between the rows
 * is what that cost, and both routes teach now.
 *
 * The hand model is what carries it. The shapes are centred and scaled to unit
 * spread before they are stored, so where a stroke *sat* is normalised out of
 * them: two strokes that differ only in where they started are nearly the same
 * shape, and the shape channel has almost nothing to say about this kind of
 * mistake. `KeyOffsets` is the learner that owns absolute position, and a
 * ladder that leaves it out measures the wrong instrument.
 *
 * Reported rather than gated: the number that matters is the drift the learning
 * stops carrying, and a floor on it would be a floor on the corpus's idea of a
 * hand rather than on the decoder.
 *
 * Measured 2026-09-17 at seed 71, signature 7, six corrections a rung:
 * cold `8 8 8 6 6 4 2`, taught `8 7 8 6 6 6 3` across drifts 0.00 to 0.90. The
 * two rungs that move are 0.75 and 0.90; what is left at 0.45 and 0.60 is the
 * `fill`/`hall` pairs, where the rival the decoder actually offers is `gil` and
 * `gal` rather than `gill` and `gall`, so those two measure doubled-letter
 * handling as much as they measure a start drift.
 */
class GlideStartDriftLadderTest {

    private companion object {
        const val SEED = 71L
        const val SIGNATURE = 7L

        /** How many corrections the user makes at each rung before it is judged. */
        const val ROUNDS = 6

        /** Start drifts, as a fraction of the way from the first key to its neighbour. */
        val DRIFTS = listOf(0.0f, 0.15f, 0.30f, 0.45f, 0.60f, 0.75f, 0.90f)

        /**
         * Pairs of (intended word, the neighbour-key word it is misread as).
         * Every one is a real pair off QWERTY, with the two first letters on
         * adjacent keys and the rest of the word shared.
         */
        val PAIRS = listOf(
            "can" to "van",
            "cat" to "vat",
            "bad" to "nad",
            "fill" to "gill",
            "hall" to "gall",
            "rain" to "tain",
            "took" to "rook",
            "mine" to "nine",
        )
    }

    private fun entries(): List<Pair<String, Int>> {
        val file = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .firstOrNull { it.exists() } ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    @Test
    fun correctionsCarryTheReadingUpToADrift() {
        val entries = entries()
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        val sources = trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
        val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
        val centers = grid.keyCenters(SwipeCorpus.KEY_WIDTH)
        val keys = GlideKeyMap.of(centers, SwipeCorpus.KEY_WIDTH)
        val layout = GlideKeyMap.fingerprint(centers, SwipeCorpus.KEY_WIDTH)
        val signature = SwipeCorpus.Signature(SIGNATURE)
        val profile = SwipeCorpus.Noise.TYPICAL.profile
        val beam = GlideBeam()
        val ws = GlideWorkspace()

        println("=== issue #213 start-drift ladder (seed=$SEED, signature=$SIGNATURE, $ROUNDS corrections a rung) ===")
        println("drift  cold  taught")

        val coldRow = ArrayList<String>()
        val warmRow = ArrayList<String>()
        for (drift in DRIFTS) {
            var cold = 0
            var warm = 0
            for ((word, rival) in PAIRS) {
                val store = GlideShapeStore(null)
                // The hand model learns from the same corrections the shapes
                // do, and moves the grid under the decoder. Both are part of
                // "learn my swipe style", so a ladder that leaves one out is
                // not measuring what the user has switched on.
                val hand = KeyOffsets(null)
                fun decodeKeys(): GlideKeyMap =
                    if (hand.isEmpty()) keys else GlideKeyMap.of(hand.shifted(centers, SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH)
                fun teachHand(path: List<GesturePoint>, target: String) {
                    val aligned = beam.align(target, path, keys, SwipeCorpus.KEY_WIDTH, ws) ?: return
                    hand.observe(
                        List(aligned.size) { i ->
                            val k = aligned.keys[i]
                            KeyOffsets.Observation(keys.keyX[k], keys.keyY[k], aligned.x[i], aligned.y[i])
                        },
                    )
                }
                // A hand that has swiped this word before, at no drift: the
                // shapes the user already has when the ladder starts.
                val settled = SwipeCorpus(SEED, grid = grid, signature = signature)
                for (round in 0 until 3) {
                    val path = SwipeCorpus(SEED + round, grid = grid, signature = signature)
                        .swipe(word, profile) ?: continue
                    beam.sampleShape(path, SwipeCorpus.KEY_WIDTH, ws)
                        ?.let { store.learn(GlideShapeSample(layout, it), word) }
                }
                check(settled.swipe(word, profile) != null) { "$word cannot be drawn" }

                // Rung one: the same drift, read with only those shapes.
                val probe = drifted(word, rival, drift, grid, profile, SEED + 100)
                    ?: error("$word cannot be drifted")
                if (top(beam, probe, decodeKeys(), sources, ws, store.forLayout(layout)) == word) cold++

                // The user corrects, over and over, the way the service does:
                // the losing reading's shape is rejected, the stroke is learned
                // as the word picked instead, and the hand model is retaught
                // against the word picked rather than the word read.
                for (round in 0 until ROUNDS) {
                    val path = drifted(word, rival, drift, grid, profile, SEED + 200 + round) ?: continue
                    val read = top(beam, path, decodeKeys(), sources, ws, store.forLayout(layout))
                    val shape = beam.sampleShape(path, SwipeCorpus.KEY_WIDTH, ws) ?: continue
                    if (read != null && read != word) store.reject(layout, read, shape)
                    store.learn(GlideShapeSample(layout, shape), word)
                    teachHand(path, word)
                }
                val after = store.forLayout(layout)
                val taughtKeys = decodeKeys()
                if (top(beam, probe, taughtKeys, sources, ws, after) == word) warm++
                // Why it did or did not move: is the word still a candidate at
                // all, how far behind is it in nats, how many shapes did the
                // corrections leave it, and how close is the nearest of them to
                // the stroke being judged.
                val read = beam.decode(probe, taughtKeys, SwipeCorpus.KEY_WIDTH, sources, ws, limit = 6, shapes = after)
                    .filter { it.ahead == 0 }
                val mine = read.firstOrNull { it.word == word }
                val lead = read.firstOrNull()
                val probeShape = beam.sampleShape(probe, SwipeCorpus.KEY_WIDTH, ws)
                val own = probeShape?.let { after?.minDistance(word, it) } ?: -1f
                println(
                    String.format(
                        Locale.ROOT,
                        "  %.2f %-5s present=%s gap=%s shapes=%d own=%.3f lead=%s",
                        drift, word,
                        if (mine != null) "yes" else "NO",
                        if (mine != null && lead != null) {
                            String.format(Locale.ROOT, "%.2f", lead.score - mine.score)
                        } else {
                            "-"
                        },
                        store.countFor(word), own, lead?.word ?: "-",
                    ),
                )
            }
            coldRow.add("$cold")
            warmRow.add("$warm")
            println(
                String.format(
                    Locale.ROOT,
                    "%.2f   %d/%d   %d/%d",
                    drift, cold, PAIRS.size, warm, PAIRS.size,
                ),
            )
        }
        println("cold:   ${coldRow.joinToString(" ")}")
        println("taught: ${warmRow.joinToString(" ")}")
    }

    /**
     * [word]'s stroke with its touch-down pulled [drift] of the way toward
     * [rival]'s first key, the pull fading to nothing by the time the finger
     * reaches the word's second letter.
     */
    @Suppress("LongParameterList", "ReturnCount")
    private fun drifted(
        word: String,
        rival: String,
        drift: Float,
        grid: GlideGrid,
        profile: SwipeCorpus.Profile,
        seed: Long,
    ): List<GesturePoint>? {
        val path = SwipeCorpus(seed, grid = grid, signature = SwipeCorpus.Signature(SIGNATURE))
            .swipe(word, profile) ?: return null
        if (drift <= 0f) return path
        val from = grid.centerOf(word[0]) ?: return null
        val to = grid.centerOf(rival[0]) ?: return null
        val second = grid.centerOf(word[1]) ?: return null
        val dx = (to.x - from.x) * drift * SwipeCorpus.KEY_WIDTH
        val dy = (to.y - from.y) * drift * SwipeCorpus.KEY_WIDTH
        val secondX = second.x * SwipeCorpus.KEY_WIDTH
        val secondY = second.y * SwipeCorpus.KEY_WIDTH
        // Where the pull has run out: the first sample that reaches the second
        // letter's key. Everything after it is the hand's own stroke.
        var fade = path.indices.firstOrNull { i ->
            val ex = path[i].x - secondX
            val ey = path[i].y - secondY
            ex * ex + ey * ey <= SwipeCorpus.KEY_WIDTH * SwipeCorpus.KEY_WIDTH / 4f
        } ?: (path.size / 2)
        if (fade <= 0) fade = 1
        return path.mapIndexed { i, p ->
            if (i >= fade) {
                p
            } else {
                val w = 1f - i.toFloat() / fade
                GesturePoint(p.x + dx * w, p.y + dy * w, p.t)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun top(
        beam: GlideBeam,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        sources: List<FuzzyBeamSearch.WalkSource>,
        ws: GlideWorkspace,
        shapes: GlideShapeSource?,
    ): String? = beam.decode(path, keys, SwipeCorpus.KEY_WIDTH, sources, ws, limit = 4, shapes = shapes)
        .firstOrNull { it.ahead == 0 }?.word
}
