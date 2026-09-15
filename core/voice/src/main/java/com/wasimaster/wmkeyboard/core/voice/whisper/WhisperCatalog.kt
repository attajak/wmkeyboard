package com.wasimaster.wmkeyboard.core.voice.whisper

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.voice.R
import java.io.IOException

/**
 * A Whisper failure carrying text the UI can show. [messageRes] is a string
 * resource id, so the message follows the app language instead of being frozen
 * in English inside the exception. [messageArg] is the one format argument the
 * message takes, or "" when it takes none.
 *
 * The exception's own `message` stays null on purpose: a caller that still
 * reads `message` falls back to its own generic text rather than printing a
 * resource id at the user.
 *
 * Declared here, in the flavour-independent source set, so the lite build sees
 * it too — the Whisper engine itself only exists in the full flavour.
 */
class WhisperException(
    @StringRes val messageRes: Int,
    val messageArg: String = "",
    cause: Throwable? = null,
) : IOException(null, cause)

/**
 * Whether a model is the one we point users at. [STANDARD] is everything else
 * and gets no badge. The catalog is all the same published conversions, so
 * there is nothing useful to say about the rest beyond their size and languages.
 */
enum class WhisperTier(@StringRes val badgeRes: Int?) {
    RECOMMENDED(R.string.core_voice_tier_recommended_label),
    STANDARD(null),
}

/**
 * Size class of a Whisper graph, ordered smallest to largest. Drives grouping and filters.
 *
 * [catalogName] is the size the way the model publishers spell it. It builds
 * [WhisperModel.displayName] and is never translated. [labelRes] is the same
 * word for the UI, which is translated.
 *
 * Turbo is a size class of its own rather than a flavour of Medium or Large:
 * it downloads at roughly Medium's size but transcribes at Large accuracy, so
 * filtering by size would put it in the wrong place either way.
 */
enum class WhisperSize(@StringRes val labelRes: Int, val catalogName: String) {
    TINY(R.string.core_voice_size_tiny_label, "Tiny"),
    BASE(R.string.core_voice_size_base_label, "Base"),
    SMALL(R.string.core_voice_size_small_label, "Small"),
    MEDIUM(R.string.core_voice_size_medium_label, "Medium"),
    TURBO(R.string.core_voice_size_turbo_label, "Turbo"),
    LARGE(R.string.core_voice_size_large_label, "Large"),
}

/**
 * One downloadable Whisper model. Unlike an LLM entry, each model needs a
 * companion `filters_vocab_*.bin` (mel filterbank + BPE vocab in one binary),
 * so a catalog entry maps to **two** files that both must land before the model
 * counts as downloaded.
 */
