package com.wasimaster.wmkeyboard.core.transliteration

/**
 * The Indic scripts [IndicPhonetic] writes, and the move between each of them
 * and Devanagari.
 *
 * Every Unicode block from Devanagari to Malayalam is laid out in parallel —
 * the ISCII heritage: क is U+0915, ক U+0995, ਕ U+0A15, ક U+0A95, କ U+0B15,
 * க U+0B95, క U+0C15, ಕ U+0C95, ക U+0D15, the same letter 0x80 apart each time.
 * So the phonetic engine spells every word in Devanagari, and [fromDevanagari]
 * shifts it into the target block and then applies what that script does
 * differently: Tamil's missing voiced and aspirated letters, Malayalam's
 * chillus, Gurmukhi's addak and tippi, Odia's ୟ and ୱ. [toDevanagari] goes the
 * other way, so the word lists of all of them can be folded by the one
 * Devanagari index ([IndicPhoneticIndex]) and romanized by the one romanizer.
 */
enum class IndicScript(private val offset: Int) {
    DEVANAGARI(0x000),
    BENGALI(0x080),
    GURMUKHI(0x100),
    GUJARATI(0x180),
    ORIYA(0x200),
    TAMIL(0x280),
    TELUGU(0x300),
    KANNADA(0x380),
    MALAYALAM(0x400),
    ;

    /** This script's Unicode block. */
    val block: IntRange get() = (DEV_START + offset)..(DEV_END + offset)

    /** A letter or sign of this script — not its digits, not the dandas. */
    fun isNative(c: Char): Boolean {
        val code = c.code - offset
        return c.code in block && code !in 0x0966..0x096F && code != 0x0964 && code != 0x0965
    }

    /** Devanagari, as [IndicPhonetic] spells it, moved into this script. */
    fun fromDevanagari(dev: String): String = when (this) {
        DEVANAGARI -> dev
        TAMIL -> toTamil(dev)
        MALAYALAM -> toMalayalam(dev)
        GURMUKHI -> toGurmukhi(dev)
        ORIYA -> toOriya(dev)
        GUJARATI -> shift(foldShortEO(dev))
        // ऴ has no letter in either: it is ळ there.
        TELUGU, KANNADA -> shift(dev.replace(LLLA, LLA)).filter { it.code != NUKTA.code + offset }
        BENGALI -> shift(dev)
    }

