// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideCoverage
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.gesture.RomanizedIndex
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.SpellingMap
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.eval.EvalMetrics
import com.wasimaster.wmkeyboard.core.prediction.eval.SuggestMetrics
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline glide-decoder quality harness: runs the real decoder over the real
 * shipped word lists against a reproducible synthetic swipe corpus, and asserts
 * the headline metrics never regress past [GestureEvalBaseline].
 *
 * Four subjects, chosen so a regression has somewhere to show:
 *
 *  - **English on QWERTY** — the case with the longest history, and the one the
 *    decoder's weights were swept against.
 *  - **Bengali on Probhat** — non-Latin, and the reason the decoder lets several
 *    characters share a key. ক and খ sit on one key, so half the consonants in
 *    the language are shape-identical to their aspirated twins and only the
 *    language model tells them apart. If that design is wrong, it is wrong here.
 *  - **A Cyrillic control** — a twelve-column grid with a synthetic lexicon,
 *    touching no per-language machinery whatsoever. It answers "is this a
 *    decoder problem or a Bengali problem" without anyone having to guess.
 *  - **Bengali on Avro** — a Latin grid whose output is Bengali, measured the
 *    whole way: the corpus traces a romanization and the case counts only if
 *    the Bengali word that comes back is the one that spelling means.
 *
 * Prints a metric table to stdout and writes a diffable JSON artifact to
 * `build/reports/gestureeval/metrics.json`.
 */
class GestureEvalTest {

    private companion object {
        const val SEED = 42L
        const val CASES_PER_LEVEL = 500

        /**
         * Deeper than the four candidates production shows, so MRR keeps
         * resolving after rank 4 instead of flattening into "missed". top1 and
         * top3 are unaffected by the depth.
         */
        const val RANK_DEPTH = 8

        /**
         * Latin to Cyrillic, for the control lexicon. Readable rather than a
         * transliteration standard — the point is a word list with realistic
         * length and frequency statistics on a grid that is neither QWERTY nor
         * Bengali, not a claim about Russian.
         */
        const val LATIN = "abcdefghijklmnopqrstuvwxyz"
        const val CYRILLIC = "абцдефгхийклмнопярстувшжыз"
    }

    private class Subject(
        val name: String,
        val grid: GlideGrid,
        /** What the corpus draws: the spellings a finger traces on this grid. */
        val entries: List<Pair<String, Int>>,
        val floors: GestureEvalBaseline.Floors,
        /**
         * What a decoded spelling means, when the grid's alphabet and the
         * language's are not the same one. Null everywhere but Avro, where the
         * corpus draws a romanization and the decoder must answer in Bengali —
         * so the case's expected answer is the Bengali word, not the spelling
         * that was traced.
         */
        val romanization: RomanizedIndex? = null,
        val expected: (String) -> String = { it },
    )

