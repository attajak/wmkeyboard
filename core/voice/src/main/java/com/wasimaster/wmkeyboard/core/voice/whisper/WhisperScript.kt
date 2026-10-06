package com.wasimaster.wmkeyboard.core.voice.whisper

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId

/**
 * Repairs a transcription that came back in the wrong script.
 *
 * Whisper works out the language from the clip itself on every graph that is not
 * built for one language or told which to use ([WhisperCatalog.autoDetectOnly]),
 * and on a short phrase it regularly picks a better-represented neighbour
 * instead: Bangla speech read as Hindi is the standard example. Nothing errors.
 * The words come back fluent, confident, and in the wrong alphabet.
 *
 * Where the wrong language is a *script* neighbour and not only a language one,
 * the mistake is both detectable and largely repairable. Bangla decoded as Hindi
 * is that case: the model still transcribed the Bangla words it heard, it just
 * spelled them in Devanagari. The two scripts are ISCII-derived and Unicode lays
 * them out in matching order, so mapping the letters across recovers readable
 * Bangla.
 *
 * What this does **not** do is fix a wrong language. Where the model produced
 * genuinely Hindi wording rather than Bangla words in Devanagari, converting the
 * letters gives Hindi spelled in Bangla script. That is still the better of the
 * two outcomes: it is legible, it is plainly wrong when it is wrong, and the
 * alternative is text in an alphabet the reader cannot use at all. The real fix
 * is a model that detects the language correctly, which is why settings points
 * these languages at the models with the best detection.
 *
 * The conversion runs only when the transcription's script disagrees with the
 * script of the language being typed in, so correctly detected dictation is never
 * touched. Dictating Hindi on a Bangla layout is the one case it gets wrong, and
 * that combination is already outside how dictation resolves its language.
 */
object WhisperScript {

    /** Start of the Devanagari block; the Bangla block sits exactly this far above. */
    private const val DEVANAGARI_BASE = 0x0900
    private const val BENGALI_BASE = 0x0980

    /** Block size shared by both, so a codepoint's offset within one indexes the other. */
    private const val BLOCK_SIZE = 0x80

    /**
     * Offsets within a block that mean the same thing in both: the signs, the
     * vowels and vowel signs, the consonants, the virama and nukta, and the
     * digits. A character at one of these offsets converts by arithmetic unless
     * [DEVANAGARI_EXCEPTIONS] or [BENGALI_EXCEPTIONS] says otherwise.
     *
     * The gap at 0x64..0x65 is the danda pair, which both scripts write with the
     * Devanagari codepoints ([SHARED]). Everything from 0x70 up is where the two
     * blocks stop corresponding at all: Devanagari fills it with extra letters,
     * Bangla with currency marks. Nothing there converts by offset.
     */
    private val ALIGNED: Set<Int> = ((0x00..0x63) + (0x66..0x6F)).toSet()

    /**
     * Punctuation at Devanagari's codepoints that Bangla uses as-is: danda,
     * double danda, the abbreviation sign and the high spacing dot. Neither
     * converted nor counted as evidence of one script or the other.
     */
    private val SHARED = setOf(0x0964, 0x0965, 0x0970, 0x0971)

    /**
     * Devanagari characters that do not become their Bangla counterpart by
     * offset, either because Bangla has nothing at that offset or because what
     * sits there means something unrelated. An empty replacement drops the
     * character, which is the honest answer for a mark Bangla simply does not
     * write.
     *
     * Every offset absent from this map and present in [ALIGNED] was checked to
     * land on an assigned Bangla character of the same meaning. That includes the
     * three Bangla letters written with a nukta: the offset produces the
     * precomposed form the rest of the keyboard uses, which is the reason they are
     * left to the arithmetic rather than written out as literals here.
     */
    private val DEVANAGARI_EXCEPTIONS: Map<Int, String> = mapOf(
        // Vowels and vowel signs Bangla has no slot for. The candra and short
        // forms exist to write English and Dravidian vowels in Devanagari, so
        // they fall back to the plain Bangla vowel of the same colour.
        0x0900 to "ঁ", // inverted candrabindu -> candrabindu
        0x0904 to "অ", // short A -> A
        0x090D to "ই", // candra E -> I
        0x090E to "ই", // short E -> I
        0x0911 to "ও", // candra O -> O
        0x0912 to "ও", // short O -> O
        0x093A to "ে", // sign OE -> sign E
        0x093B to "ো", // sign OOE -> sign O
        0x0945 to "ে", // candra E sign -> sign E
        0x0946 to "ে", // short E sign -> sign E
        0x0949 to "ো", // candra O sign -> sign O
        0x094A to "ো", // short O sign -> sign O
        0x094E to "ে", // prishthamatra E -> sign E
        0x094F to "ো", // sign AW -> sign O
        0x0955 to "ে", // candra long E sign -> sign E
        0x0956 to "ু", // sign UE -> sign U
        0x0957 to "ূ", // sign UUE -> sign UU
        // Consonants Bangla has no slot for.
        0x0929 to "ন", // NNNA -> NA
        0x0931 to "র", // RRA -> RA
        0x0933 to "ল", // LLA -> LA
        0x0934 to "ল", // LLLA -> LA
        0x0935 to "ব", // VA -> BA, which is how Bangla writes the sound
        // Nukta consonants. Bangla precomposes three of them and writes the rest
        // without the dot, which is what its spelling does anyway. DDDHA, RHA and
        // YYA are absent here on purpose: those three do line up by offset.
        0x0958 to "ক", // QA -> KA
        0x0959 to "খ", // KHHA -> KHA
        0x095A to "গ", // GHHA -> GA
        0x095B to "জ", // ZA -> JA
        0x095E to "ফ", // FA -> PHA
        // Religious and Vedic marks. Bangla writes OM as two characters; the
        // stress and accent marks it does not write at all.
        0x0950 to "ওঁ", // OM
        0x0951 to "", // stress sign udatta
        0x0952 to "", // stress sign anudatta
        0x0953 to "", // grave accent
        0x0954 to "", // acute accent
        // The extra letters at the top of the block. By offset these would land
        // on Bangla currency marks, so each one is spelled out instead.
        0x0972 to "অ", // candra A -> A
        0x0973 to "ও", // OE -> O
        0x0974 to "ও", // OOE -> O
        0x0975 to "ও", // AW -> O
        0x0976 to "উ", // UE -> U
        0x0977 to "ঊ", // UUE -> UU
        0x0978 to "ড", // Marwari DDA -> DDA
        0x0979 to "জ", // ZHA -> JA
        0x097A to "য", // heavy YA -> YA
        0x097B to "গ", // GGA -> GA
        0x097C to "জ", // JJA -> JA
        0x097E to "ড", // DDDA -> DDA
        0x097F to "ব", // BBA -> BA
    )