data class WhisperModel(
    /** Stable key — storage directory name and the whisperModelId setting value. */
    val id: String,
    val displayName: String,
    val size: WhisperSize,
    val repo: String,
    val modelFile: String,
    val vocabFile: String,
    /**
     * Where [vocabFile] comes from, when that is not the repo holding the model.
     * Only the 128-band filterbank needs this: no repo publishes both it and a
     * large-v3 graph.
     */
    val vocabRepo: String = repo,
    val modelBytes: Long,
    val vocabBytes: Long,
    /**
     * The Whisper language codes this graph can transcribe. Empty means all 99 —
     * the `-transcribe-translate` models detect the language themselves.
     */
    val langCodes: Set<String> = emptySet(),
    /**
     * True for the grouped graphs that expose `serving_transcribe_lang` and take
     * a `lang_token` scalar, so the keyboard forces whichever of [langCodes] is
     * being typed in instead of letting the model guess.
     */
    val selectableLang: Boolean = false,
    /** True where a `serving_translate` signature exists (speech → English). */
    val supportsTranslate: Boolean = false,
    val tier: WhisperTier,
    /** What this model is good for, as a string resource the UI resolves. */
    @StringRes val descriptionRes: Int,
    /**
     * The one format argument [descriptionRes] takes, or "" when it takes none.
     * The UI resolves the pair together:
     * `if (descriptionArg.isEmpty()) stringResource(descriptionRes)`
     * `else stringResource(descriptionRes, descriptionArg)`.
     */
    val descriptionArg: String = "",
    /**
     * Byte sizes of model files this entry used to download and must not run.
     * A file of one of these exact sizes is an earlier, broken graph left on
     * disk from before the entry changed source: [WhisperStore] reports the
     * model as not downloaded and deletes the file, so it comes down again
     * from the current [repo] instead of silently transcribing nothing.
     */
    val retiredModelBytes: Set<Long> = emptySet(),
) {
    /** Total download/footprint — used for the space preflight and progress fallback. */
    val sizeBytes: Long get() = modelBytes + vocabBytes

    @get:StringRes
    val sizeLabelRes: Int get() = size.labelRes

    /** False only for the graphs locked to one language (distilled and `.en`). */
    val multilingual: Boolean get() = langCodes.size != 1

    /** Whisper language code a single-language model is locked to; null otherwise. */
    val fixedLang: String? get() = langCodes.singleOrNull()

    /** Whether this model can transcribe [code] at all. */
    fun covers(code: String): Boolean = langCodes.isEmpty() || code in langCodes

    /** How many of [codes] this model handles — ranks catalog picks against enabled languages. */
    fun coverageOf(codes: Collection<String>): Int = codes.count { covers(it) }

    /**
     * The `lang_token` to force for a keyboard language, or null when this model
     * takes no such input or does not cover the language (then it auto-detects).
     */
    fun langTokenFor(languageId: String): Int? {
        if (!selectableLang) return null
        val code = WhisperLanguages.codeForLanguage(languageId) ?: return null
        if (code !in langCodes) return null
        return WhisperLanguages.tokenFor(code)
    }

    /**
     * The language name when this model handles exactly one language, else null.
     * The UI shows this in place of the [languageCount] plural. Language names
     * are proper nouns, so this one is not translated.
     */
    val singleLanguageName: String?
        get() = langCodes.singleOrNull()?.let { WhisperLanguages.label(it) }

    /**
     * How many languages this model transcribes. Feeds the
     * `core_voice_model_language_count` plural, which the UI shows whenever
     * [singleLanguageName] is null.
     */
    val languageCount: Int get() = if (langCodes.isEmpty()) ALL_LANGUAGES else langCodes.size

    companion object {
        /** Every language Whisper knows. An auto-detecting graph covers them all. */
        const val ALL_LANGUAGES = 99
    }
}

/**
 * Every offline Whisper graph we can actually run, from the two public Hugging
 * Face collections that publish them (no token or licence gate):
 *
 *  - `DocWolle/whisper_tflite_models` — the F-Droid Whisper app's set.
 *  - `nyadla-sys/whisper-tiny.en.tflite` — the original conversion repo, which
 *    carries the extra per-language and medium/large graphs.
 *  - `cik009/whisper` — for one file only, the 128-band `filters_vocab` the
 *    large-v3 generation needs. Neither repo above ships one.
 *
 * Three shapes exist, and the settings UI leans on the distinction:
 *  - **auto** (`-transcribe-translate`): all 99 languages, detected per clip,
 *    plus a translate-to-English task.
 *  - **grouped** (`TOP_WORLD`, `EUROPEAN_UNION`): a language *subset* baked into
 *    one graph with a `lang_token` input, so the keyboard forces the language
 *    you are typing in — more accurate than auto-detect on short clips, and far
 *    less disk than one single-language model per language.
 *  - **single-language** (`base.de`, `small.en`, …): one language, no choice.
 *
 * The large-v3 generation (`large-v3` and `turbo`) needs a 128-band mel
 * spectrogram where every earlier model needs 80. Those two pair with the
 * 128-band `filters_vocab_multilingual-v3.bin`, and [WhisperMel] takes the band
 * count from whichever filterbank came down with the model.
 *
 * Not included on purpose: `whisper-small.tflite` forces no task and duplicates
 * `small-multi`; `whisper-tiny-en.tflite` takes decoder token ids, not a
 * spectrogram; DocWolle's `whisper-tiny.en.tflite` forces the multilingual
 * prompt (`<|en|>` `<|transcribe|>` as ids 50259/50359) onto the English-only
 * tokenizer, where those ids mean something else, so it answers most clips with
 * an immediate end-of-text and dictation comes back empty (#207) — `tiny-en`
 * takes nyadla's conversion of the same name, which forces the right prompt;
 * and the `-with-timestamp-` graphs emit timestamp tokens this decoder has no
 * use for.
 *
 * Order is a suggested-pick ranking, not a size ranking — the settings list
 * renders in catalog order, so the balanced default sits first.
 *
 * Only the multi-language graphs are shown unconditionally; the single-language
 * ones surface once their language is enabled. See [visibleFor].
 */
