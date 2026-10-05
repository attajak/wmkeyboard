package com.wasimaster.wmkeyboard.core.dictionaries

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * One downloadable n-gram pack: corpus bigram/trigram counts for a language,
 * hosted in the data repo next to its wordlists. Files are gzip text, one
 * `word... count` line each, count-descending — the same shape as every
 * other list the repo ships.
 */
class NgramPackEntry(
    /** Language id the pack serves ([com.wasimaster.wmkeyboard.core.language] registry). */
    val languageId: String,
    /** Directory under `data/` in the repo (languages share one, e.g. `bn`). */
    val repoCode: String,
    /** File stem, e.g. `bn_rom_bigrams` -> `data/bn/bn_rom_bigrams.txt.gz`. */
    val bigramStem: String,
    val trigramStem: String,
    /**
     * Roughly what the two files cost to fetch, compressed, for the prompt
     * that asks before downloading them.
     *
     * The *transferred* share, not the published size: the lists are
     * count-descending and the read stops at
     * [NgramPackDownloadManager]'s caps, so English sends about a quarter of
     * its bigram file and an eighth of its trigram one. A pack whose lists are
     * shorter than the caps is simply its full size.
     */
    val approxGzBytes: Long,
) {
    fun bigramUrl(): String = url(bigramStem)
    fun trigramUrl(): String = url(trigramStem)

    /** The two files' names in the data repo, and in a language pack. */
    val bigramFileName: String get() = "$bigramStem.txt.gz"
    val trigramFileName: String get() = "$trigramStem.txt.gz"

    /** The data repository, wherever [ServiceRepo.DATA] points. */
    private fun url(stem: String): String =
        ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl("data/$repoCode/$stem.txt.gz")
}

/**
 * Every language with a published n-gram pack: English, Bengali and romanized
 * Bengali, then the 48 languages the data repo builds from Leipzig corpora.
 * A language id that is not here has no pack to offer.
 */
object NgramPackCatalog {

