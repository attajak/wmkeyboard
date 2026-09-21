package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.TrieCompleter
import com.wasimaster.wmkeyboard.core.prediction.TrieWalker

/**
 * Whether a layout can actually draw the language being typed on it.
 *
 * A glide decoder refuses any word holding a character its grid cannot produce,
 * so a layout that covers a tenth of a language does not fail loudly — it
 * quietly decodes strokes against the tenth it can reach and commits confident
 * nonsense. The old guard against this was `('a'..'z').all { … }`, which is the
 * English question wearing a general name: it passes Avro's Latin grid for
 * Bengali (whose words share no character with it) and fails a perfectly
 * glidable Greek layout.
 *
 * The general question is empirical, so this measures it: take the language's
 * most common words and count how many the grid can draw end to end. Common
 * words rather than all of them, because the tail of any word list is full of
 * loanwords and proper nouns in other scripts, and a keyboard that handles
 * everything people actually type is doing its job.
 *
 * ### Who is asked
 *
 * The answer is only as good as the lists that stand for the language, so the
 * caller (`SuggestionEngine.glideCoverage`) hands over the first of these that
 * holds any words, and nothing after it:
 *
 * 1. the romanization, on a layout that types one (Avro: the question is
 *    whether the Latin grid spells the romanized vocabulary, never the Bengali
 *    list);
 * 2. the language's own lists, bundled or downloaded;
 * 3. the lists the user imported for it, when it has none of its own or is set
 *    to read its imports alone (#28);
 * 4. its secondary languages' lists, so a language with nothing at all can
 *    still earn the readiness the missing-list chip is offered on (#219).
 *
 * Each step down exists because the one above it was letting the wrong list
 * vote. A secondary's words are not the language's (#272), and neither is an
 * import while the language has a list of its own (#288): a user may keep
 * English names under Arabic, and an older version copied compiled
 * dictionaries in unread, where they came back as a hundred thousand words of
 * noise. Either sat beside a list the grid spells 99.7 % of, pooled to a half,
 * and switched glide off without a word. The rule is symmetric, and has to be:
 * a sparse layout still fails however well it spells an import, and a few
 * imported Latin words do not switch glide on for a grid that cannot draw the
 * language. What an import's words cost when the grid cannot draw them is that
 * those words are not decoded, and nothing else.
 */
object GlideCoverage {

    /**
     * Share of the [sample] most frequent words across [walkers] that [alphabet]
     * can spell. 0 when there are no words to measure — an unknown language is
     * not a covered one.
     *
     * The alphabet rather than a laid-out [GlideKeyMap], because coverage is a
     * question about which characters the layout holds and not about where they
     * sit: asking it this way lets the answer be computed when the language or
     * layout changes, rather than waiting for the renderer to measure a grid.
     */
    fun measure(
        walkers: List<TrieWalker>,
        alphabet: Set<Int>,
        sample: Int = SAMPLE_WORDS,
    ): Float {
        if (walkers.isEmpty() || alphabet.isEmpty()) return 0f
        var seen = 0
        var drawable = 0
        // Per walker rather than pooled: each source is sampled at its own top,
        // so a small list cannot swamp a big one's verdict and a big one
        // cannot hide behind it. Every walker therefore votes with a full
        // sample, which is why the caller has to be careful whom it sends
        // (see "Who is asked" above).
        for (walker in walkers) {
            for (suggestion in TrieCompleter.top(walker, sample)) {
                val word = suggestion.word
                if (word.codePointCount(0, word.length) < MIN_WORD) continue
                seen++
                if (spells(word, alphabet)) drawable++
            }
        }
        return if (seen == 0) 0f else drawable.toFloat() / seen
    }

    /**
     * Whether every letter of [word] is in [alphabet], letter by letter rather
     * than `Char` by `Char`: a word in a script outside the BMP is surrogate
     * pairs, and no alphabet holds half a letter.
     */
    fun spells(word: String, alphabet: Set<Int>): Boolean {
        var at = 0
        while (at < word.length) {
            val codePoint = word.codePointAt(at)
            if (Character.toLowerCase(codePoint) !in alphabet) return false
            at += Character.charCount(codePoint)
        }
        return true
    }

    /**
     * True when [alphabet] covers enough of the language to decode honestly.
     *
     * The threshold is deliberately short of 1.0. Real word lists carry a few
     * per cent of entries no sane layout has keys for — hyphenated forms,
     * abbreviations with full stops, the odd Latin loanword in a non-Latin list
     * — and demanding perfection would switch glide off for languages it serves
     * perfectly well.
     */
    fun sufficient(
        walkers: List<TrieWalker>,
        alphabet: Set<Int>,
        sample: Int = SAMPLE_WORDS,
    ): Boolean = measure(walkers, alphabet, sample) >= THRESHOLD

    /**
     * Words sampled per source. Large enough that the answer is about the
     * language rather than about a handful of words, small enough that the
     * branch-and-bound walk behind it stays a few milliseconds — it runs when
     * the layout or language changes, not per stroke.
     */
    const val SAMPLE_WORDS = 1500

    /** Share of sampled words the grid must be able to draw. */
    const val THRESHOLD = 0.90f

    /** Two-character words are tapped, not swiped, and skew a small sample. */
    private const val MIN_WORD = 3
}
