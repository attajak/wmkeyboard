package com.wasimaster.wmkeyboard.core.translate

/**
 * What ML Kit's on-device translator can and cannot read, kept apart from ML
 * Kit itself so the lite build, the settings screens and the unit tests can all
 * reason about it without the library on the classpath.
 *
 * Three facts about the engine shape everything here:
 *
 *  - It has one model per language, about thirty megabytes each, and every
 *    model translates to and from English only. Bengali to German is Bengali
 *    to English to German, so it needs *both* models, and English itself is
 *    built in and never downloaded.
 *  - It cannot detect the source language. The online services all do, so the
 *    panel has always been "auto-detect"; on device that job goes to ML Kit's
 *    separate language identifier, and its answer is a BCP-47 tag that has to
 *    be mapped onto a model before it is any use.
 *  - Its Chinese model is Simplified. There is no Traditional one, so
 *    `zh-TW` as a target is an online-only language rather than a quiet
 *    substitution of the wrong script.
 */
object OfflineTranslateLanguages {

    /** The language every model pivots through, and the one with no download. */
    const val PIVOT = "en"

    /**
     * Roughly what one model costs to download and keep. ML Kit publishes no
     * per-language figure and its API reports none, so this is the documented
     * round number, used only in "about 30 MB" copy and as a progress total of
     * last resort.
     */
    const val APPROX_MODEL_BYTES = 30L * 1024 * 1024

    /**
     * ML Kit's translation languages, by its own codes. Pinned against
     * `TranslateLanguage.getAllLanguages()` by a full-flavour unit test, so a
     * library bump that adds or drops one fails there rather than in a picker.
     */
    val SUPPORTED: Set<String> = setOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en",
        "eo", "es", "et", "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr",
        "ht", "hu", "id", "is", "it", "ja", "ka", "kn", "ko", "lt", "lv", "mk",
        "mr", "ms", "mt", "nl", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq",
        "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    /**
     * Primary subtags that mean one of ML Kit's languages under another name:
     * the pre-1989 ISO codes Java still hands out, the two written Norwegians
     * (ML Kit ships one model for both), and Filipino, which is Tagalog.
     */
    private val ALIASES = mapOf(
        "iw" to "he",
        "in" to "id",
        "nb" to "no",
        "nn" to "no",
        "fil" to "tl",
    )

    /**
     * The model code for a picker code, a keyboard language id or a tag from
     * the language identifier, or null when the engine has no model for it.
     *
     * Null for anything written in a script the model was not trained on:
     * Traditional Chinese, and every romanised form the identifier reports
     * (`hi-Latn`, `ja-Latn`, …). Those are real detections of text the
     * translator would mangle, so they are refused rather than mapped.
     */
    fun modelCode(tag: String): String? {
        val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        val primary = parts.firstOrNull()?.lowercase() ?: return null
        val rest = parts.drop(1).map { it.lowercase() }
        if ("latn" in rest && isRomanized(tag)) return null
        if (primary == "zh" && rest.any { it in TRADITIONAL_CHINESE_SUBTAGS }) return null
        val code = ALIASES[primary] ?: primary
        return code.takeIf { it in SUPPORTED }
    }

    /**
     * Whether [tag] is the identifier's name for a language typed in Latin
     * letters instead of its own script. Only languages whose *model* expects
     * another script count: `sr-Latn` would be ordinary Serbian, but ML Kit has
     * no Serbian at all, and no Latin-script language is ever reported this way.
     */
    fun isRomanized(tag: String): Boolean {
        val parts = tag.trim().replace('_', '-').lowercase().split('-')
        return parts.size >= 2 && parts[1] == "latn" && parts[0] in ROMANIZABLE
    }

    /** The plain language under a romanised tag: `hi-Latn` gives `hi`. */
    fun baseLanguage(tag: String): String =
        tag.trim().replace('_', '-').substringBefore('-').lowercase()

    /**
     * The models a translation between two *model codes* needs on the device,
     * in the order worth downloading them. English is never among them.
     */
    fun modelsNeeded(source: String, target: String): List<String> =
        listOf(source, target).distinct().filter { it != PIVOT }

    private val TRADITIONAL_CHINESE_SUBTAGS = setOf("tw", "hk", "mo", "hant")

    /** The languages ML Kit's identifier reports a `-Latn` variant of. */
    private val ROMANIZABLE = setOf("ar", "bg", "el", "hi", "ja", "ru", "zh")
}

/** Where one language's model stands, as the panel and the settings list draw it. */
sealed interface OfflineModelState {

    /** Not on the device. [failed] when the last attempt to fetch it went wrong. */
    data class Missing(val failed: Boolean = false) : OfflineModelState

    /**
     * On its way. [bytes] and [totalBytes] are read off the system download
     * that ML Kit started, and both stay 0 while that download has not been
     * found yet, which the UI draws as an indeterminate wait.
     */
    data class Downloading(val bytes: Long = 0, val totalBytes: Long = 0) : OfflineModelState {
        val fraction: Float?
            get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    /** On the device and usable. English is always this. */
    data object Downloaded : OfflineModelState
}

/** What an on-device translation came to. Expected outcomes are values, not throws. */
sealed interface OfflineTranslateResult {

