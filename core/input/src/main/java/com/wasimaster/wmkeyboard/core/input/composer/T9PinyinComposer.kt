package com.wasimaster.wmkeyboard.core.input.composer

/**
 * T9 / 九宫格 pinyin: Chinese on a 9-key phone pad. The buffer is digits, each
 * standing for three or four pinyin letters, and the strip offers Hanzi from the
 * same pinyin pack full-keyboard pinyin uses — no separate dictionary.
 *
 * Structurally this is [PinyinComposer] with one extra degree of freedom. There,
 * a segment resolves to exactly one syllable; here a segment resolves to a digit
 * *code* that several syllables share ([T9Pinyin.index]), so each reading is a
 * capped cartesian product over the segments' alternatives — the same widening
 * [PinyinComposer] already does for Fuzzy Pinyin, applied to keypad ambiguity
 * instead of confusable spellings.
 *
 * Because the input alphabet (digits) *is* the segmentation alphabet, consumed
 * lengths need no translation back to keystrokes — unlike [JapaneseComposer],
 * which segments kana but must report romaji. [consumedFor] counts digits
 * directly, so prefix commit works unchanged.
 */
object T9PinyinComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingPinyin

    /** The whole input alphabet is digits, and they must be able to *start* a buffer. */
    override val bufferDigits: Boolean get() = true
    override val digitsStartBuffer: Boolean get() = true

    /** Reading space for learned picks: digit codes, not pinyin letters. */
    private const val NAMESPACE = "pinyin_t9"

    private const val LIMIT = 12

    /** Ranked depth for resolving a tap, so the expanded grid can be tapped too. */
    private const val LOOKUP_LIMIT = 128

    /**
     * The composing region shows the pinyin of the best candidate rather than the
     * raw digits: `426` reads back as `hao`, which is what the user is spelling.
     * Falls back to the digits themselves when nothing segments yet.
     */
    override fun composeBuffer(buffer: String): String =
        ranked(buffer).firstOrNull()?.reading ?: buffer

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer).take(limit).map { it.text }

    override fun consumedFor(buffer: String, chosen: String): Int =
        ranked(buffer).firstOrNull { it.text == chosen }?.consumed ?: buffer.length

    override fun consumedForIndex(buffer: String, index: Int): Int =
        ranked(buffer).getOrNull(index)?.consumed ?: buffer.length

    override fun learnChoice(buffer: String, index: Int) {
        val cand = ranked(buffer).getOrNull(index) ?: return
        CjkLearning.learn(NAMESPACE, cand.reading, cand.text)
    }


    /** A candidate, the digits it covers, and the reading that produced it. */
    private data class Cand(val text: String, val consumed: Int, val reading: String)

    /**
     * Splits the digit buffer against the digit codes of real syllables. Reuses
     * [PinyinSyllables.segment] verbatim, because segmenting digit codes and
     * segmenting syllables are the same problem over a different alphabet. Each
     * returned [Seg.syllable] is therefore a digit code, not a syllable.
     *
     * The backtracking search matters more here than anywhere: 412 syllables
     * collapse onto ~230 codes over an eight-symbol alphabet, so ambiguous splits
     * are far commoner among digits than among letters.
     */
    private fun segments(buffer: String): List<Seg> =
        PinyinSyllables.segment(buffer, T9Pinyin.index.keys)

    /**
     * Candidates for [buffer], longest reading first so a whole-phrase entry
     * outranks its leading syllable, each tagged with the number of *digits* it
     * consumed. Mirrors [PinyinComposer.ranked]; with no segmentable code (a
     * digit run that spells no syllable) it yields nothing and the raw digits
     * commit, so the buffer never traps the user.
     */
    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val segs = segments(buffer)
        if (segs.isEmpty()) return emptyList()
        // Each digit code stands for several syllables, and that ambiguity goes
        // to the decoder as options rather than being multiplied out into
        // readings. The old cartesian product needed a cap to stay bounded, and
        // the cap cut alphabetically — for 726726726 every surviving reading
        // began `pan`, so 三三三 was unreachable. Here an option no word starts
        // with is pruned by one binary search and costs nothing.
        val options = segs.map { T9Pinyin.index[it.syllable]?.toTypedArray() ?: return emptyList() }
        val input = Lattice.options(options, segs.map { it.inputLen })
        return Lattice.decode(
            input,
            CjkDictionaries.pinyin,
            CjkDictionaries.ngrams,
            // Every digit is ambiguous, so the ambiguity cap has to come off or
            // later syllables get pinned to whichever one sorted first — the
            // alphabetical bias this whole change removes.
            Lattice.Opts(limit = LOOKUP_LIMIT, maxAmbiguousSpan = Int.MAX_VALUE),
        )
            .map { Cand(HanVariant.toTraditional(it.text), it.consumed, it.reading) }
            .distinctBy { it.text }
            .let { CjkLearning.rank(NAMESPACE, it, { c -> c.text }, { c -> c.reading }) }
    }

    private val cache = RankCache<List<Cand>>()
}
