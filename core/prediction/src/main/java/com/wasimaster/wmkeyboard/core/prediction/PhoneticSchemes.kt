package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.AvroPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhonetic
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticIndex

/**
 * Everything the prediction side needs to know about one phonetic input
 * method: a Latin grid whose text is another script, ranked against that
 * script's dictionary (Avro for Bengali).
 *
 * The composer says *which* scheme a layout types through
 * (`Composer.phoneticLanguage`); this says what that means — the rules, the
 * lenient index over the word list, and the curated spellings that outrank
 * both. Adding a language is one more entry here, not another branch in the
 * engine or the service.
 */
class PhoneticScheme(
    /** `LanguageDef.id` of the language typed, and of the word list it is ranked against. */
    val languageId: String,
    /** Id of the downloadable romanized word list a glide is decoded against. */
    val romanizedListId: String,
    /**
     * `spelling<TAB>native` assets under `assets/`, most trusted first — where
     * two disagree the earlier one leads ([SpellingMap.load]).
     */
    val spellingAssets: List<String>,
    /** The rules alone: one romanized word (or free text) in native script. */
    val transliterate: (String) -> String,
    /**
     * Every reading the rules think plausible, the literal one first. More than
     * one only where the rules are genuinely undecided and there may be no
     * dictionary to settle it.
     */
    val variants: (String) -> List<String>,
    /** The lenient index over `word to frequency` entries of the language's list. */
    val buildIndex: (List<Pair<String, Int>>) -> PhoneticIndex,
)

/** The phonetic schemes that ship, by language. */
object PhoneticSchemes {

    val BENGALI = PhoneticScheme(
        languageId = "bn",
        romanizedListId = "bn_rom",
        spellingAssets = listOf("dictionaries/en_bn.tsv", "dictionaries/bn_rom.tsv"),
        transliterate = AvroPhonetic::transliterate,
        variants = { listOf(AvroPhonetic.transliterate(it)) },
        buildIndex = ::BengaliPhoneticIndex,
    )

    /**
     * Hindi has no bundled word list, so its index is built over whatever the
     * user downloaded or imported — and is empty until they have. That is why
     * its [PhoneticScheme.variants] carry more than one reading, and why its
     * spelling map is a vocabulary rather than a list of exceptions.
     */
    val HINDI = PhoneticScheme(
        languageId = "hi",
        romanizedListId = "hi_rom",
        spellingAssets = listOf("dictionaries/en_hi.tsv", "dictionaries/hi_rom.tsv"),
        transliterate = HindiPhonetic::transliterate,
        variants = HindiPhonetic::variants,
        buildIndex = ::HindiPhoneticIndex,
    )

    val all: List<PhoneticScheme> = listOf(BENGALI, HINDI)

    fun forLanguage(languageId: String?): PhoneticScheme? =
        all.firstOrNull { it.languageId == languageId }
}

/**
 * One scheme with its data loaded: what the engine consults for a composing
 * buffer typed on that scheme's layout. [index] is a var because a word list
 * can arrive (a download, an import) under a running engine.
 */
class PhoneticBackend(
    val scheme: PhoneticScheme,
    @Volatile var index: PhoneticIndex,
    val spellings: SpellingMap = SpellingMap.EMPTY,
)
