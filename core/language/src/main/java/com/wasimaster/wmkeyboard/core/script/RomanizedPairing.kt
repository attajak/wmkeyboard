package com.wasimaster.wmkeyboard.core.script

/**
 * Whether this language is a romanized variant of a native-script language —
 * Banglish, Arabizi and the rest of the `*_rom` family, which type on a Latin
 * grid with suggestions from a transliterated word list.
 *
 * The `_rom` id suffix is the declared convention for these entries (see the
 * "Romanized variants" block in [LanguageRegistry]); the locale tag is *not*
 * a usable marker, because languages like Crimean Tatar carry `-Latn` as
 * their genuine native orthography and are not romanizations of anything.
 */
val LanguageDef.isRomanized: Boolean get() = id.endsWith("_rom")

/**
 * Cross-wires romanized languages with the other languages typed on the same
 * script, so adding Banglish next to English makes each keyboard suggest —
 * and refuse to autocorrect away — the other's words without a trip through
 * the language detail screen.
 *
 * People who type a romanized language rarely switch layouts for it: the
 * whole point is that it lives on the same QWERTY as their English. Left
 * unpaired, the English keyboard treats every Banglish word as a typo to fix;
 * paired both ways, the suggestion engine sees both vocabularies wherever the
 * user happens to be typing. Only pairs involving a romanized language are
 * wired automatically — plain multilinguals (English + French) usually keep
 * their languages on separate keyboards, and their manual toggles remain.
 */
object RomanizedPairing {

    /** The updated map plus which links were newly added ((primary, secondary) ids). */
    data class Result(
        val secondaries: Map<String, List<String>>,
        val added: List<Pair<String, String>>,
    ) {
        /**
         * The added links as unordered display pairs, each once — "Banglish ↔
         * English" is one fact even though it took two map entries.
         */
        val addedPairs: List<Pair<String, String>>
            get() {
                val seen = LinkedHashSet<Set<String>>()
                for ((a, b) in added) seen.add(setOf(a, b))
                return seen.map { pair -> pair.first() to pair.last() }
            }
    }

    /**
     * Ensures every (romanized, same-script) pair among [enabled] languages
     * suggests from each other, in both directions. Existing entries are only
     * ever added to.
     *
     * [involving] limits the pass to pairs with at least one side in it: the
     * language that was just added. Without it, adding French re-ran every
     * pair and brought back the English and Banglish link the user had
     * removed by hand. Null wires every pair, for the one-shot upgrade.
     */
    fun autoPair(
        enabled: List<LanguageDef>,
        secondaries: Map<String, List<String>>,
        involving: Set<String>? = null,
    ): Result {
        val languages = enabled.distinctBy { it.id }
        val updated = HashMap(secondaries)
        val added = ArrayList<Pair<String, String>>()
        fun link(primary: String, secondary: String) {
            val current = updated[primary].orEmpty()
            if (secondary in current) return
            updated[primary] = current + secondary
            added.add(primary to secondary)
        }
        for (romanized in languages) {
            if (!romanized.isRomanized) continue
            for (other in languages) {
                if (other.id == romanized.id || other.script != romanized.script) continue
                if (involving != null && romanized.id !in involving && other.id !in involving) continue
                link(romanized.id, other.id)
                link(other.id, romanized.id)
            }
        }
        return Result(updated, added)
    }
}