    /**
     * [source] is the model code the text was read as. [guessed] when the
     * identifier could not tell and the keyboard's own language stood in, so
     * the panel can show the source as a guess the user may want to correct.
     */
    data class Success(
        val text: String,
        val source: String,
        val guessed: Boolean = false,
    ) : OfflineTranslateResult

    /** Both languages are fine; these models have to be downloaded first. */
    data class NeedsModels(
        val source: String,
        val target: String,
        val missing: List<String>,
    ) : OfflineTranslateResult

    /**
     * The engine has no model for [language] (a picker code or an identifier
     * tag). [romanized] when it is a language ML Kit does have, typed in Latin
     * letters, which no model reads.
     */
    data class Unsupported(val language: String, val romanized: Boolean) : OfflineTranslateResult

    /** Nothing could say what language the text is in; the user has to pick. */
    data object Undetermined : OfflineTranslateResult

    /**
     * The engine itself is not on this install yet: a Play build, where it is
     * an on-demand module. The state to draw is [TranslateModule.gate]'s.
     */
    data object ModuleMissing : OfflineTranslateResult

    /** The engine itself failed: not initialised, out of memory, a corrupt model. */
    data class Failed(val cause: Throwable) : OfflineTranslateResult
}

/**
 * The decisions around an on-device translation that need no engine to make,
 * split out so they can be tested without one.
 */
object OfflineTranslatePlan {

    /**
     * One candidate from the language identifier: its BCP-47 tag and how sure
     * it was, 0 to 1.
     */
    data class Candidate(val tag: String, val confidence: Float)

    /** A settled source: a model code, or a tag the engine cannot read. */
    sealed interface Source {
        data class Known(val code: String, val guessed: Boolean) : Source
        data class Unreadable(val tag: String, val romanized: Boolean) : Source
        data object Unknown : Source
    }

    /** The identifier's answer is taken at its word from here up. */
    const val CONFIDENT = 0.5f

    /** Below this a candidate is noise, even when it agrees with a hint. */
    private const val PLAUSIBLE = 0.08f

    /**
     * Settles the source language.
     *
     * An explicit [override] always wins. Otherwise the identifier's best
     * candidate is believed once it is [CONFIDENT]. A word or two often does
     * not get there, and that is where [hints] earn their place: the languages the user types in, the active one first. A hint the
     * identifier also found at all plausible is taken as the answer; failing
     * that, the first hint that is not simply the target, as a guess. The
     * guess is marked so the panel can say so.
     */
    fun resolveSource(
        override: String?,
        candidates: List<Candidate>,
        hints: List<String>,
        target: String,
    ): Source {
        if (!override.isNullOrBlank()) return classify(override, guessed = false)
        val ranked = candidates
            .filter { it.tag.isNotBlank() && it.tag != UNDETERMINED }
            .sortedByDescending { it.confidence }
        val best = ranked.firstOrNull()
        if (best != null && best.confidence >= CONFIDENT) return classify(best.tag, guessed = false)

        val hintCodes = hints.mapNotNull { OfflineTranslateLanguages.modelCode(it) }.distinct()
        val agreed = ranked.firstOrNull { candidate ->
            candidate.confidence >= PLAUSIBLE &&
                OfflineTranslateLanguages.modelCode(candidate.tag) in hintCodes
        }
        if (agreed != null) return classify(agreed.tag, guessed = false)

        val targetCode = OfflineTranslateLanguages.modelCode(target)
        hintCodes.firstOrNull { it != targetCode }?.let { return Source.Known(it, guessed = true) }
        // Nothing to go on but a weak candidate: still better than refusing,
        // and marked as a guess for the same reason.
        if (best != null && best.confidence >= PLAUSIBLE) return classify(best.tag, guessed = true)
        return Source.Unknown
    }

    private fun classify(tag: String, guessed: Boolean): Source {
        val code = OfflineTranslateLanguages.modelCode(tag)
        return if (code != null) {
            Source.Known(code, guessed)
        } else {
            Source.Unreadable(tag, OfflineTranslateLanguages.isRomanized(tag))
        }
    }

    /** The identifier's code for "could not tell". */
    const val UNDETERMINED = "und"

    /** One stretch of the input: a line worth translating, or the gap between two. */
    data class Segment(val lead: String, val body: String, val trail: String)

    /**
     * Splits [text] so that line structure survives translation.
     *
     * The translator reads sentences and gives sentences back; line breaks,
     * indentation and blank lines in between are not part of its output. A
     * chat message with three short lines would come back as one. So each line
     * is translated on its own and put back between the same breaks, with its
     * own leading and trailing whitespace. A line with nothing to translate
     * (blank, or only punctuation and digits) passes through untouched.
     */
    fun segments(text: String): List<Segment> =
        text.split('\n').map { line ->
            val body = line.trim()
            if (body.isEmpty() || body.none { it.isLetter() }) {
                Segment(lead = line, body = "", trail = "")
            } else {
                val start = line.indexOf(body)
                Segment(
                    lead = line.substring(0, start),
                    body = body,
                    trail = line.substring(start + body.length),
                )
            }
        }

    /** Reassembles [segments] around their translated bodies, in order. */
    fun join(segments: List<Segment>, translatedBodies: List<String>): String {
        val bodies = translatedBodies.iterator()
        return segments.joinToString("\n") { segment ->
            if (segment.body.isEmpty()) segment.lead else segment.lead + bodies.next() + segment.trail
        }
    }
}