    /**
     * Text in this script spelled in Devanagari, letter for letter, with this
     * script's own conventions undone: a chillu becomes its consonant and a
     * virama, an addak the consonant written twice, a tippi an anusvara.
     */
    fun toDevanagari(text: String): String {
        if (this == DEVANAGARI) return text
        val out = StringBuilder(text.length + 4)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val special = when (this) {
                GURMUKHI -> gurmukhiToDevanagari(text, i, out)
                ORIYA -> ORIYA_BACK[c]?.also { out.append(it) } != null
                MALAYALAM -> MALAYALAM_BACK[c]?.also { out.append(it) } != null
                KANNADA -> (c == 'ೞ').also { if (it) out.append(LLLA) }
                else -> false
            }
            if (!special) {
                out.append(if (c.code in block) (c.code - offset).toChar() else c)
            }
            i++
        }
        return out.toString()
    }

    private fun shift(dev: String): String {
        if (offset == 0) return dev
        val out = StringBuilder(dev.length)
        for (c in dev) {
            out.append(if (c.code in DEV_START..DEV_END && c != DANDA && c != DOUBLE_DANDA) (c.code + offset).toChar() else c)
        }
        return out.toString()
    }

    private fun foldShortEO(dev: String): String =
        dev.replace(SHORT_E, LONG_E).replace(SHORT_O, LONG_O)
            .replace(SHORT_E_SIGN, LONG_E_SIGN).replace(SHORT_O_SIGN, LONG_O_SIGN)

    /**
     * Tamil has no voiced or aspirated stops of its own, so those fold onto the
     * unvoiced letter, which Tamil reads by position; it writes no anusvara, so
     * a nasal is spelled out homorganic with what follows it; and it has no
     * nukta.
     */
    private fun toTamil(dev: String): String {
        val folded = StringBuilder(dev.length)
        for (c in dev) {
            if (c == NUKTA) continue
            folded.append(TAMIL_FOLD[c] ?: c.toString())
        }
        val out = StringBuilder(folded.length + 4)
        for ((i, c) in folded.withIndex()) {
            if (c == ANUSVARA) {
                out.append(TAMIL_NASAL_BEFORE[folded.getOrNull(i + 1)] ?: TAMIL_ALVEOLAR_N).append(VIRAMA)
            } else {
                out.append(c)
            }
        }
        return shift(out.toString())
    }

    /** A consonant closing a word becomes its chillu, where Malayalam has one. */
    private fun toMalayalam(dev: String): String {
        val out = StringBuilder(dev.length)
        var i = 0
        while (i < dev.length) {
            val c = dev[i]
            val chillu = MALAYALAM_CHILLU[c]
            val after = dev.getOrNull(i + 2)
            if (chillu != null && dev.getOrNull(i + 1) == VIRAMA && (after == null || after.code !in DEV_START..DEV_END)) {
                out.append(chillu)
                i += 2
                continue
            }
            if (c != NUKTA) out.append(if (c.code in DEV_START..DEV_END && c != DANDA && c != DOUBLE_DANDA) (c.code + offset).toChar() else c)
            i++
        }
        return out.toString()
    }

    /**
     * Gurmukhi writes no conjuncts beyond the subjoined ra and ha (pairin; a
     * subjoined va is archaic — ਸਵਾਲ, not ਸ੍ਵਾਲ):
     * a doubled consonant is the addak before it, any other cluster loses its
     * virama, and a word-final virama is not written. The anusvara is a tippi
     * after a short vowel or a bare consonant and a bindi after a long one.
     */
    private fun toGurmukhi(devIn: String): String {
        val dev = devIn.replace("ड़", "ੜ").replace("ढ़", "ੜ")
        val out = StringBuilder(dev.length)
        var i = 0
        while (i < dev.length) {
            val c = dev[i]
            val next = dev.getOrNull(i + 1)
            when {
                c == VIRAMA -> {
                    val prev = dev.getOrNull(i - 1)
                    when {
                        next == null || next !in CONSONANTS -> Unit
                        next == prev && prev in NASALS -> {
                            // A doubled nasal is a tippi (a bindi after a long
                            // vowel), not an addak: ਕੰਮ, ਕਿੰਨਾ.
                            out.setLength(out.length - 1)
                            val before = dev.getOrNull(i - 2)
                            val short = before != null && (before in CONSONANTS || before in SHORT_NASAL_VOWELS)
                            out.append(if (short) TIPPI else BINDI)
                        }
                        next == prev || ASPIRATE[prev] == next -> {
                            // ਪੱਕਾ is ਪ ੱ ਕ ਾ: the addak, and one ਕ.
                            out.setLength(out.length - 1)
                            out.append(ADDAK)
                        }
                        next in PAIRIN -> out.append(GURMUKHI_VIRAMA)
                        else -> Unit
                    }
                }
                c == ANUSVARA -> {
                    val prev = dev.getOrNull(i - 1)
                    val short = prev != null && (prev in CONSONANTS || prev in SHORT_NASAL_VOWELS)
                    out.append(if (short) TIPPI else BINDI)
                }
                c == 'ष' -> out.append('ਸ਼')
                c == SHORT_E || c == LONG_E -> out.append('ਏ')
                c == SHORT_O || c == LONG_O -> out.append('ਓ')
                c == SHORT_E_SIGN -> out.append('ੇ')
                c == SHORT_O_SIGN -> out.append('ੋ')
                c.code in DEV_START..DEV_END && c != DANDA && c != DOUBLE_DANDA -> out.append((c.code + offset).toChar())
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Odia writes ଯ where a word opens (said "j" there) and ୟ elsewhere; w is ୱ. */
    private fun toOriya(dev: String): String {
        val out = StringBuilder(dev.length)
        for ((i, c) in dev.withIndex()) {
            when (c) {
                'य' -> {
                    val prev = dev.getOrNull(i - 1)
                    val opening = prev == null || prev.code !in DEV_START..DEV_END
                    out.append(if (opening) 'ଯ' else 'ୟ')
                }
                'व' -> out.append('ୱ')
                SHORT_E -> out.append('ଏ')
                SHORT_O -> out.append('ଓ')
                SHORT_E_SIGN -> out.append('େ')
                SHORT_O_SIGN -> out.append('ୋ')
                else -> out.append(if (c.code in DEV_START..DEV_END && c != DANDA && c != DOUBLE_DANDA) (c.code + offset).toChar() else c)
            }
        }
        return out.toString().replace("ଡ଼", "ଡ଼").replace("ଢ଼", "ଢ଼")
    }

    /** Gurmukhi's own signs, read back; true when [text] at [i] was one of them. */
    private fun gurmukhiToDevanagari(text: String, i: Int, out: StringBuilder): Boolean {
        when (val c = text[i]) {
            TIPPI, BINDI -> out.append(ANUSVARA)
            ADDAK -> {
                // The letter it doubles comes next: write it, and a virama, now.
                val next = text.getOrNull(i + 1) ?: return true
                if (next.code in block) out.append((next.code - offset).toChar()).append(VIRAMA)
            }
            'ਖ਼' -> out.append("ख़")
            'ਗ਼' -> out.append("ग़")
            'ਜ਼' -> out.append("ज़")
            'ੜ' -> out.append("ड़")
            'ਫ਼' -> out.append("फ़")
            else -> return c == '਼' // a separate nukta: dropped
        }
        return true
    }

    companion object {
        private const val DEV_START = 0x0900
        private const val DEV_END = 0x097F
        private const val VIRAMA = '्'
        private const val ANUSVARA = 'ं'
        private const val NUKTA = '़'
        private const val DANDA = '।'
        private const val DOUBLE_DANDA = '॥'
        private const val LLA = "ळ"
        private const val LLLA = "ऴ"
        private const val SHORT_E = 'ऎ'
        private const val LONG_E = 'ए'
        private const val SHORT_O = 'ऒ'
        private const val LONG_O = 'ओ'
        private const val SHORT_E_SIGN = 'ॆ'
        private const val LONG_E_SIGN = 'े'
        private const val SHORT_O_SIGN = 'ॊ'
        private const val LONG_O_SIGN = 'ो'
        private const val ADDAK = 'ੱ'
        private const val TIPPI = 'ੰ'
        private const val BINDI = 'ਂ'
        private const val GURMUKHI_VIRAMA = '੍'
        private const val TAMIL_ALVEOLAR_N = 'ऩ'

        private val CONSONANTS: Set<Char> = ('क'..'ह').toSet()
        private val PAIRIN = setOf('\u0930', '\u0939')

        /** ਨ ਮ ਣ: doubled, a tippi or bindi rather than an addak. */
        private val NASALS = setOf('\u0928', '\u092E', '\u0923')

        /**
         * What a tippi follows: the inherent vowel, ਿ, ੁ, ੂ (ਤੂੰ, ਨੂੰ), ਅ and ਇ.
         * Everything else takes a bindi — ਉ included (ਕਿਉਂ, ਆਉਂਦਾ).
         */
        private const val SHORT_NASAL_VOWELS = "\u093F\u0941\u0942\u0905\u0907"

        private val ASPIRATE: Map<Char?, Char> = mapOf(
            'क' to 'ख', 'ग' to 'घ', 'च' to 'छ', 'ज' to 'झ',
            'ट' to 'ठ', 'ड' to 'ढ', 'त' to 'थ', 'द' to 'ध',
            'प' to 'फ', 'ब' to 'भ',
        )

        private val TAMIL_FOLD: Map<Char, String> = mapOf(
            'ख' to "क", 'ग' to "क", 'घ' to "क",
            'छ' to "च", 'झ' to "ज",
            'ठ' to "ट", 'ड' to "ट", 'ढ' to "ट",
            'थ' to "त", 'द' to "त", 'ध' to "त",
            'फ' to "प", 'ब' to "प", 'भ' to "प",
            'श' to "ष",
            'ऋ' to "रु", 'ृ' to "्रु",
        )

        private val TAMIL_NASAL_BEFORE: Map<Char?, Char> = mapOf(
            'क' to 'ङ', 'च' to 'ञ', 'ट' to 'ण',
            'त' to 'न', 'प' to 'म', 'म' to 'म',
        )

        private val MALAYALAM_CHILLU: Map<Char, Char> = mapOf(
            'ण' to 'ൺ', 'न' to 'ൻ', 'र' to 'ർ',
            'ल' to 'ൽ', 'ळ' to 'ൾ',
        )

        private val MALAYALAM_BACK: Map<Char, String> = mapOf(
            'ൺ' to "ण्", 'ൻ' to "न्", 'ർ' to "र्",
            'ൽ' to "ल्", 'ൾ' to "ळ्", 'ൿ' to "क्",
            'ൎ' to "र्",
        )

        private val ORIYA_BACK: Map<Char, String> = mapOf(
            'ୟ' to "य", 'ୱ' to "व",
            'ଡ଼' to "ड़", 'ଢ଼' to "ढ़",
        )
    }
}
