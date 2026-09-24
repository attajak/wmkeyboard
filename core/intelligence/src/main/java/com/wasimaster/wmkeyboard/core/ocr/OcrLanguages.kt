package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.settings.OcrEngine

/**
 * Which Tesseract language data ("pack") reads text in each keyboard language,
 * for the scan text tool.
 *
 * Packs are the `tessdata_fast` models: LSTM only, which is all the slimmed
 * engine in `native/tesseract-jni` can run, and about a quarter of the size of
 * `tessdata_best`. A pack is named by Tesseract's own code (`ben`, `chi_sim`),
 * and several keyboard languages can share one: both Norwegians read with
 * `nor`, and a language without a pack of its own reads with the main pack for
 * its script, so Maithili gets Hindi's Devanagari model.
 *
 * Never a catalogue on its own: the lists the user sees come from their
 * enabled languages through [packsFor].
 */
object OcrLanguages {

    /** The tessdata_fast release the downloads are pinned to. */
    const val SOURCE_TAG = "4.1.0"

    /**
     * Notation layouts and placeholders. They type Latin letters or symbols
     * but are not a language anyone prints, so they get no pack of their own
     * and the tool falls back to another enabled language.
     */
    private val notText = setOf("ipa", "fancy", "music", "braille", "morse", "und")

    fun downloadUrl(pack: String): String =
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$SOURCE_TAG/$pack.traineddata"

    /** The pack that reads [language], or null when no pack fits it. */
    fun packFor(language: LanguageDef): String? {
        if (language.id in notText) return null
        return byLanguageId[language.id] ?: byScript[language.script]
    }

    /** The download size of [pack] in bytes, or 0 for a name this table does not know. */
    fun sizeOf(pack: String): Long = sizes[pack] ?: 0L

    /**
     * A human name for [pack]: the English name of the first language that
     * reads with it as its own, or the pack name itself.
     */
    fun nameOf(pack: String): String =
        byLanguageId.entries.firstOrNull { it.value == pack }
            ?.let { LanguageRegistry.byId(it.key).englishName }
            ?: pack

    /** Every pack name this table can hand out. */
    val allPacks: Set<String> get() = sizes.keys

    /**
     * The packs [languages] need, each with the languages it serves, in the
     * order the languages come in. Languages with no pack are left out.
     */
    fun packsFor(languages: List<LanguageDef>): List<Pair<String, List<LanguageDef>>> =
        languages.mapNotNull { lang -> packFor(lang)?.let { it to lang } }
            .groupBy({ it.first }, { it.second })
            .toList()

    /**
     * The pack Tesseract reads [language] with under [engine], or null when
     * ML Kit reads it instead. [tesseractAvailable] is false in a build
     * without the native library, which leaves everything to ML Kit.
     */
    fun tesseractPack(engine: OcrEngine, language: LanguageDef, tesseractAvailable: Boolean): String? {
        if (!tesseractAvailable) return null
        val pack = packFor(language) ?: return null
        return when (engine) {
            OcrEngine.ML_KIT -> null
            OcrEngine.TESSERACT -> pack
            // ML Kit's recognizer here is the Latin one.
            OcrEngine.AUTO -> pack.takeIf { language.script != ScriptId.LATIN }
        }
    }

    /**
     * The languages the viewfinder's language chip steps through: one per
     * distinct way of reading, so two languages that read with the same pack,
     * or both with ML Kit, do not both appear. Empty under [OcrEngine.ML_KIT],
     * where the language makes no difference.
     */
    fun chipLanguages(
        engine: OcrEngine,
        enabled: List<LanguageDef>,
        tesseractAvailable: Boolean,
    ): List<LanguageDef> {
        if (engine == OcrEngine.ML_KIT || !tesseractAvailable) return emptyList()
        return enabled.filter { packFor(it) != null }
            .distinctBy { tesseractPack(engine, it, true) ?: ML_KIT_ROUTE }
    }

    private const val ML_KIT_ROUTE = ""

    // Keyboard language id -> pack, for every language with a pack of its own.
    // Sizes are the 4.1.0 files, for progress and the storage check.
    private val byLanguageId: Map<String, String> = mapOf(
        "en" to "eng", "bn" to "ben", "fr" to "fra", "de" to "deu", "es" to "spa", "ko" to "kor",
        "ru" to "rus", "ar" to "ara", "el" to "ell", "he" to "heb", "hi" to "hin", "pt" to "por",
        "uk" to "ukr", "it" to "ita", "nl" to "nld", "pl" to "pol", "sv" to "swe", "sr" to "srp",
        "sr-Latn" to "srp_latn", "bg" to "bul", "ka" to "kat", "cs" to "ces", "sk" to "slk",
        "ro" to "ron", "hu" to "hun", "fi" to "fin", "da" to "dan", "nb" to "nor", "nn" to "nor",
        "hr" to "hrv", "fa" to "fas", "be" to "bel", "et" to "est", "lt" to "lit", "lv" to "lav",
        "sl" to "slv", "ur" to "urd", "ps" to "pus", "sd" to "snd", "ug" to "uig", "hy" to "hye",
        "mk" to "mkd", "kk" to "kaz", "ky" to "kir", "tg" to "tgk", "mn" to "mon", "tt" to "tat",
        "mr" to "mar", "ne" to "nep", "sa" to "san", "ta" to "tam", "si" to "sin", "te" to "tel",
        "kn" to "kan", "ml" to "mal", "gu" to "guj", "pa" to "pan", "or" to "ori", "as" to "asm",
        "ca" to "cat", "gl" to "glg", "eu" to "eus", "oc" to "oci", "br" to "bre", "co" to "cos",
        "la" to "lat", "lb" to "ltz", "fy" to "fry", "fo" to "fao", "cy" to "cym", "ga" to "gle",
        "gd" to "gla", "is" to "isl", "sq" to "sqi", "mt" to "mlt", "eo" to "epo", "af" to "afr",
        "tr" to "tur", "az" to "aze", "uz" to "uzb", "ku" to "kmr", "id" to "ind", "ms" to "msa",
        "tl" to "fil", "ceb" to "ceb", "jv" to "jav", "su" to "sun", "mi" to "mri", "sw" to "swa",
        "yo" to "yor", "vi" to "vie", "ja" to "jpn", "zh" to "chi_sim", "yue" to "chi_tra",
        "ht" to "hat", "qu" to "que", "th" to "tha", "lo" to "lao", "km" to "khm", "my" to "mya",
        "am" to "amh", "ti" to "tir", "to" to "ton", "dv" to "div", "yi" to "yid", "syc" to "syr",
        "grc" to "grc", "chr" to "chr", "iu" to "iku", "bo" to "bod", "dz" to "dzo",
    )