object WhisperCatalog {

    private const val REPO = "DocWolle/whisper_tflite_models"
    private const val REPO_NYADLA = "nyadla-sys/whisper-tiny.en.tflite"
    private const val REPO_CIK = "cik009/whisper"
    private const val VOCAB_MULTI = "filters_vocab_multilingual.bin"
    private const val VOCAB_EN = "filters_vocab_en.bin"

    /** The 128-band filterbank, for large-v3 and turbo only. */
    private const val VOCAB_V3 = "filters_vocab_multilingual-v3.bin"
    private const val VOCAB_MULTI_BYTES = 572_421L
    private const val VOCAB_EN_BYTES = 586_174L
    private const val VOCAB_V3_BYTES = 611_013L

    /**
     * The 40 languages baked into the `TOP_WORLD` graphs, decoded from the
     * `whisper-base.TOP_WORLD.tokens` file that ships beside them.
     */
    private val TOP_WORLD = setOf(
        "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl",
        "ar", "sv", "it", "id", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da",
        "hu", "ta", "no", "th", "ur", "hr", "bg", "lt", "sk", "lv", "sl", "et", "mt", "lb",
    )

    /** The 26 languages in the `EUROPEAN_UNION` graph, from its own `.tokens` file. */
    private val EUROPEAN_UNION = setOf(
        "en", "de", "es", "fr", "pt", "tr", "pl", "nl", "sv", "it", "fi", "el", "cs",
        "ro", "da", "hu", "no", "hr", "bg", "lt", "sk", "lv", "sl", "et", "mt", "lb",
    )

