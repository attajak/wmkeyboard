package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.ln

/**
 * The shared conversion decoder: given a buffer already split into units, work
 * out which words the user most likely meant.
 *
 * Every conversion composer here used to do its own version of this — walk the
 * cumulative prefixes longest-first, look each one up, stop at twelve. That has
 * three problems the lattice exists to fix.
 *
 *  - **It cannot see past the first word.** Longest-first ranks 你好 above 你
 *    because it is longer, not because the rest of the buffer works out. Scoring
 *    whole paths lets a shorter first word win when it leaves a better remainder,
 *    which is what makes typing a sentence and committing it possible.
 *  - **It enumerates ambiguity into strings, then truncates.** Fuzzy pinyin and
 *    T9 both build the cartesian product of per-unit readings and cut it off at a
 *    cap, which drops real candidates — alphabetically-late ones first, because
 *    that is the order the product happens to come out in. Here the ambiguity
 *    stays as [Input.options] and is resolved against
 *    [ConversionDictionary.prefixRange], so a reading no word starts with is
 *    pruned before it is ever built and the cost is what the dictionary contains
 *    rather than what the option lists multiply out to.
 *  - **It fills to a limit and breaks.** Whatever the first source of candidates
 *    was, it took all twelve slots. Collect-then-rank has no such accident.
 *
 * Prefix commit shapes what this can offer. `commitConversionPrefix` deletes
 * `composing[0, consumed)`, so **every candidate must start at input position 0**
 * — there is no way to commit the second word of a sentence without committing
 * the first. So the strip gets the whole decoded sentence at rank 0, then the
 * plausible *first* words below it, each re-ranked by how well the rest of the
 * buffer comes out behind it. Per-word alternatives under a sentence would need
 * the segment-narrowing UI a desktop IME has, which this keyboard does not.
 */
object Lattice {

    /**
     * A buffer prepared for decoding: where the unit boundaries fall in the input,
     * what reading each unit might spell, and what each of those spellings costs.
     *
     * The composer owns this translation, which is the point — Japanese counts
     * romaji while segmenting kana, T9 counts digits, Double Pinyin counts key
     * codes, and none of that leaks in here.
     */
    class Input(
        /** Size `units + 1`; `boundaries[k]` is the input chars the first k units ate. */
        val boundaries: IntArray,
        /** Per unit, the readings it could spell. Index 0 is what the user typed. */
        val options: Array<Array<String>>,
        /** Parallel to [options]: 0.0 for the as-typed reading, negative for a guess. */
        val penalties: Array<DoubleArray>,
        /**
         * Per unit, the index in [options] from which the readings are
         * *abbreviations* — the unit spells only the start of its syllable
         * (jianpin `h` for hao/hen/hua…) rather than a complete reading that
         * might have been misspelled. Defaults to `options[i].size`: no
         * abbreviations. The distinction matters to [Opts.maxAmbiguousSpan],
         * which caps guesses but must never cap deliberate abbreviation.
         */
        val partialFrom: IntArray = IntArray(options.size) { options[it].size },
        /**
         * Per unit, readings that cover this unit *and the next one* as a single
         * syllable — jianpin's `z` + `h` read as the digraph initial `zh…`, so
         * `zhg` still reaches 中国 while `sh` also reads as `s` + `h` (上海).
         * Empty by default. These are always abbreviations, never capped.
         */
        val merged: Array<Array<String>> = Array(options.size) { emptyArray() },
        /** Parallel to [merged]. */
        val mergedPenalties: Array<DoubleArray> = Array(options.size) { DoubleArray(0) },
    ) {
        val units: Int get() = options.size
    }

    /** [Input] for units whose reading is unambiguous — Zhuyin, Jyutping, kana. */
    fun input(readings: List<String>, inputLens: List<Int>): Input =
        options(readings.map { arrayOf(it) }, inputLens)