    private val byScript: Map<ScriptId, String> = mapOf(
        ScriptId.LATIN to "eng",
        ScriptId.CYRILLIC to "rus",
        ScriptId.GREEK to "ell",
        ScriptId.ARMENIAN to "hye",
        ScriptId.GEORGIAN to "kat",
        ScriptId.ARABIC to "ara",
        ScriptId.HEBREW to "heb",
        ScriptId.SYRIAC to "syr",
        ScriptId.DEVANAGARI to "hin",
        ScriptId.BENGALI to "ben",
        ScriptId.GURMUKHI to "pan",
        ScriptId.GUJARATI to "guj",
        ScriptId.ORIYA to "ori",
        ScriptId.TAMIL to "tam",
        ScriptId.TELUGU to "tel",
        ScriptId.KANNADA to "kan",
        ScriptId.MALAYALAM to "mal",
        ScriptId.SINHALA to "sin",
        ScriptId.THAI to "tha",
        ScriptId.LAO to "lao",
        ScriptId.KHMER to "khm",
        ScriptId.MYANMAR to "mya",
        ScriptId.HANGUL to "kor",
        ScriptId.ETHIOPIC to "amh",
        ScriptId.THAANA to "div",
        ScriptId.JAPANESE to "jpn",
        ScriptId.HAN to "chi_sim",
        ScriptId.CHEROKEE to "chr",
        ScriptId.CANADIAN_ABORIGINAL_SYLLABICS to "iku",
        ScriptId.TIBETAN to "bod",
    )

    private val sizes: Map<String, Long> = mapOf(
        "afr" to 2652786L, "amh" to 5470094L, "ara" to 1432056L, "asm" to 2045427L, "aze" to 3524799L,
        "bel" to 3692098L, "ben" to 855841L, "bod" to 1966440L, "bre" to 6335007L, "bul" to 1675212L,
        "cat" to 1146012L, "ceb" to 716214L, "ces" to 3795684L, "chi_sim" to 2469156L,
        "chi_tra" to 2366642L, "chr" to 374944L, "cos" to 2299099L, "cym" to 2208919L, "dan" to 2580059L,
        "deu" to 1525436L, "div" to 1774535L, "dzo" to 449596L, "ell" to 1419514L, "eng" to 4113088L,
        "epo" to 4728393L, "est" to 4458101L, "eus" to 5175921L, "fao" to 3439772L, "fas" to 431500L,
        "fil" to 1841715L, "fin" to 7865732L, "fra" to 1130365L, "fry" to 1906018L, "gla" to 3068307L,
        "gle" to 1181824L, "glg" to 2554555L, "grc" to 2246328L, "guj" to 1418394L, "hat" to 1976902L,
        "heb" to 961404L, "hin" to 1122751L, "hrv" to 4103348L, "hun" to 5296273L, "hye" to 3463717L,
        "iku" to 2802266L, "ind" to 1122661L, "isl" to 2278973L, "ita" to 2701314L, "jav" to 2982741L,
        "jpn" to 2471260L, "kan" to 3608331L, "kat" to 2524769L, "kaz" to 4734644L, "khm" to 1446926L,
        "kir" to 9928497L, "kmr" to 3568615L, "kor" to 1677415L, "lao" to 6386744L, "lat" to 3187463L,
        "lav" to 2717247L, "lit" to 3154896L, "ltz" to 2606426L, "mal" to 5275996L, "mar" to 2118233L,
        "mkd" to 1600188L, "mlt" to 2308796L, "mon" to 2137042L, "mri" to 862973L, "msa" to 1747801L,
        "mya" to 4640561L, "nep" to 1002911L, "nld" to 6050296L, "nor" to 3610079L, "oci" to 6322087L,
        "ori" to 1480066L, "pan" to 497721L, "pol" to 4765518L, "por" to 1982756L, "pus" to 1772087L,
        "que" to 5026336L, "ron" to 2376323L, "rus" to 3861738L, "san" to 12404677L, "sin" to 1727413L,
        "slk" to 4427661L, "slv" to 3003829L, "snd" to 1694035L, "spa" to 2294433L, "sqi" to 1874705L,
        "srp" to 2149931L, "srp_latn" to 3281787L, "sun" to 1369500L, "swa" to 2167651L,
        "swe" to 4167034L, "syr" to 2207208L, "tam" to 3237963L, "tat" to 1072896L, "tel" to 2769654L,
        "tgk" to 2602685L, "tha" to 1072600L, "tir" to 378822L, "ton" to 947249L, "tur" to 4550554L,
        "uig" to 2794272L, "ukr" to 3825102L, "urd" to 1398718L, "uzb" to 6470703L, "vie" to 531275L,
        "yid" to 545606L, "yor" to 963400L,
    )
}