    val models: List<WhisperModel> = buildList {
        // ---- Grouped, language-selectable: the best fit for a multilingual keyboard.
        add(
            WhisperModel(
                id = "base-world",
                displayName = "Whisper Base (40 languages)",
                size = WhisperSize.BASE,
                repo = REPO,
                modelFile = "whisper-base.TOP_WORLD.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 107_564_368L,
                vocabBytes = VOCAB_MULTI_BYTES,
                langCodes = TOP_WORLD,
                selectableLang = true,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_base_world_body,
            ),
        )
        add(
            WhisperModel(
                id = "base-eu",
                displayName = "Whisper Base (European)",
                size = WhisperSize.BASE,
                repo = REPO,
                modelFile = "whisper-base.EUROPEAN_UNION.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 94_819_264L,
                vocabBytes = VOCAB_MULTI_BYTES,
                langCodes = EUROPEAN_UNION,
                selectableLang = true,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_base_eu_body,
            ),
        )
        add(
            WhisperModel(
                id = "small-world",
                displayName = "Whisper Small (40 languages)",
                size = WhisperSize.SMALL,
                repo = REPO,
                modelFile = "whisper-small.TOP_WORLD.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 307_408_944L,
                vocabBytes = VOCAB_MULTI_BYTES,
                langCodes = TOP_WORLD,
                selectableLang = true,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_small_world_body,
            ),
        )

        // ---- Auto-detecting, all 99 languages, translate-capable.
        add(
            WhisperModel(
                id = "base-multi",
                displayName = "Whisper Base",
                size = WhisperSize.BASE,
                repo = REPO,
                modelFile = "whisper-base-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 78_508_512L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_base_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "tiny-multi",
                displayName = "Whisper Tiny",
                size = WhisperSize.TINY,
                repo = REPO,
                modelFile = "whisper-tiny-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 42_103_936L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_tiny_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "small-multi",
                displayName = "Whisper Small",
                size = WhisperSize.SMALL,
                repo = REPO,
                modelFile = "whisper-small-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 248_684_440L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.RECOMMENDED,
                descriptionRes = R.string.core_voice_model_small_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "medium-multi",
                displayName = "Whisper Medium",
                size = WhisperSize.MEDIUM,
                repo = REPO_NYADLA,
                modelFile = "whisper-medium-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 775_637_984L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_medium_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "large-multi",
                displayName = "Whisper Large",
                size = WhisperSize.LARGE,
                repo = REPO_NYADLA,
                modelFile = "whisper-large-transcribe-translate.tflite",
                vocabFile = VOCAB_MULTI,
                modelBytes = 1_559_228_864L,
                vocabBytes = VOCAB_MULTI_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_large_multi_body,
            ),
        )

        // ---- The large-v3 generation: 128 mel bands, so a different filterbank.
        add(
            WhisperModel(
                id = "turbo-multi",
                displayName = "Whisper Turbo",
                size = WhisperSize.TURBO,
                repo = REPO_NYADLA,
                modelFile = "whisper-turbo-transcribe-translate.tflite",
                vocabFile = VOCAB_V3,
                vocabRepo = REPO_CIK,
                modelBytes = 818_545_328L,
                vocabBytes = VOCAB_V3_BYTES,
                // large-v3-turbo dropped the translate task in training, so the
                // signature is not offered even where the graph still carries it.
                supportsTranslate = false,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_turbo_multi_body,
            ),
        )
        add(
            WhisperModel(
                id = "large-v3-multi",
                displayName = "Whisper Large v3",
                size = WhisperSize.LARGE,
                repo = REPO_NYADLA,
                modelFile = "whisper-large-v3-transcribe-translate.tflite",
                vocabFile = VOCAB_V3,
                vocabRepo = REPO_CIK,
                modelBytes = 1_559_408_000L,
                vocabBytes = VOCAB_V3_BYTES,
                supportsTranslate = true,
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_large_v3_multi_body,
            ),
        )

        // ---- Single-language graphs: one language each, no detection step.
        addAll(
            singleLanguage(
                Single(
                    "tiny-en", WhisperSize.TINY, "whisper-tiny.en.tflite", "en", REPO_NYADLA, 41_507_968L,
                    // DocWolle's graph of the same name, which tiny-en downloaded
                    // before #207 and which transcribes almost nothing.
                    retired = setOf(41_486_616L),
                ),
                Single("base-en", WhisperSize.BASE, "whisper-base.en.tflite", "en", REPO_NYADLA, 77_642_600L),
                Single("small-en", WhisperSize.SMALL, "whisper-small.en.tflite", "en", REPO_NYADLA, 247_048_280L),
                Single("medium-en", WhisperSize.MEDIUM, "whisper-medium.en.tflite", "en", REPO_NYADLA, 772_442_720L),
                Single("base-de", WhisperSize.BASE, "whisper-base.de.tflite", "de", REPO, 78_448_496L),
                Single("base-es", WhisperSize.BASE, "whisper-base.es.tflite", "es", REPO, 78_448_496L),
                Single("small-es", WhisperSize.SMALL, "whisper-small.es.tflite", "es", REPO_NYADLA, 247_025_152L),
                Single("base-fr", WhisperSize.BASE, "whisper-base.fr.tflite", "fr", REPO, 78_448_496L),
                Single("base-hi", WhisperSize.BASE, "whisper-base.hi.tflite", "hi", REPO, 78_448_496L),
                Single("small-hi", WhisperSize.SMALL, "whisper-small.hi.tflite", "hi", REPO_NYADLA, 247_025_144L),
                Single("base-it", WhisperSize.BASE, "whisper-base.it.tflite", "it", REPO, 78_448_496L),
                Single("base-ja", WhisperSize.BASE, "whisper-base.ja.tflite", "ja", REPO_NYADLA, 77_619_400L),
                Single("base-pt", WhisperSize.BASE, "whisper-base.pt.tflite", "pt", REPO, 78_448_496L),
                Single("base-ru", WhisperSize.BASE, "whisper-base.ru.tflite", "ru", REPO, 78_448_496L),
                Single("small-ta", WhisperSize.SMALL, "whisper-small.ta.tflite", "ta", REPO_NYADLA, 247_025_192L),
                Single("small-te", WhisperSize.SMALL, "whisper-small.te.tflite", "te", REPO_NYADLA, 247_025_192L),
                Single("base-ur", WhisperSize.BASE, "whisper-base.ur.tflite", "ur", REPO, 78_437_408L),
                Single("small-ur", WhisperSize.SMALL, "whisper-small.ur.tflite", "ur", REPO, 248_612_928L),
                Single("base-zh", WhisperSize.BASE, "whisper-base.zh.tflite", "zh", REPO, 78_448_496L),
            ),
        )
    }

