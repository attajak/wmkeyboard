// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.gesture.KeyOffsets
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a word finished early should teach the hand model (issue #262,
 * addendum 3).
 *
 * "Finish long words early" commits `dictionary` off a stroke that only ever
 * drew `d-i-c-t`. The hand model learns where the finger went for each letter by
 * laying the committed word back over the stroke, and until #262 it laid the
 * *whole* word there: ten letters forced onto a four-key stroke, the six the
 * finger never drew placed wherever the alignment could squeeze them, and every
 * one within [KeyOffsets.MAX_OBSERVED] of its key read as a miss of this hand.
 * The letters the finger actually drew are the only evidence the stroke holds.
 *
 * The harness plays a user who lifts early on long words — an unbiased hand, so
 * anything the model learns from them is error — then decodes ordinary words on
 * the grid the model has moved. `whole word` is the old lesson, `drawn` the
 * letters the stroke really covered, and `cold` a hand model that learned
 * nothing. The honest outcome for an unbiased hand is `drawn` ≈ `cold`.
 *
 * Measured 2026-09-22 at seed 262, 200 early lifts drawing four letters each,
 * 300 ordinary words: top-1 `cold` .9567, `whole word` .9467, `drawn` .9633.
 * The whole-word lesson learned 482 letters at a mean miss of 0.446 key widths,
 * half a key, off an unbiased hand; the drawn letters 776 at 0.292, which is
 * this corpus's ordinary corner cutting. Only the `drawn` floor is gated: the
 * whole-word row is the bug, kept printed so the cost stays visible.
 */
class GlideEarlyLiftLearningTest {

    private companion object {
        const val SEED = 262L
        const val EARLY_LIFTS = 200
        const val CONTROL_CASES = 300
        const val RANK_DEPTH = 8

        /** Shortest word worth finishing early, and how much of it the finger draws. */
        const val MIN_LONG_WORD = 8
        const val DRAWN_LETTERS = 4

        /** What the drawn-letters lesson may cost against learning nothing: ~1 binomial σ at 300 strokes. */
        const val MAX_LOSS = 0.015
    }

    private fun entries(): List<Pair<String, Int>> {
        val file = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
            .firstOrNull { it.exists() } ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    @Test
    fun anEarlyLiftTeachesOnlyTheLettersItDrew() {
        val entries = entries()
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        val sources = trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
        val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
        val centers = grid.keyCenters(SwipeCorpus.KEY_WIDTH)
        val keys = GlideKeyMap.of(centers, SwipeCorpus.KEY_WIDTH)
        val profile = SwipeCorpus.Noise.TYPICAL.profile
        val beam = GlideBeam()
        val ws = GlideWorkspace()

        // Long words, finished early: the stroke covers only their first letters.
        val long = entries.filter { (word, _) -> word.length >= MIN_LONG_WORD && word.all { it in 'a'..'z' } }
        val lifts = SwipeCorpus(SEED, grid = grid)
            .generate(long, SwipeCorpus.Noise.TYPICAL, EARLY_LIFTS * 2)
            .mapNotNull { case ->
                val drawn = case.intended.take(DRAWN_LETTERS)
                SwipeCorpus(SEED + case.intended.hashCode(), grid = grid).swipe(drawn, profile)
                    ?.let { Triple(case.intended, drawn, it) }
            }
            .take(EARLY_LIFTS)
        val control = SwipeCorpus(SEED + 1, grid = grid).generate(entries, SwipeCorpus.Noise.TYPICAL, CONTROL_CASES)

        val whole = KeyOffsets(null)
        val drawn = KeyOffsets(null)
        var wholeSeen = 0
        var drawnSeen = 0
        var wholeMiss = 0.0
        var drawnMiss = 0.0
        for ((word, prefix, path) in lifts) {
            teach(whole, beam, word, path, keys, ws)?.let { (n, miss) -> wholeSeen += n; wholeMiss += miss }
            teach(drawn, beam, prefix, path, keys, ws)?.let { (n, miss) -> drawnSeen += n; drawnMiss += miss }
        }

        val cold = top1(control.map { it.intended to it.path }, beam, keys, sources, ws)
        val afterWhole = top1(control.map { it.intended to it.path }, beam, shifted(whole, centers), sources, ws)
        val afterDrawn = top1(control.map { it.intended to it.path }, beam, shifted(drawn, centers), sources, ws)

        println("=== issue #262 early-lift hand lesson (seed=$SEED, ${lifts.size} early lifts, ${control.size} control) ===")
        println(
            String.format(
                Locale.ROOT,
                "whole word: %d letters learned, mean miss %.3f kw",
                wholeSeen, if (wholeSeen > 0) wholeMiss / wholeSeen else 0.0,
            ),
        )
        println(
            String.format(
                Locale.ROOT,
                "drawn:      %d letters learned, mean miss %.3f kw",
                drawnSeen, if (drawnSeen > 0) drawnMiss / drawnSeen else 0.0,
            ),
        )
        println(String.format(Locale.ROOT, "control top-1: cold=%.4f whole=%.4f drawn=%.4f", cold, afterWhole, afterDrawn))

        assertTrue("the drawn letters cost ${cold - afterDrawn} against learning nothing", cold - afterDrawn <= MAX_LOSS)
    }

    /** Teaches [hand] [word] laid over [path]; the letters it learned and their summed miss, in key widths. */
    @Suppress("LongParameterList")
    private fun teach(
        hand: KeyOffsets,
        beam: GlideBeam,
        word: String,
        path: List<GesturePoint>,
        keys: GlideKeyMap,
        ws: GlideWorkspace,
    ): Pair<Int, Double>? {
        val aligned = beam.align(word, path, keys, SwipeCorpus.KEY_WIDTH, ws) ?: return null
        val observations = List(aligned.size) { i ->
            val k = aligned.keys[i]
            KeyOffsets.Observation(keys.keyX[k], keys.keyY[k], aligned.x[i], aligned.y[i])
        }
        hand.observe(observations) ?: return null
        val usable = observations.filter {
            abs(it.seenX - it.keyX) <= KeyOffsets.MAX_OBSERVED &&
                abs(it.seenY - it.keyY) <= KeyOffsets.MAX_OBSERVED
        }
        val miss = usable.sumOf { o ->
            val dx = (o.seenX - o.keyX).toDouble()
            val dy = (o.seenY - o.keyY).toDouble()
            sqrt(dx * dx + dy * dy)
        }
        return usable.size to miss
    }

    private fun shifted(hand: KeyOffsets, centers: List<com.wasimaster.wmkeyboard.core.gesture.KeyCenter>): GlideKeyMap =
        if (hand.isEmpty()) {
            GlideKeyMap.of(centers, SwipeCorpus.KEY_WIDTH)
        } else {
            GlideKeyMap.of(hand.shifted(centers, SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH)
        }

    private fun top1(
        cases: List<Pair<String, List<GesturePoint>>>,
        beam: GlideBeam,
        keys: GlideKeyMap,
        sources: List<FuzzyBeamSearch.WalkSource>,
        ws: GlideWorkspace,
    ): Double {
        var hits = 0
        for ((intended, path) in cases) {
            val decoded = beam.decode(path, keys, SwipeCorpus.KEY_WIDTH, sources, ws, RANK_DEPTH)
            if (decoded.firstOrNull()?.word.equals(intended, ignoreCase = true)) hits++
        }
        return hits / cases.size.toDouble()
    }
}
