package com.wasimaster.wmkeyboard.core.prediction

/** Left context handed to a [CandidateReranker]. */
class RerankContext(
    val composing: String,
    val previousWord: String?,
    val recentWords: List<String>,
    /** The word before [previousWord], null when unknown or across a
     * sentence boundary — same contract as the engine's trigram context. */
    val previousWord2: String? = null,
    /** The word before [previousWord2], under the same contract, for the
     * distance-3 skip-gram (#195). */
    val previousWord3: String? = null,
)

/**
 * Hook for a smarter model (a class-based n-gram, a tiny LM) to reorder the
 * engine's top candidates using left context the frequency ranking can't see.
 *
 * Contract: [rerank] returns a reordering of (a subset of) [candidates], or
 * null to pass — on timeout, low confidence, or no opinion — in which case
 * the engine keeps its own order. Implementations own their latency budget:
 * the engine never waits beyond the call itself, so an impl that can block
 * must self-time and return null past its deadline. Never called on the
 * synchronous main-thread paths (the engine's caller opts in per call).
 *
 * Wired only when a real model lands (full-flavor, downloadable); until then
 * [NONE] keeps this a seam, not a feature.
 */
fun interface CandidateReranker {

    fun rerank(context: RerankContext, candidates: List<String>): List<String>?

    companion object {
        val NONE = CandidateReranker { _, _ -> null }
    }
}
