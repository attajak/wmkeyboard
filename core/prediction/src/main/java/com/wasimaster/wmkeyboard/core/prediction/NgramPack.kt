package com.wasimaster.wmkeyboard.core.prediction

/**
 * The corpus n-grams for one language: bigram and trigram counts, read straight
 * out of memory-mapped [MappedNgramPack]s.
 *
 * Usually one pack, the download. The word pairs of an imported dictionary
 * (a HeliBoard `.dict`, a user history) ride along as packs of their own, one
 * per list, so switching a list off or deleting it takes its pairs with it and
 * no pack is ever rewritten. Several packs read as one: a count is the
 * strongest any of them holds, never a sum, because the same pair in two
 * sources is the same evidence seen twice, not twice the evidence.
 *
 * Each pack carries its own vocabulary and its own id space, so nothing outside
 * this class ever learns that followers are stored as numbers. Callers pass
 * words and get words back.
 *
 * It is also where a word is put into the spelling the pack files it under —
 * see [WordKey], and note that a caller handing over the other spelling of a
 * word gets a silent miss, not an error, which is why this is the only door in.
 *
 * Corpus counts complement, never replace, the personal stores: the user's own
 * n-grams are the stronger evidence everywhere both exist.
 */
class NgramPack private constructor(private val packs: List<MappedNgramPack>) {

    /** No pack installed. Not "no trigrams" — a bigram-only pack is a real pack. */
    val isEmpty: Boolean get() = packs.isEmpty()

    fun bigramCount(previous: String, next: String): Int {
        if (packs.isEmpty()) return 0
        val p = WordKey.of(previous)
        val n = WordKey.of(next)
        return packs.maxOf { it.bigramCount(p, n) }
    }

    fun trigramCount(prev2: String, prev1: String, next: String): Int {
        if (packs.isEmpty()) return 0
        val a = WordKey.of(prev2)
        val b = WordKey.of(prev1)
        val n = WordKey.of(next)
        return packs.maxOf { it.trigramCount(a, b, n) }
    }

    /** Corpus followers of [previous], best first. */
    fun nextWords(previous: String, limit: Int): List<String> {
        val p = WordKey.of(previous)
        return merged(limit, { it.nextWords(p, limit) }) { pack, word -> pack.bigramCount(p, word) }
    }

    /** Corpus followers of the two-word context, best first. */
    fun nextWordsAfter(prev2: String, prev1: String, limit: Int): List<String> {
        val a = WordKey.of(prev2)
        val b = WordKey.of(prev1)
        return merged(limit, { it.nextWordsAfter(a, b, limit) }) { pack, word -> pack.trigramCount(a, b, word) }
    }

    /**
     * Each pack's own best [limit], ranked together by the strongest count any
     * pack gives them. Every pack's top list is complete for its own pack, so
     * the merged top [limit] can only come from their union.
     */
    private inline fun merged(
        limit: Int,
        best: (MappedNgramPack) -> List<String>,
        crossinline count: (MappedNgramPack, String) -> Int,
    ): List<String> {
        if (packs.isEmpty()) return emptyList()
        if (packs.size == 1) return best(packs[0])
        val candidates = LinkedHashSet<String>()
        for (pack in packs) candidates.addAll(best(pack))
        return candidates
            .map { word -> word to packs.maxOf { count(it, word) } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    companion object {
        val EMPTY = NgramPack(emptyList())

        fun of(mapped: MappedNgramPack?): NgramPack =
            if (mapped == null) EMPTY else NgramPack(listOf(mapped))

        /** The packs that opened, read as one; [EMPTY] when none did. */
        fun of(mapped: List<MappedNgramPack?>): NgramPack {
            val opened = mapped.filterNotNull()
            return if (opened.isEmpty()) EMPTY else NgramPack(opened)
        }
    }
}