    private fun load(name: String): List<Pair<String, Int>> {
        val candidates = listOf(File("dictionaries-src/$name"), File("app/dictionaries-src/$name"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("$name not found (cwd=${File(".").absolutePath})")
        return file.inputStream().use { DictionaryLoader.loadEntries(it) }
    }

    /**
     * English words respelled in Cyrillic, keeping their frequencies.
     *
     * A real Russian list would be better and is not in this repository — the
     * two bundled lists are English and Bengali. What this controls for is the
     * grid and the script, and respelling varies both while holding the word
     * statistics fixed. Deliberately not a Russian accuracy number.
     */
    private fun cyrillicControl(english: List<Pair<String, Int>>): List<Pair<String, Int>> {
        val map = LATIN.indices.associate { LATIN[it] to CYRILLIC[it] }
        return english.mapNotNull { (word, frequency) ->
            val respelled = buildString(word.length) {
                for (ch in word) append(map[ch.lowercaseChar()] ?: return@mapNotNull null)
            }
            respelled to frequency
        }
    }

    @Test
    fun qualityDoesNotRegress() {
        val english = load("en.txt")
        val subjects = listOf(
            Subject(
                "en/qwerty", GlideGrid.of(BuiltInLayouts.QWERTY), english,
                GestureEvalBaseline.ENGLISH,
            ),
            Subject(
                "bn/probhat", GlideGrid.of(BuiltInLayouts.PROBHAT), load("bn.txt"),
                GestureEvalBaseline.BENGALI,
            ),
            Subject(
                "ru/jcuken", GlideGrid.of(BuiltInLayouts.RUSSIAN), cyrillicControl(english),
                GestureEvalBaseline.CYRILLIC,
            ),
            avro(),
        )

        val report = StringBuilder()
        for (subject in subjects) {
            measure(subject, report)
        }
        writeReport(report.toString())
    }

    /**
     * Avro: a QWERTY grid whose output is Bengali.
     *
     * The corpus draws the curated romanized spellings — "amake", "bhalo",
     * "tmr" — and a case counts as decoded only when the *Bengali* the decoder
     * answers with is the Bengali that spelling stands for. That is the whole
     * path end to end: Latin stroke in, Bengali word out.
     *
     * Built from the shipped assets rather than a fixture, so a change to either
     * spelling list shows up here.
     */
    private fun avro(): Subject {
        val bengali = load("bn.txt")
        val phonetic = BengaliPhoneticIndex(bengali)
        val spellings = asset("en_bn.tsv").use { en ->
            asset("bn_rom.tsv").use { rom -> SpellingMap.load(en, rom) }
        }
        val romanization = RomanizedIndex.of(
            spellings = spellings,
            phonetic = phonetic,
            nativeFrequency = phonetic::frequencyOf,
        )
        // Only spellings that resolve, and weighted by the Bengali behind them,
        // so the corpus draws the words people actually reach for.
        val entries = spellings.spellings.mapNotNull { spelling ->
            val form = spellings.lookup(spelling).firstOrNull() ?: return@mapNotNull null
            if (spelling.length < 3 || !spelling.all { it in 'a'..'z' }) return@mapNotNull null
            spelling to phonetic.frequencyOf(form).coerceAtLeast(1)
        }
        val meaning = spellings.spellings.associateWith { spellings.lookup(it).first() }
        return Subject(
            "bn/avro", GlideGrid.of(BuiltInLayouts.AVRO), entries,
            GestureEvalBaseline.AVRO,
            romanization = romanization,
            expected = { meaning.getValue(it) },
        )
    }

    private fun asset(name: String) =
        listOf(File("src/main/assets/dictionaries/$name"), File("app/src/main/assets/dictionaries/$name"))
            .first { it.exists() }
            .inputStream()

    private fun measure(subject: Subject, report: StringBuilder) {
        val trie = Trie().apply {
            subject.entries.forEach { (word, frequency) -> insert(word, frequency) }
        }
        val sources = subject.romanization?.walkSources() ?: trie.walkers().map {
            FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY)
        }
        val keys = GlideKeyMap.of(
            subject.grid.keyCenters(SwipeCorpus.KEY_WIDTH), SwipeCorpus.KEY_WIDTH,
        )
        val beam = GlideBeam()
        val workspace = GlideWorkspace()
        val corpus = SwipeCorpus(SEED, grid = subject.grid)

        val byNoise = LinkedHashMap<SwipeCorpus.Noise, SuggestMetrics>()
        val emptyRate = LinkedHashMap<SwipeCorpus.Noise, Double>()
        val allRanks = ArrayList<Int?>(CASES_PER_LEVEL * SwipeCorpus.Noise.entries.size)
        for (noise in SwipeCorpus.Noise.entries) {
            var empty = 0
            val ranks = corpus.generate(subject.entries, noise, CASES_PER_LEVEL).map { case ->
                val raw = beam.decode(
                    case.path, keys, SwipeCorpus.KEY_WIDTH, sources, workspace, RANK_DEPTH,
                )
                val decoded = subject.romanization?.resolve(raw) ?: raw
                if (decoded.isEmpty()) empty++
                val want = subject.expected(case.intended)
                val at = decoded.indexOfFirst { it.word.equals(want, ignoreCase = true) }
                if (at >= 0) at + 1 else null
            }
            byNoise[noise] = EvalMetrics.suggest(ranks)
            emptyRate[noise] = empty / CASES_PER_LEVEL.toDouble()
            allRanks.addAll(ranks)
        }
        val overall = EvalMetrics.suggest(allRanks)

        // Coverage is what decides on a device whether this layout is offered
        // for this language at all, so the harness reports and asserts it: a
        // score for a grid production would switch off is a score nobody can
        // reach.
        val coverage = GlideCoverage.measure(
            subject.romanization?.walkSources()?.map { it.walker } ?: trie.walkers(),
            subject.grid.alphabet,
        )
        printTable(subject, byNoise, emptyRate, overall, coverage)
        appendJson(report, subject, byNoise, emptyRate, overall, coverage)

        assertTrue(
            "${subject.name}: coverage $coverage would switch glide off in production",
            coverage >= GlideCoverage.THRESHOLD,
        )
        val f = subject.floors
        floor(subject, "top1", overall.top1, f.top1)
        floor(subject, "top3", overall.top3, f.top3)
        floor(subject, "mrr", overall.mrr, f.mrr)
        floor(subject, "clean top1", top1(byNoise, SwipeCorpus.Noise.CLEAN), f.clean)
        floor(subject, "light top1", top1(byNoise, SwipeCorpus.Noise.LIGHT), f.light)
        floor(subject, "typical top1", top1(byNoise, SwipeCorpus.Noise.TYPICAL), f.typical)
        floor(subject, "sloppy top1", top1(byNoise, SwipeCorpus.Noise.SLOPPY), f.sloppy)
    }

    private fun top1(byNoise: Map<SwipeCorpus.Noise, SuggestMetrics>, noise: SwipeCorpus.Noise): Double =
        byNoise.getValue(noise).top1

    private fun floor(subject: Subject, name: String, value: Double, baseline: Double) {
        assertTrue(
            "${subject.name} $name $value regressed below ${baseline - GestureEvalBaseline.TOLERANCE}",
            value >= baseline - GestureEvalBaseline.TOLERANCE,
        )
    }

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    private fun printTable(
        subject: Subject,
        byNoise: Map<SwipeCorpus.Noise, SuggestMetrics>,
        emptyRate: Map<SwipeCorpus.Noise, Double>,
        overall: SuggestMetrics,
        coverage: Float,
    ) {
        println(
            "=== glide eval ${subject.name} (seed=$SEED, ${CASES_PER_LEVEL}/level, " +
                "n=${overall.n}, words=${subject.entries.size}, " +
                "keys=${subject.grid.alphabet.size}, coverage=${pct(coverage.toDouble())}) ==="
        )
        for ((noise, m) in byNoise) {
            // `empty` is the share the decoder refused outright. It separates a
            // pruning failure from a ranking failure, which the rank metrics
            // alone cannot tell apart.
            println(
                String.format(Locale.ROOT, "%-8s", noise.name) +
                    " top1=${pct(m.top1)}  top3=${pct(m.top3)}  mrr=${pct(m.mrr)}" +
                    "  empty=${pct(emptyRate.getValue(noise))}"
            )
        }
        println("OVERALL  top1=${pct(overall.top1)}  top3=${pct(overall.top3)}  mrr=${pct(overall.mrr)}")
    }

    private fun appendJson(
        out: StringBuilder,
        subject: Subject,
        byNoise: Map<SwipeCorpus.Noise, SuggestMetrics>,
        emptyRate: Map<SwipeCorpus.Noise, Double>,
        overall: SuggestMetrics,
        coverage: Float,
    ) {
        if (out.isNotEmpty()) out.append(",\n")
        out.append("  \"${subject.name}\": {\n")
        out.append("    \"words\": ${subject.entries.size},\n")
        out.append("    \"keys\": ${subject.grid.alphabet.size},\n")
        out.append("    \"coverage\": ${pct(coverage.toDouble())},\n")
        out.append("    \"n\": ${overall.n},\n")
        for ((noise, m) in byNoise) {
            val key = noise.name.lowercase(Locale.ROOT)
            out.append("    \"$key\": { ")
            out.append("\"top1\": ${pct(m.top1)}, ")
            out.append("\"top3\": ${pct(m.top3)}, ")
            out.append("\"mrr\": ${pct(m.mrr)}, ")
            out.append("\"empty\": ${pct(emptyRate.getValue(noise))} },\n")
        }
        out.append("    \"top1\": ${pct(overall.top1)},\n")
        out.append("    \"top3\": ${pct(overall.top3)},\n")
        out.append("    \"mrr\": ${pct(overall.mrr)}\n")
        out.append("  }")
    }

    private fun writeReport(body: String) {
        val out = File("build/reports/gestureeval/metrics.json")
        out.parentFile?.mkdirs()
        out.writeText("{\n\"seed\": $SEED,\n\"casesPerLevel\": $CASES_PER_LEVEL,\n$body\n}\n")
        println("metrics written to ${out.path}")
    }
}