    /**
     * [Input] for units that could spell several readings, each equally intended —
     * a T9 digit code, where ambiguity is the keypad's doing and not the user's,
     * so nothing is penalized and the language model decides.
     */
    fun options(options: List<Array<String>>, inputLens: List<Int>): Input {
        val n = options.size
        val boundaries = IntArray(n + 1)
        for (i in 0 until n) boundaries[i + 1] = boundaries[i] + inputLens[i]
        return Input(
            boundaries,
            options.toTypedArray(),
            Array(n) { DoubleArray(options[it].size) },
        )
    }

    /** Decoder bounds and weights. Defaults suit Chinese; Japanese overrides one. */
    class Opts(
        /** Longest word, in syllables. Beyond ~6 a "word" is really a phrase. */
        val maxWordUnits: Int = 6,
        /**
         * Spans longer than this use only each unit's first reading.
         *
         * This is for *guesses*. Fuzzy pinyin is per-syllable spelling correction,
         * and someone who typed a five-syllable phrase with three fuzzy errors is
         * not worth a combinatorial price — the short edges the decoder stitches
         * together recover most of it anyway.
         *
         * It must be left wide open where the ambiguity is intrinsic rather than
         * the user's doing. On a T9 keypad *every* unit is ambiguous, so capping
         * this pins later units to whichever syllable sorted first and rebuilds
         * the exact alphabetical bias the lattice replaced: `726726726` would
         * offer nothing beginning `san`. Cost there is bounded by the dictionary
         * prune instead, which is what it is for.
         *
         * Abbreviations ([Input.partialFrom]) are exempt for the same reason: a
         * jianpin `zgrm` is four deliberately ambiguous units, and pinning the
         * third to `ra` would make 中国人民 untypeable.
         */
        val maxAmbiguousSpan: Int = 2,
        /**
         * Words kept per span, best-frequency first — raised to [limit] where
         * that is larger, since a single reading routinely has more characters
         * behind it than the strip shows and the expanded grid exists to show
         * them. Interior spans only matter for pathfinding, but the first span
         * *is* the candidate list.
         */
        val spanCandCap: Int = 16,
        /** Live paths carried between positions. */
        val beam: Int = 8,
        val limit: Int = 12,
        /**
         * Whether one character spells exactly one unit.
         *
         * True across the Chinese family: one Hanzi is one syllable, always. The
         * dictionary keys a *joined* reading, so a two-syllable key is shared with
         * every single character read the same way — 西安 and 现 both sit under
         * `xian`. When the user spells out two syllables (`xi'an`), 现 is not a
         * lower-ranked answer, it is not an answer at all: one character cannot
         * cover two syllables. Dropping those rows is what makes the apostrophe
         * worth typing.
         *
         * The reverse is only over-segmentation, not an error: typing `xian` as
         * one unit still legitimately means 西安, so a longer word is kept and
         * merely demoted by [overLengthPenalty].
         *
         * **False for Japanese**, where the rule is simply untrue — a kanji takes
         * however many mora it takes.
         */
        val charPerUnit: Boolean = true,
        /** Charged per character by which a word overruns its span's unit count. */
        val overLengthPenalty: Double = -1.2,
        /**
         * How many extra whole-buffer readings made of *several* words to offer
         * beyond the single best path, best first (issue #405). 0 offers none,
         * which is right for every buffer the user spelled out in full.
         *
         * These exist because the language model here is a unigram over words,
         * and a unigram cannot compare readings that split the buffer
         * differently: each extra word costs another `ln(totalFreq)`, so a
         * two-word reading loses to any single dictionary word covering the same
         * span by about fifteen nats whatever the words are. That is an artifact
         * of the normaliser, not a judgement — and it is exactly the readings
         * jianpin is for. `wdmm` is 我的妈妈, two of whose three words
         * (我, 的) are not a dictionary entry together, so the decoder answered
         * 味道妈妈 and the right reading appeared nowhere at all.
         *
         * So they are *offered*, not promoted: the unbiased best path keeps rank
         * 0 and these follow it, found with [stitchBonus] standing in for the
         * missing word-insertion term. Picking one teaches [CjkLearning] the
         * whole reading, after which it leads on its own.
         */
        val stitch: Int = 0,
        /**
         * The per-word bonus the [stitch] search runs with — the word-insertion
         * term a unigram model lacks. Measured against the shipped CC-CEDICT
         * pack: at 8.0 `wdmm` finds 我的妈妈 and `wbxhni` finds 我不喜欢你,
         * which is what the issue asked for; much above it the decoder stops
         * preferring phrases at all (`wdmm` → 我的们们) and much below it finds
         * nothing new. It never touches the ranking the strip leads with.
         */
        val stitchBonus: Double = 8.0,
    )