    /** Every language at least one single-language graph exists for. */
    val singleLanguageCodes: Set<String> =
        models.mapNotNullTo(mutableSetOf()) { it.fixedLang }

    private class Single(
        val id: String,
        val size: WhisperSize,
        val file: String,
        val code: String,
        val repo: String,
        val bytes: Long,
        val retired: Set<Long> = emptySet(),
    )

    /** Single-language graphs share one shape — spell out only what differs. */
    private fun singleLanguage(vararg entries: Single): List<WhisperModel> =
        entries.map { entry ->
            // The English graphs are built on the English-only tokenizer, so they
            // need the other vocab binary; every other one is a distilled
            // multilingual graph with its language forced.
            val english = entry.code == "en"
            val name = WhisperLanguages.label(entry.code)
            WhisperModel(
                id = entry.id,
                displayName = "Whisper ${entry.size.catalogName} ($name)",
                size = entry.size,
                repo = entry.repo,
                modelFile = entry.file,
                vocabFile = if (english) VOCAB_EN else VOCAB_MULTI,
                modelBytes = entry.bytes,
                vocabBytes = if (english) VOCAB_EN_BYTES else VOCAB_MULTI_BYTES,
                langCodes = setOf(entry.code),
                tier = WhisperTier.STANDARD,
                descriptionRes = R.string.core_voice_model_single_language_body,
                descriptionArg = name,
                retiredModelBytes = entry.retired,
            )
        }

    init {
        check(models.map { it.id }.toSet().size == models.size) { "catalog ids must be unique" }
    }

    fun byId(id: String): WhisperModel? = models.firstOrNull { it.id == id }

    /**
     * The catalog as it should be offered to someone whose enabled languages are
     * [codes]: every multi-language graph, plus the single-language graphs for
     * languages they actually type in. Showing a German-only model to someone who
     * has never enabled German is noise — and downloading one would be a wasted
     * 78 MB, since nothing would ever route dictation to it.
     */
    fun visibleFor(codes: Set<String>): List<WhisperModel> =
        models.filter { model -> model.fixedLang?.let { it in codes } ?: true }

    /** The single-language graphs for one language, smallest first. */
    fun singleLanguageFor(code: String): List<WhisperModel> =
        models.filter { it.fixedLang == code }.sortedBy { it.sizeBytes }

