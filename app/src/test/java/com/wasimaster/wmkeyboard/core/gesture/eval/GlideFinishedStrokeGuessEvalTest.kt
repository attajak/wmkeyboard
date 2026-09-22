// The harness runs on the JVM only and stdout IS its report channel.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.GlideGuessGate
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import kotlin.math.ln
import org.junit.Test

/**
 * What "finish long words early" costs a stroke that was drawn to its end.
 *
 * [GlideLookAheadEvalTest] measures guesses on strokes cut short, which is the
 * case the feature is for. This measures the other case, which is most
 * strokes: the word was drawn in full, and every guess put in front of it is
 * a word the user did not ask for (#317).
 */
class GlideFinishedStrokeGuessEvalTest {

    private companion object {
        const val SEED = 7L
        const val CASES = 600
        const val RANK_DEPTH = 8
        const val LEXICON_WORDS = 2_000
        val LOG_USER_WEIGHT = ln(500.0)
        val MARGINS = listOf("eager" to 1.0, "confident" to 4.0)
    }

    private fun load(): List<Pair<String, Int>> {
        val candidates = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("en.txt not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    /** The service's `admitLookAhead` as shipped in 0.5.11. */
    private fun admitOld(decoded: List<GlideBeam.Candidate>, margin: Double): List<GlideBeam.Candidate> {
        if (decoded.none { it.ahead > 0 }) return decoded
        val bestRead = decoded.firstOrNull { it.ahead == 0 } ?: return decoded
        return decoded.filter { it.ahead == 0 || it.score - bestRead.score >= margin }
    }

    /** PREFER_LEARNED as shipped in 0.5.11: the learned leader, guess or not, against the full leader. */
    private fun preferLearnedOld(learned: List<GlideBeam.Candidate>, full: List<GlideBeam.Candidate>) =
        learned.firstOrNull().let { best ->
            if (best != null && (full.isEmpty() || best.shapeCost <= full[0].shapeCost)) learned else full
        }

    @Test
    fun aFinishedStrokeKeepsItsWord() {
        val entries = load()
        val grid = GlideGrid.of(BuiltInLayouts.QWERTY)
        val keys = GlideKeyMap.of(grid.keyCenters(SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH)
        val beam = GlideBeam()
        val ws = GlideWorkspace()
        val dictionary = Trie().apply { entries.forEach { (w, f) -> insert(w, f) } }
        val lexicon = Trie().apply { entries.take(LEXICON_WORDS).forEach { (w, f) -> insert(w, 1 + f / 500) } }
        val learnedSources = lexicon.walkers().map {
            FuzzyBeamSearch.WalkSource(it, LOG_USER_WEIGHT, FuzzyBeamSearch.Tier.USER)
        }
        val allSources = dictionary.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        } + learnedSources
        fun decode(path: List<GesturePoint>, sources: List<FuzzyBeamSearch.WalkSource>, guess: Boolean) =
            beam.decode(path, keys, SwipeCorpus.KEY_WIDTH, sources, ws, RANK_DEPTH, lookAhead = if (guess) RANK_DEPTH else 0)

        val out = StringBuilder("finished strokes: dictionary + ${LEXICON_WORDS}-word learned lexicon at ln(500)\n")
        out.append("  top1 = intended leads; hijack = look-ahead off was right, a guess leads instead\n\n")
        for (noise in listOf(SwipeCorpus.Noise.TYPICAL, SwipeCorpus.Noise.SLOPPY)) {
            val cases = SwipeCorpus(SEED, grid = grid).generate(entries, noise, CASES)
            // policy name -> (margin -> list) ; baseline = same policy with look-ahead off
            val policies = linkedMapOf<String, (List<GesturePoint>, Double) -> List<GlideBeam.Candidate>>(
                "OFF" to { p, m -> admitOld(decode(p, allSources, true), m) },
                "OFF fixed" to { p, m -> GlideGuessGate.admit(decode(p, allSources, true), m) },
                "PREFER_LEARNED 0.5.11" to { p, m ->
                    admitOld(preferLearnedOld(decode(p, learnedSources, true), decode(p, allSources, false)), m)
                },
                "PREFER_LEARNED fixed" to { p, m ->
                    GlideGuessGate.admit(
                        GlideGuessGate.preferLearned(decode(p, learnedSources, true), decode(p, allSources, false)),
                        m,
                    )
                },
                "LEARNED_ONLY 0.5.11" to { p, m -> admitOld(decode(p, learnedSources, true), m) },
                "LEARNED_ONLY fixed" to { p, m -> GlideGuessGate.admit(decode(p, learnedSources, true), m) },
            )
            val baselines = mapOf<String, (List<GesturePoint>) -> List<GlideBeam.Candidate>>(
                "OFF" to { p -> decode(p, allSources, false) },
                "OFF fixed" to { p -> decode(p, allSources, false) },
                "PREFER_LEARNED 0.5.11" to { p ->
                    preferLearnedOld(decode(p, learnedSources, false), decode(p, allSources, false))
                },
                "PREFER_LEARNED fixed" to { p ->
                    preferLearnedOld(decode(p, learnedSources, false), decode(p, allSources, false))
                },
                "LEARNED_ONLY 0.5.11" to { p -> decode(p, learnedSources, false) },
                "LEARNED_ONLY fixed" to { p -> decode(p, learnedSources, false) },
            )
            for ((name, policy) in policies) {
                var baseRight = 0
                val right = IntArray(MARGINS.size)
                val hijack = IntArray(MARGINS.size)
                val samples = Array(MARGINS.size) { ArrayList<String>() }
                for (case in cases) {
                    val baseOk = baselines.getValue(name)(case.path).firstOrNull()?.word
                        .equals(case.intended, ignoreCase = true)
                    if (baseOk) baseRight++
                    MARGINS.forEachIndexed { i, (_, margin) ->
                        val lead = policy(case.path, margin).firstOrNull()
                        val ok = lead?.word.equals(case.intended, ignoreCase = true)
                        if (ok) right[i]++
                        if (baseOk && !ok && lead != null && lead.ahead > 0) {
                            hijack[i]++
                            if (samples[i].size < 10) samples[i] += "${case.intended}→${lead.word}"
                        }
                    }
                }
                out.append("[$noise] $name — look-ahead off top1 ${pct(baseRight)}\n")
                MARGINS.forEachIndexed { i, (m, _) ->
                    out.append("  $m: top1 ${pct(right[i])}  hijack ${pct(hijack[i])}  ${samples[i]}\n")
                }
            }
            out.append("\n")
        }
        println(out)
        File("build/reports/gestureeval").mkdirs()
        File("build/reports/gestureeval/finished-stroke-guess.txt").writeText(out.toString())
    }

    private fun pct(n: Int) = String.format(Locale.ROOT, "%.3f", n / CASES.toDouble())
}