    /**
     * Bangla characters that do not become their Devanagari counterpart by
     * offset. Shorter than the other direction: Devanagari carries the wider
     * repertoire, so nearly everything lines up.
     *
     * Bangla's currency marks, from offset 0x72 up, are outside [ALIGNED] and so
     * pass through untouched rather than turning into Devanagari letters.
     */
    private val BENGALI_EXCEPTIONS: Map<Int, String> = mapOf(
        0x09CE to "त", // khanda ta -> TA; by offset this is a Devanagari vowel sign
        0x09D7 to "", // au length mark: only ever part of a decomposed vowel
        0x09F0 to "र", // Assamese RA with middle diagonal -> RA
        0x09F1 to "व", // Assamese RA with lower diagonal -> VA
    )

    /**
     * The two scripts this converts between, and no others. A pair belongs here
     * only where the letters correspond closely enough that converting beats
     * leaving the text unreadable.
     */
    private val CONVERTIBLE = setOf(ScriptId.DEVANAGARI, ScriptId.BENGALI)

    /**
     * [rescue] for a dictation that listens for several languages at once
     * (#416). Only a choice that writes in exactly one of the convertible
     * scripts has a script to correct towards: with both Hindi and Bangla in
     * it, text in either is a language the speaker asked for.
     */
    fun rescueAmong(text: String, languageIds: List<String>): String {
        val targets = languageIds.filter { LanguageRegistry.byId(it).script in CONVERTIBLE }
        if (targets.map { LanguageRegistry.byId(it).script }.distinct().size != 1) return text
        return rescue(text, targets.first())
    }

    /**
     * The transcription with its script corrected for [languageId], or [text]
     * unchanged when there is nothing to correct: the language does not use one
     * of the convertible scripts, the text is already in the right one, or the
     * text is in some third script this cannot reason about.
     */
    fun rescue(text: String, languageId: String): String {
        if (text.isEmpty()) return text
        val want = LanguageRegistry.byId(languageId).script
        if (want !in CONVERTIBLE) return text
        val got = dominantScript(text) ?: return text
        if (got == want) return text
        return if (want == ScriptId.BENGALI) {
            convert(text, DEVANAGARI_BASE, BENGALI_BASE, DEVANAGARI_EXCEPTIONS)
        } else {
            convert(text, BENGALI_BASE, DEVANAGARI_BASE, BENGALI_EXCEPTIONS)
        }
    }

    /**
     * Which of the two scripts [text] is mostly written in, or null when neither
     * appears. Shared punctuation and everything Latin (digits, names, a stray
     * English word) count for neither side, so a Bangla sentence with an English
     * word in it still reads as Bangla.
     */
    private fun dominantScript(text: String): ScriptId? {
        var devanagari = 0
        var bengali = 0
        for (ch in text) {
            val cp = ch.code
            if (cp in SHARED) continue
            when (cp) {
                in DEVANAGARI_BASE until DEVANAGARI_BASE + BLOCK_SIZE -> devanagari++
                in BENGALI_BASE until BENGALI_BASE + BLOCK_SIZE -> bengali++
            }
        }
        return when {
            devanagari == 0 && bengali == 0 -> null
            devanagari >= bengali -> ScriptId.DEVANAGARI
            else -> ScriptId.BENGALI
        }
    }

    /**
     * Rewrites the [from] block into the [to] block: [exceptions] first, then the
     * shared offset for anything [ALIGNED]. Characters outside the source block,
     * and those at offsets the two blocks do not share, pass through.
     */
    private fun convert(
        text: String,
        from: Int,
        to: Int,
        exceptions: Map<Int, String>,
    ): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            val cp = ch.code
            val offset = cp - from
            when {
                cp in SHARED || offset !in 0 until BLOCK_SIZE -> out.append(ch)
                exceptions.containsKey(cp) -> out.append(exceptions[cp])
                offset in ALIGNED -> out.append((to + offset).toChar())
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