    /**
     * True when no model in the catalog can be *told* to transcribe [code]: there
     * is no graph built for it alone, and no grouped graph lists it. Every option
     * left has to work out the language from the clip itself.
     *
     * This matters because auto-detection is the one Whisper step that fails
     * silently and completely. A language it confuses with a better-represented
     * neighbour — Bangla read as Hindi is the standard example — comes back as
     * fluent text in the wrong language, with no error anywhere. The graphs are
     * published conversions and the language list is compiled into each one (a
     * branch per language token), so this is not something the keyboard can fix
     * by asking differently; all it can do is say so, point at the models whose
     * detection is best, and repair the script afterwards where the two
     * languages happen to be script-convertible ([WhisperScript]).
     */
    fun autoDetectOnly(code: String): Boolean =
        models.none { it.fixedLang == code || (it.selectableLang && code in it.langCodes) }

    /**
     * Ranks [candidates] by how well each transcribes [code], best first. Used
     * both to suggest downloads and to pick which downloaded model handles a
     * language when the user has not said explicitly.
     *
     * A graph built for exactly this language wins, then one that can be *told*
     * the language, then a plain auto-detecting one; within a rank, more
     * parameters means better transcription, so bigger wins. Models that cannot
     * do the language at all are dropped.
     */
    fun rankedFor(code: String, candidates: List<WhisperModel> = models): List<WhisperModel> =
        candidates.filter { it.covers(code) }.sortedWith(
            compareBy<WhisperModel> {
                when {
                    it.fixedLang == code -> 0
                    it.selectableLang -> 1
                    else -> 2
                }
            }.thenByDescending { it.sizeBytes },
        )

    /**
     * The models worth suggesting for an enabled-language set, best first: the
     * grouped or single-language graphs that actually cover those languages,
     * falling back to the all-languages default. Capped at [limit] so the
     * settings screen stays a shortlist rather than a second full catalog.
     */
    fun recommendedFor(codes: Set<String>, limit: Int = 4): List<WhisperModel> {
        val default = byId("small-multi")
        if (codes.isEmpty()) return listOfNotNull(default)
        val picks = LinkedHashSet<WhisperModel>()
        // The one we point at first, then the smallest download, so a shortlist
        // spans the accuracy/size trade-off instead of listing near-clones.
        val bestFirst = compareBy<WhisperModel>({ it.tier != WhisperTier.RECOMMENDED }, { it.sizeBytes })

        // A grouped graph that covers everything the user types is the standout
        // pick: one download, and the language forced per phrase. Among those that
        // fit, the widest one goes first — it is the one that still fits after the
        // user enables another language — and only then the smallest download.
        models.filter { it.selectableLang && it.coverageOf(codes) == codes.size }
            .sortedWith(compareByDescending<WhisperModel> { it.langCodes.size }.thenBy { it.sizeBytes })
            .firstOrNull()
            ?.let { picks += it }

        // Then the single-language graphs, one per language, so a one-language
        // user is pointed straight at their own model.
        for (code in codes) {
            models.filter { it.fixedLang == code }.minWithOrNull(bestFirst)?.let { picks += it }
        }

        // Always leave an all-languages escape hatch — the only thing that
        // covers a language with no dedicated graph.
        default?.let { picks += it }

        // A language nothing can be told about rides entirely on how well the
        // model detects it, so the best detector in the catalog belongs on the
        // shortlist even at its size. Nothing else on this screen would lead
        // someone there, and for those languages it is the difference between
        // usable dictation and fluent text in the wrong language.
        if (codes.any { autoDetectOnly(it) }) {
            byId("turbo-multi")?.let { picks += it }
        }

        // Partial grouped coverage is still useful when nothing above fit well.
        models.filter { it.selectableLang && it.coverageOf(codes) > 0 }
            .sortedByDescending { it.coverageOf(codes) }
            .forEach { picks += it }

        return picks.take(limit)
    }

    fun downloadUrl(repo: String, fileName: String): String =
        "https://huggingface.co/$repo/resolve/main/$fileName"
}
