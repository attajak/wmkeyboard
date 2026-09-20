package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.ln

/**
 * Chinese Pinyin input. The roman buffer is toneless pinyin (or Double Pinyin
 * key codes), shown in the composing region, and the strip offers Hanzi/word
 * candidates from the pinyin→Hanzi pack ([isConversion] + [candidates]).
 *
 * The buffer is split into syllables so a multi-syllable reading like `nihao`
 * offers both the whole-phrase 你好 and the leading-syllable 你/尼/…, each
 * remembering how many *input* chars it consumed ([consumedFor]) so the service
 * commits that prefix and re-converts the tail. Two [CjkConfig] knobs feed in at
 * call time: Double Pinyin ([DoublePinyin]) changes how the buffer segments, and
 * Fuzzy Pinyin ([PinyinFuzzy]) widens each syllable's lookup. With no dictionary
 * match the raw pinyin commits, so the buffer never traps the user.
 */
object PinyinComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingPinyin

    /** Reading space for learned picks: pinyin letters, whatever scheme typed them. */
    private const val NAMESPACE = "pinyin"

    private const val LIMIT = 12

    /**
     * How deep [consumedFor] ranks. The expanded grid shows far more than the
     * strip, and a candidate the user can tap but the ranking cannot find would
     * fall through to "consumed the whole buffer" and swallow their text.
     */
    private const val LOOKUP_LIMIT = 128

    /** Fuzzy variants offered per syllable, beyond the one actually typed. */
    private const val FUZZY_VARIANTS = 2

    /**
     * What a fuzzy syllable costs, per syllable that differs from what was typed.
     * An exact match therefore loses only to a fuzzy word about seven times more
     * probable, and a one-syllable slip outranks a three-syllable one.
     */
    private val FUZZY_PENALTY = ln(0.15)

    /**
     * Buffer length, in syllables, past which the lattice gives way to plain
     * prefix lookup. Nobody types two dozen syllables without committing, and the
     * bound is what keeps a pathological buffer off the frame budget.
     */
    private const val MAX_LATTICE_UNITS = 24

    /** The composing region shows the pinyin — translated from key codes in Double Pinyin. */
    override fun composeBuffer(buffer: String): String {
        val table = doublePinyinTable()
        return if (table != null) DoublePinyin.translate(buffer.lowercase(), table, PinyinSyllables.valid)
        else buffer.lowercase()
    }

    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer.lowercase()).take(limit).map { it.text }

    override fun consumedFor(buffer: String, chosen: String): Int {
        val b = buffer.lowercase()
        return ranked(b).firstOrNull { it.text == chosen }?.consumed ?: b.length
    }

    override fun consumedForIndex(buffer: String, index: Int): Int {
        val b = buffer.lowercase()
        return ranked(b).getOrNull(index)?.consumed ?: b.length
    }

    override fun learnChoice(buffer: String, index: Int) {
        val cand = ranked(buffer.lowercase()).getOrNull(index) ?: return
        CjkLearning.learn(NAMESPACE, cand.reading, cand.text)
    }


    private fun doublePinyinTable(): DoublePinyin.Table? {
        val scheme = CjkConfig.doublePinyin
        return if (scheme == DoublePinyinScheme.OFF) null else DoublePinyin.tableFor(scheme)
    }

    /** Syllables of [buffer] with per-syllable input spans, in the active input mode. */
    private fun segments(buffer: String): List<Seg> {
        val table = doublePinyinTable()
        return if (table != null) DoublePinyin.segments(buffer, table, PinyinSyllables.valid)
        else PinyinSyllables.segment(buffer)
    }

    /**
     * The lattice input for [segs]: one unit per syllable, each offering the
     * syllable as typed and — with Fuzzy Pinyin on — a couple of confusable
     * spellings behind a penalty.
     *
     * The fuzzy variants are *options*, not readings. Building the cartesian
     * product of readings was what forced a cap, and the cap dropped real
     * candidates; the decoder instead prunes each variant against the dictionary
     * before extending it, so an impossible spelling costs one binary search.
     */
    private fun latticeInput(segs: List<Seg>): Lattice.Input {
        val n = segs.size
        val boundaries = IntArray(n + 1)
        for (i in 0 until n) boundaries[i + 1] = boundaries[i] + segs[i].inputLen
        val fuzzy = CjkConfig.fuzzyPinyin
        val fuzzyPairs = CjkConfig.fuzzyPinyinPairs
        val options = Array(n) { i ->
            val typed = segs[i].syllable
            if (!fuzzy) arrayOf(typed)
            else {
                val variants = PinyinFuzzy.expand(typed, PinyinSyllables.valid, fuzzyPairs)
                    .filter { it != typed }
                    .take(FUZZY_VARIANTS)
                (listOf(typed) + variants).toTypedArray()
            }
        }
        val penalties = Array(n) { i ->
            DoubleArray(options[i].size) { o -> if (o == 0) 0.0 else FUZZY_PENALTY }
        }
        return Lattice.Input(boundaries, options, penalties)
    }

    /**
     * Candidates for [buffer], best first, each tagged with its consumed input
     * length. Falls back to the dictionary's own prefix matching when the buffer
     * has no segmentable syllable yet (a still-typing first syllable).
     *
     * Cached on the buffer, because the service ranks the same one twice per
     * commit — once to fill the strip and once to ask how much to delete.
     */
    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val dict = CjkDictionaries.pinyin
        val segs = segments(buffer)
        if (segs.isEmpty() || segs.size > MAX_LATTICE_UNITS) {
            // Nothing segments yet, or the buffer is longer than anyone types
            // before committing: fall back to plain prefix lookup, which cannot
            // offer a prefix commit but always answers.
            return dict.candidates(buffer, LOOKUP_LIMIT)
                .map { Cand(HanVariant.toTraditional(it), buffer.length, buffer) }
        }
        val decoded = Lattice.decode(latticeInput(segs), dict, CjkDictionaries.ngrams, Lattice.Opts(limit = LOOKUP_LIMIT))
            .map { Cand(HanVariant.toTraditional(it.text), it.consumed, it.reading) }
            // Converting to Traditional can merge two Simplified words onto one
            // form, so de-duplicate after the conversion rather than before it.
            .distinctBy { it.text }
        return CjkLearning.rank(NAMESPACE, decoded, { it.text }, { it.reading })
    }

    /** A candidate word, the input chars (keys) it covers, and the reading behind it. */
    private data class Cand(val text: String, val consumed: Int, val reading: String)

    private val cache = RankCache<List<Cand>>()
}