    /** A decoded candidate: what to commit, how much of the buffer it eats. */
    data class Cand(
        val text: String,
        val consumed: Int,
        val score: Double,
        val reading: String,
    )

    /**
     * How much the word's own frequency within its reading counts against the
     * language model. Normalized inside the reading's run, so it is always ≤ 0 and
     * exactly 0 for the run's most frequent word. Matters little in Chinese and a
     * lot in Japanese, where 行 is a common word under several unrelated readings.
     */
    private const val BETA = 0.5

    /** Score of committing a unit as its raw reading. */
    private val UNK_LOG = ln(1e-9)

    /**
     * What a learned pick is worth to an edge, times `ln(1 + times chosen)`.
     *
     * [CjkLearning.rank] already puts a previously-chosen candidate first, but
     * that is a sort of the finished list and so can only promote a reading the
     * decoder already found. This is the same knowledge applied *inside* the
     * search, where it decides which words a path is built out of: a user who
     * keeps choosing 好 for `hao` gets 好的 out of `hd`, which no re-sort of the
     * answers could have done because 好的 was never among them (issue #405).
     *
     * One pick is worth 0.69 and ten are worth 2.4 — enough to settle the near
     * ties the pack's frequency column is full of (和 970 against 好 941), not
     * enough to beat a genuinely commoner word.
     */
    private const val LEARN_WEIGHT = 1.0

    /** One dictionary word spanning `[from, to)` units, with its context-free score. */
    private class Edge(
        val from: Int,
        val to: Int,
        val text: String,
        val reading: String,
        /** Everything but the language model: emission, penalties, bonuses. */
        val emission: Double,
        val freq: Int,
        /**
         * A raw-reading edge rather than a dictionary word. These keep the lattice
         * connected across a syllable nothing is written with, so a path always
         * exists — but they are never offered as candidates. A strip that answered
         * `si` with "si" would be claiming a conversion it does not have; the raw
         * reading already commits through `composeBuffer` when the buffer is
         * flushed, which is where that fallback belongs.
         */
        val fallback: Boolean = false,
    )

    /** A live path: the word just committed, its running score, and where it came from. */
    private class Path(val word: String?, val score: Double, val prev: Path?, val edge: Edge?)