    val entries: List<NgramPackEntry> = listOf(
        NgramPackEntry(
            languageId = "en",
            repoCode = "en",
            bigramStem = "en_bigrams",
            trigramStem = "en_trigrams",
            approxGzBytes = 1_100_000,
        ),
        // Bengali script, for all three of its layouts. The words are spelled
        // the way `bn_full` spells them, which is NFC — and NFC leaves a nukta
        // decomposed (য + U+09BC), because U+09DF and its two siblings are
        // Unicode composition exclusions. Probhat and Jatiya type it that way
        // too. Avro does not: it commits the precomposed U+09DF, so a word it
        // transliterated will miss these pairs until the context word is
        // normalized on the way in.
        NgramPackEntry(
            languageId = "bn",
            repoCode = "bn",
            bigramStem = "bn_bigrams",
            trigramStem = "bn_trigrams",
            approxGzBytes = 1_820_000,
        ),
        // Banglish: bn_rom is its own first-class language (Latin script,
        // plain QWERTY, transliterated wordlist), so its romanized pairs
        // match that language's typing directly — the ordinary pipeline,
        // nothing Avro-specific. The files live under the repo's bn/ dir.
        NgramPackEntry(
            languageId = "bn_rom",
            repoCode = "bn",
            bigramStem = "bn_rom_bigrams",
            trigramStem = "bn_rom_trigrams",
            approxGzBytes = 315_000,
        ),
        // Everything below is built the same way from the Leipzig Corpora
        // Collection (data/README.md, "N-gram Lists") and already stops at
        // the read caps, so the published size is the transferred size.
        NgramPackEntry(
            languageId = "hi",
            repoCode = "hi",
            bigramStem = "hi_bigrams",
            trigramStem = "hi_trigrams",
            approxGzBytes = 1_522_473,
        ),
        NgramPackEntry(
            languageId = "ur",
            repoCode = "ur",
            bigramStem = "ur_bigrams",
            trigramStem = "ur_trigrams",
            approxGzBytes = 1_400_797,
        ),
        NgramPackEntry(
            languageId = "mr",
            repoCode = "mr",
            bigramStem = "mr_bigrams",
            trigramStem = "mr_trigrams",
            approxGzBytes = 1_703_233,
        ),
        NgramPackEntry(
            languageId = "gu",
            repoCode = "gu",
            bigramStem = "gu_bigrams",
            trigramStem = "gu_trigrams",
            approxGzBytes = 1_468_283,
        ),
        NgramPackEntry(
            languageId = "pa",
            repoCode = "pa",
            bigramStem = "pa_bigrams",
            trigramStem = "pa_trigrams",
            approxGzBytes = 1_398_798,
        ),
        NgramPackEntry(
            languageId = "or",
            repoCode = "or",
            bigramStem = "or_bigrams",
            trigramStem = "or_trigrams",
            approxGzBytes = 835_130,
        ),
        NgramPackEntry(
            languageId = "as",
            repoCode = "as",
            bigramStem = "as_bigrams",
            trigramStem = "as_trigrams",
            approxGzBytes = 1_054_959,
        ),
        NgramPackEntry(
            languageId = "ta",
            repoCode = "ta",
            bigramStem = "ta_bigrams",
            trigramStem = "ta_trigrams",
            approxGzBytes = 1_959_859,
        ),
        NgramPackEntry(
            languageId = "te",
            repoCode = "te",
            bigramStem = "te_bigrams",
            trigramStem = "te_trigrams",
            approxGzBytes = 1_811_653,
        ),
        NgramPackEntry(
            languageId = "kn",
            repoCode = "kn",
            bigramStem = "kn_bigrams",
            trigramStem = "kn_trigrams",
            approxGzBytes = 1_857_900,
        ),
        NgramPackEntry(
            languageId = "ml",
            repoCode = "ml",
            bigramStem = "ml_bigrams",
            trigramStem = "ml_trigrams",
            approxGzBytes = 1_757_180,
        ),
        NgramPackEntry(
            languageId = "fa",
            repoCode = "fa",
            bigramStem = "fa_bigrams",
            trigramStem = "fa_trigrams",
            approxGzBytes = 1_395_329,
        ),
        NgramPackEntry(
            languageId = "ar",
            repoCode = "ar",
            bigramStem = "ar_bigrams",
            trigramStem = "ar_trigrams",
            approxGzBytes = 1_545_166,
        ),
        NgramPackEntry(
            languageId = "es",
            repoCode = "es",
            bigramStem = "es_bigrams",
            trigramStem = "es_trigrams",
            approxGzBytes = 1_265_849,
        ),
        NgramPackEntry(
            languageId = "fr",
            repoCode = "fr",
            bigramStem = "fr_bigrams",
            trigramStem = "fr_trigrams",
            approxGzBytes = 1_327_303,
        ),
        NgramPackEntry(
            languageId = "de",
            repoCode = "de",
            bigramStem = "de_bigrams",
            trigramStem = "de_trigrams",
            approxGzBytes = 1_298_189,
        ),
        NgramPackEntry(
            languageId = "pt",
            repoCode = "pt",
            bigramStem = "pt_bigrams",
            trigramStem = "pt_trigrams",
            approxGzBytes = 1_320_108,
        ),
        NgramPackEntry(
            languageId = "ru",
            repoCode = "ru",
            bigramStem = "ru_bigrams",
            trigramStem = "ru_trigrams",
            approxGzBytes = 1_537_818,
        ),
        NgramPackEntry(
            languageId = "it",
            repoCode = "it",
            bigramStem = "it_bigrams",
            trigramStem = "it_trigrams",
            approxGzBytes = 1_286_750,
        ),
        NgramPackEntry(
            languageId = "tr",
            repoCode = "tr",
            bigramStem = "tr_bigrams",
            trigramStem = "tr_trigrams",
            approxGzBytes = 1_490_458,
        ),
        NgramPackEntry(
            languageId = "id",
            repoCode = "id",
            bigramStem = "id_bigrams",
            trigramStem = "id_trigrams",
            approxGzBytes = 1_351_056,
        ),
        NgramPackEntry(
            languageId = "vi",
            repoCode = "vi",
            bigramStem = "vi_bigrams",
            trigramStem = "vi_trigrams",
            approxGzBytes = 1_263_341,
        ),
        NgramPackEntry(
            languageId = "pl",
            repoCode = "pl",
            bigramStem = "pl_bigrams",
            trigramStem = "pl_trigrams",
            approxGzBytes = 1_451_563,
        ),
        NgramPackEntry(
            languageId = "nl",
            repoCode = "nl",
            bigramStem = "nl_bigrams",
            trigramStem = "nl_trigrams",
            approxGzBytes = 1_200_623,
        ),
        NgramPackEntry(
            languageId = "uk",
            repoCode = "uk",
            bigramStem = "uk_bigrams",
            trigramStem = "uk_trigrams",
            approxGzBytes = 1_728_943,
        ),
        NgramPackEntry(
            languageId = "ko",
            repoCode = "ko",
            bigramStem = "ko_bigrams",
            trigramStem = "ko_trigrams",
            approxGzBytes = 1_529_913,
        ),
        NgramPackEntry(
            languageId = "ro",
            repoCode = "ro",
            bigramStem = "ro_bigrams",
            trigramStem = "ro_trigrams",
            approxGzBytes = 1_309_077,
        ),
        NgramPackEntry(
            languageId = "el",
            repoCode = "el",
            bigramStem = "el_bigrams",
            trigramStem = "el_trigrams",
            approxGzBytes = 1_639_033,
        ),
        NgramPackEntry(
            languageId = "cs",
            repoCode = "cs",
            bigramStem = "cs_bigrams",
            trigramStem = "cs_trigrams",
            approxGzBytes = 1_382_871,
        ),
        NgramPackEntry(
            languageId = "hu",
            repoCode = "hu",
            bigramStem = "hu_bigrams",
            trigramStem = "hu_trigrams",
            approxGzBytes = 1_370_515,
        ),
        NgramPackEntry(
            languageId = "sv",
            repoCode = "sv",
            bigramStem = "sv_bigrams",
            trigramStem = "sv_trigrams",
            approxGzBytes = 1_236_131,
        ),
        NgramPackEntry(
            languageId = "he",
            repoCode = "he",
            bigramStem = "he_bigrams",
            trigramStem = "he_trigrams",
            approxGzBytes = 1_360_510,
        ),
        NgramPackEntry(
            languageId = "ms",
            repoCode = "ms",
            bigramStem = "ms_bigrams",
            trigramStem = "ms_trigrams",
            approxGzBytes = 1_388_479,
        ),
        NgramPackEntry(
            languageId = "sw",
            repoCode = "sw",
            bigramStem = "sw_bigrams",
            trigramStem = "sw_trigrams",
            approxGzBytes = 1_105_398,
        ),
        NgramPackEntry(
            languageId = "tl",
            repoCode = "tl",
            bigramStem = "tl_bigrams",
            trigramStem = "tl_trigrams",
            approxGzBytes = 1_134_474,
        ),
        NgramPackEntry(
            languageId = "ne",
            repoCode = "ne",
            bigramStem = "ne_bigrams",
            trigramStem = "ne_trigrams",
            approxGzBytes = 1_711_668,
        ),
        NgramPackEntry(
            languageId = "si",
            repoCode = "si",
            bigramStem = "si_bigrams",
            trigramStem = "si_trigrams",
            approxGzBytes = 1_603_499,
        ),
        NgramPackEntry(
            languageId = "az",
            repoCode = "az",
            bigramStem = "az_bigrams",
            trigramStem = "az_trigrams",
            approxGzBytes = 1_440_413,
        ),
        NgramPackEntry(
            languageId = "uz",
            repoCode = "uz",
            bigramStem = "uz_bigrams",
            trigramStem = "uz_trigrams",
            approxGzBytes = 1_302_778,
        ),
        NgramPackEntry(
            languageId = "kk",
            repoCode = "kk",
            bigramStem = "kk_bigrams",
            trigramStem = "kk_trigrams",
            approxGzBytes = 1_455_744,
        ),
        NgramPackEntry(
            languageId = "da",
            repoCode = "da",
            bigramStem = "da_bigrams",
            trigramStem = "da_trigrams",
            approxGzBytes = 1_237_543,
        ),
        NgramPackEntry(
            languageId = "fi",
            repoCode = "fi",
            bigramStem = "fi_bigrams",
            trigramStem = "fi_trigrams",
            approxGzBytes = 1_389_457,
        ),
        NgramPackEntry(
            languageId = "nb",
            repoCode = "nb",
            bigramStem = "nb_bigrams",
            trigramStem = "nb_trigrams",
            approxGzBytes = 1_226_063,
        ),
        NgramPackEntry(
            languageId = "sk",
            repoCode = "sk",
            bigramStem = "sk_bigrams",
            trigramStem = "sk_trigrams",
            approxGzBytes = 1_410_500,
        ),
        NgramPackEntry(
            languageId = "bg",
            repoCode = "bg",
            bigramStem = "bg_bigrams",
            trigramStem = "bg_trigrams",
            approxGzBytes = 1_482_369,
        ),
        NgramPackEntry(
            languageId = "sr",
            repoCode = "sr",
            bigramStem = "sr_bigrams",
            trigramStem = "sr_trigrams",
            approxGzBytes = 1_481_462,
        ),
        NgramPackEntry(
            languageId = "hr",
            repoCode = "hr",
            bigramStem = "hr_bigrams",
            trigramStem = "hr_trigrams",
            approxGzBytes = 1_344_582,
        ),
        NgramPackEntry(
            languageId = "ca",
            repoCode = "ca",
            bigramStem = "ca_bigrams",
            trigramStem = "ca_trigrams",
            approxGzBytes = 1_217_419,
        ),
    )

    fun forLanguage(languageId: String): NgramPackEntry? =
        entries.firstOrNull { it.languageId == languageId }

    /** The pack [name] (gzip or not) belongs to, and whether it is the trigram half. */
    fun byFileName(name: String): Pair<NgramPackEntry, Boolean>? {
        val gz = if (name.endsWith(".txt")) "$name.gz" else name
        for (entry in entries) {
            if (entry.bigramFileName == gz) return entry to false
            if (entry.trigramFileName == gz) return entry to true
        }
        return null
    }
}
