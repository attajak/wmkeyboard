package com.wasimaster.wmkeyboard.core.prediction

/**
 * The endings the strip offers after a whole Klingon word.
 *
 * Klingon builds its words by stacking suffixes on a root (`jatlh` "speak",
 * `jatlhpu'` "has spoken", `jatlhbe'` "does not speak"), and a word list can
 * hold only the combinations somebody happened to write. So once what is
 * typed is a word, its likeliest endings are offered as whole words, `QaH`
 * → `QaHpu'`, whether the list has them or not.
 *
 * A root's part of speech is read off the list itself: the more of a kind's
 * endings it already carries there, the more that kind it is. A root the list
 * says nothing about gets both kinds, commonest first. The orders are by how
 * often each ending appears on a known root in the Klingon list.
 */
object KlingonSuffixes {

    /** The language id these endings belong to. */
    const val LANGUAGE = "tlh"

    /** Endings of nouns: my, at, topic, plural, for, my (beings), your, from, little. */
    private val NOUN = listOf("wI'", "Daq", "'e'", "mey", "vaD", "wIj", "lIj", "vo'", "Hom")

    /** Endings of verbs: done, not, which, in order to, -ing, un-, when, can, cause. */
    private val VERB = listOf("pu'", "be'", "bogh", "meH", "taH", "Ha'", "DI'", "laH", "moH")

    /** Both kinds interleaved, for a root of no known kind. */
    private val EITHER = listOf("wI'", "pu'", "Daq", "be'", "'e'", "mey", "bogh", "meH", "taH")

    /**
     * Up to [limit] of [root]'s suffixed forms, likeliest first. [known] asks
     * whether the word list has a form, so the root's kind can be read off it.
     */
    fun offers(root: String, limit: Int, known: (String) -> Boolean): List<String> {
        if (root.length < MIN_ROOT || root.any { it.isWhitespace() }) return emptyList()
        val nouny = NOUN.count { known(root + it) }
        val verby = VERB.count { known(root + it) }
        val order = when {
            nouny > verby -> NOUN
            verby > nouny -> VERB
            else -> EITHER
        }
        return order.asSequence()
            // An ending the root already ends in is not stacked twice.
            .filterNot { root.endsWith(it) }
            .map { root + it }
            .take(limit)
            .toList()
    }

    /** Shorter than this is a particle or a typo in progress, not a root. */
    private const val MIN_ROOT = 2
}