    /**
     * Decodes [input], best first.
     *
     * Rank 0 is the whole-buffer sentence when the best path uses more than one
     * word; the rest are first words, ranked by their own score plus the best the
     * remainder can do behind them ([suffixScores]). Always returns something for
     * a non-empty input — every unit has a raw-reading fallback edge, so no buffer
     * can trap the user with an empty strip.
     */
    fun decode(
        input: Input,
        dict: ConversionDictionary,
        ngrams: CjkNgrams,
        opts: Opts = Opts(),
        learned: (reading: String) -> Map<String, Int>? = { null },
    ): List<Cand> {
        val n = input.units
        if (n == 0) return emptyList()
        val edges = buildEdges(input, dict, opts, learned)
        val suffix = suffixScores(edges, dict, ngrams, n)
        val out = LinkedHashMap<String, Cand>()

        // Rank 0: the decoded sentence — but only when every step of it is a real
        // word. A path patched together with raw readings is not a conversion.
        val best = bestPath(edges, dict, ngrams, opts, n)
        if (best.size >= 2 && best.none { it.fallback }) {
            val text = best.joinToString("") { it.text }
            val reading = best.joinToString("") { it.reading }
            out[text] = Cand(text, input.boundaries[n], best.sumOf { it.emission }, reading)
        }

        // Then the readings that cover the buffer with several words, which the
        // unigram model above cannot see (see [Opts.stitch]). Ahead of the first
        // words rather than after them: they cover everything typed, which is
        // what the buffer was abbreviated to ask for.
        //
        // Only when nothing in the dictionary covers it on its own. An
        // abbreviation that reaches a real phrase — `zhg` for 中国, `zgrm` for
        // 中国人民 — has already done its job, and stitched readings there are
        // three slots of 知好国 in front of the answer.
        val wholeBufferWord = edges[0].any { !it.fallback && it.to == n }
        if (opts.stitch > 0 && !wholeBufferWord) {
            for (path in stitchedPaths(edges, dict, ngrams, opts, n)) {
                if (out.size >= opts.limit) break
                val text = path.joinToString("") { it.text }
                out.getOrPut(text) {
                    Cand(text, input.boundaries[n], path.sumOf { it.emission }, path.joinToString("") { it.reading })
                }
            }
        }

        // Then every plausible first word, scored with the rest of the buffer
        // behind it rather than on its own length.
        val firsts = edges[0]
            .filterNot { it.fallback }
            .map { e -> e to ngrams.logProbability(null, e.text, e.freq, dict.totalFreq) + e.emission + suffix[e.to] }
            .sortedByDescending { it.second }
        for ((edge, score) in firsts) {
            if (out.size >= opts.limit) break
            out.getOrPut(edge.text) {
                Cand(edge.text, input.boundaries[edge.to], score, edge.reading)
            }
        }
        return out.values.toList()
    }

    /**
     * Every word that starts at each unit position, plus a raw-reading fallback so
     * a complete path always exists.
     */
    private fun buildEdges(
        input: Input,
        dict: ConversionDictionary,
        opts: Opts,
        learned: (String) -> Map<String, Int>?,
    ): Array<MutableList<Edge>> {
        val n = input.units
        val out = Array(n) { mutableListOf<Edge>() }
        for (i in 0 until n) {
            if (!dict.isEmpty) extend(i, i, "", 0 until dict.size, 0.0, 0, input, dict, opts, out, learned)
            val raw = input.options[i].firstOrNull().orEmpty()
            out[i].add(Edge(i, i + 1, raw, raw, UNK_LOG, 0, fallback = true))
        }
        return out
    }

    /**
     * Walks forward from unit [start], extending the reading one unit at a time
     * and narrowing the candidate rows with it. [syllables] is how many the
     * reading so far spells — the unit count, except where two units merged
     * into one digraph syllable. Recursion depth is bounded by
     * [Opts.maxWordUnits], so it cannot outrun the stack.
     */
    @Suppress("LongParameterList")
    private fun extend(
        start: Int,
        unit: Int,
        prefix: String,
        range: IntRange,
        penalty: Double,
        syllables: Int,
        input: Input,
        dict: ConversionDictionary,
        opts: Opts,
        out: Array<MutableList<Edge>>,
        learned: (String) -> Map<String, Int>?,
    ) {
        if (unit >= input.units || syllables >= opts.maxWordUnits) return
        val options = input.options[unit]
        val ambiguous = unit - start < opts.maxAmbiguousSpan
        val partialFrom = input.partialFrom[unit]
        for (oi in options.indices) {
            // Past the ambiguity cap only the as-typed reading and the
            // abbreviations are tried; a spelling guess is not worth the fan-out.
            if (!ambiguous && oi > 0 && oi < partialFrom) continue
            val reading = prefix + options[oi]
            // The prune that makes per-unit ambiguity affordable: no word starts
            // this way, so nothing longer can either.
            val sub = dict.prefixRange(reading, range)
            if (sub.isEmpty()) continue
            val cost = penalty + input.penalties[unit].getOrElse(oi) { 0.0 }
            emitSpan(start, unit + 1, reading, cost, syllables + 1, dict, opts, out, learned)
            extend(start, unit + 1, reading, sub, cost, syllables + 1, input, dict, opts, out, learned)
        }
        // Two units read as one syllable (`z` + `h` → `zh…`): the edge lands
        // past both, but counts one syllable towards the word-length test.
        if (unit + 1 >= input.units) return
        val merged = input.merged[unit]
        for (mi in merged.indices) {
            val reading = prefix + merged[mi]
            val sub = dict.prefixRange(reading, range)
            if (sub.isEmpty()) continue
            val cost = penalty + input.mergedPenalties[unit].getOrElse(mi) { 0.0 }
            emitSpan(start, unit + 2, reading, cost, syllables + 1, dict, opts, out, learned)
            extend(start, unit + 2, reading, sub, cost, syllables + 1, input, dict, opts, out, learned)
        }
    }

