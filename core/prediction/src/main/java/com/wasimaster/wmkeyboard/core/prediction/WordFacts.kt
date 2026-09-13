package com.wasimaster.wmkeyboard.core.prediction

/**
 * Everything the engine knows about where one word comes from, for the word
 * card the suggestion strip opens on a held chip (#99). Built by
 * [SuggestionEngine.describe]; the service adds what the engine cannot see
 * (the waiting room, the names of the lists) before it is drawn.
 *
 * A description, not a score: the card explains the word's standing in
 * words the user can act on — which list it is in, how often they typed it,
 * whether they pinned its spelling — and never the log-space arithmetic.
 */
data class WordFacts(
    /** The word as every store keys it. */
    val key: String,
    /** The on-screen language's own list, when it has the word. */
    val primary: PackFact? = null,
    /** Secondary-language lists that have it, including English-as-secondary. */
    val secondary: List<PackFact> = emptyList(),
    /** Frequency in an imported list for this language, 0 when absent. */
    val customFrequency: Int = 0,
    /**
     * In a word list the user imported and Delete can edit (#190), as opposed
     * to a downloaded one. Filled in by the service, which is what keeps the
     * two apart; the engine sees them as one source.
     */
    val importedList: Boolean = false,
    /** In Android's personal dictionary. */
    val system: Boolean = false,
    /** In the keyboard's own personal dictionary. */
    val learned: LearnedFact? = null,
    /** Settled sightings in the waiting room; filled in by the service. */
    val pendingSightings: Int = 0,
    /** A contact's name. */
    val contact: Boolean = false,
    /** An installed app's label. */
    val app: Boolean = false,
    /** On the never-suggest list. */
    val blacklisted: Boolean = false,
    /** The user's rank adjustment, 0 when none ([WordRanks]). */
    val rankOffset: Int = 0,
    /** Shapes the user's kept glides taught for the word, across layouts; filled in by the service. */
    val swipeShapes: Int = 0,
) {
    /** True when nothing at all knows the word. */
    val unknown: Boolean
        get() = primary == null && secondary.isEmpty() && customFrequency <= 0 &&
            !system && learned == null && !contact && !app
}

/**
 * The word's standing in one frequency list.
 *
 * [rank] is its 1-based place in the list's most-frequent-first order and
 * [vocabularySize] the number of words in that order, so the card can say
 * "#4,512 of 300,000". Both 0 when the list cannot rank (an in-memory trie
 * built from a flat list).
 */
data class PackFact(
    val langId: String,
    val frequency: Int,
    val rank: Int,
    val vocabularySize: Int,
)

/**
 * The word's personal-dictionary entry: how often it was typed, the language
 * it was last typed under (null when untagged), the capitals it is written
 * with (null when written in lower case) and whether that spelling is pinned
 * against the case vote (#100).
 */
data class LearnedFact(
    val count: Int,
    val langId: String?,
    val display: String?,
    val casePinned: Boolean,
)