    /** Adds the best [Opts.spanCandCap] words read exactly [reading] over the span. */
    @Suppress("LongParameterList")
    private fun emitSpan(
        start: Int,
        end: Int,
        reading: String,
        penalty: Double,
        syllables: Int,
        dict: ConversionDictionary,
        opts: Opts,
        out: Array<MutableList<Edge>>,
        learned: (String) -> Map<String, Int>?,
    ) {
        val rows = dict.rowsFor(reading)
        if (rows.isEmpty()) return
        val usable = if (opts.charPerUnit) rows.filter { dict.wordLength(it) >= syllables } else rows.toList()
        if (usable.isEmpty()) return
        val cap = maxOf(opts.spanCandCap, opts.limit)
        val kept = usable.sortedByDescending { dict.frequency(it) }.take(cap)
        // Asked once per span, not once per row: this reaches a synchronized
        // store, and a long buffer enumerates thousands of rows across a
        // hundred-odd spans (see [LEARN_WEIGHT]).
        val picks = learned(reading)
        val maxFreq = dict.frequency(kept.first()).coerceAtLeast(1)
        for (row in kept) {
            val freq = dict.frequency(row).coerceAtLeast(1)
            var emission = BETA * (ln(freq.toDouble()) - ln(maxFreq.toDouble())) + penalty
            if (opts.charPerUnit) {
                emission += opts.overLengthPenalty * (dict.wordLength(row) - syllables)
            }
            val word = dict.word(row)
            val chosen = picks?.get(word) ?: 0
            if (chosen > 0) emission += LEARN_WEIGHT * ln(1.0 + chosen)
            out[start].add(Edge(start, end, word, reading, emission, dict.frequency(row)))
        }
    }

    /**
     * Best score from each unit position to the end, scored without context.
     *
     * A deliberate approximation: it drops the bigram conditioning across the
     * boundary between a candidate first word and the remainder. Getting that
     * exactly right needs k-best backtrace over (position, last word) states, for
     * a difference nobody can perceive below rank two.
     */
    private fun suffixScores(
        edges: Array<MutableList<Edge>>,
        dict: ConversionDictionary,
        ngrams: CjkNgrams,
        n: Int,
    ): DoubleArray {
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        best[n] = 0.0
        for (p in n - 1 downTo 0) {
            for (e in edges[p]) {
                val rest = best[e.to]
                if (rest == Double.NEGATIVE_INFINITY) continue
                val score = ngrams.logProbability(null, e.text, e.freq, dict.totalFreq) + e.emission + rest
                if (score > best[p]) best[p] = score
            }
        }
        return best
    }

    /**
     * Whole-buffer paths of two or more words, best first, searched with
     * [Opts.stitchBonus] added per word (see [Opts.stitch]).
     *
     * A second search rather than a k-best read-off of [bestPath], because the
     * two want different things. That one prunes to one path per distinct last
     * word, which is exactly right for finding *the* best reading — only the
     * last word conditions what follows, so anything worse behind the same word
     * can never win. Here it is the opposite: 和的 and 好的 end in the same 的
     * and differ only in the word before it, so that prune keeps one of them and
     * throws the alternative the user was looking for away. This one carries the
     * text so far in the key instead, which is what makes the list diverse.
     */
    private fun stitchedPaths(
        edges: Array<MutableList<Edge>>,
        dict: ConversionDictionary,
        ngrams: CjkNgrams,
        opts: Opts,
        n: Int,
    ): List<List<Edge>> {
        val live = arrayOfNulls<MutableList<Path>>(n + 1)
        live[0] = mutableListOf(Path(null, 0.0, null, null))
        for (p in 0..n) {
            val here = live[p] ?: continue
            val bestByText = LinkedHashMap<String, Path>()
            for (path in here) {
                val key = textOf(path)
                val cur = bestByText[key]
                if (cur == null || path.score > cur.score) bestByText[key] = path
            }
            val pruned = bestByText.values.sortedByDescending { it.score }.take(opts.beam)
            live[p] = pruned.toMutableList()
            if (p == n) break
            for (path in pruned) {
                for (e in edges[p]) {
                    if (e.fallback) continue
                    val lm = ngrams.logProbability(path.word, e.text, e.freq, dict.totalFreq)
                    val next = live[e.to] ?: mutableListOf<Path>().also { live[e.to] = it }
                    next.add(Path(e.text, path.score + lm + e.emission + opts.stitchBonus, path, e))
                }
            }
        }
        val ends = live[n]?.sortedByDescending { it.score } ?: return emptyList()
        val out = ArrayList<List<Edge>>(opts.stitch)
        for (end in ends) {
            val path = pathOf(end)
            if (path.size < 2) continue
            out.add(path)
            if (out.size >= opts.stitch) break
        }
        return out
    }

    /** The text a path has committed so far — the key that keeps the list diverse. */
    private fun textOf(path: Path): String {
        val parts = ArrayList<String>()
        var cur: Path? = path
        while (cur != null) {
            val word = cur.word ?: break
            parts.add(word)
            cur = cur.prev
        }
        if (parts.isEmpty()) return ""
        parts.reverse()
        return parts.joinToString("")
    }

    /** The edges [end] was built from, in order. */
    private fun pathOf(end: Path): List<Edge> {
        val path = ArrayList<Edge>()
        var cur: Path? = end
        while (cur != null) {
            val edge = cur.edge ?: break
            path.add(edge)
            cur = cur.prev
        }
        path.reverse()
        return path
    }

    /** Viterbi over the lattice, carrying the previous word so bigrams can apply. */
    private fun bestPath(
        edges: Array<MutableList<Edge>>,
        dict: ConversionDictionary,
        ngrams: CjkNgrams,
        opts: Opts,
        n: Int,
    ): List<Edge> {
        val live = arrayOfNulls<MutableList<Path>>(n + 1)
        live[0] = mutableListOf(Path(null, 0.0, null, null))
        for (p in 0..n) {
            val here = live[p] ?: continue
            // One path per distinct previous word — anything worse with the same
            // word can never win, since only the word conditions what follows.
            val bestByWord = LinkedHashMap<String?, Path>()
            for (path in here) {
                val cur = bestByWord[path.word]
                if (cur == null || path.score > cur.score) bestByWord[path.word] = path
            }
            val pruned = bestByWord.values.sortedByDescending { it.score }.take(opts.beam)
            live[p] = pruned.toMutableList()
            if (p == n) break
            for (path in pruned) {
                for (e in edges[p]) {
                    val lm = ngrams.logProbability(path.word, e.text, e.freq, dict.totalFreq)
                    val next = live[e.to] ?: mutableListOf<Path>().also { live[e.to] = it }
                    next.add(Path(e.text, path.score + lm + e.emission, path, e))
                }
            }
        }
        val end = live[n]?.maxByOrNull { it.score } ?: return emptyList()
        return pathOf(end)
    }
}
