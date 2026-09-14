package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Bengali grapheme-cluster helpers for editing.
 *
 * Backspace normally removes one code unit, which visually shatters a
 * conjunct (জ্ঞ → জ্ + delete → জ). With the conjunct-aware option the
 * whole cluster — consonant(s) joined by hasant, plus any vowel sign and
 * modifiers — is removed as a single unit.
 */
object BengaliGraphemes {

    private const val HASANT = '্'
    private const val ZWJ = '‍'
    private const val ZWNJ = '‌'
    private const val NUKTA = '়'

    /** Bengali vowel sign (kar) → independent vowel letter. */
    val KAR_TO_VOWEL: Map<Char, String> = mapOf(
        'া' to "আ", 'ি' to "ই", 'ী' to "ঈ", 'ু' to "উ", 'ূ' to "ঊ",
        'ৃ' to "ঋ", 'ে' to "এ", 'ৈ' to "ঐ", 'ো' to "ও", 'ৌ' to "ঔ",
    )

    /**
     * Kar → য়-glide form used after another vowel, where a bare আ or এ is
     * invalid: কা + আ must give কায়া (never কাআ), দি + এ gives দিয়ে. Only
     * া and ে take the glide; the other vowels are written independently
     * after a vowel (খাই, বউ, খাও).
     */
    val KAR_TO_GLIDE: Map<Char, String> = mapOf(
        'া' to "\u09DFা", 'ে' to "\u09DFে",
    )

    /** True for characters a kar can attach to: consonants, nukta, hasant. */
    fun karAttachesTo(c: Char): Boolean =
        c in 'ক'..'হ' || // consonants ক..হ
            c == '\u09DC' || c == '\u09DD' || c == '\u09DF' || // ড় ঢ় য়
            c == NUKTA || c == HASANT

    /** What a vowel key should produce given the character before the cursor. */
    enum class VowelKeyForm { INDEPENDENT, KAR, GLIDE }

    /**
     * Chooses the vowel-key form from the preceding character: kar after
     * anything it can attach to, the য়-glide after a vowel (sign or
     * independent, including trailing modifiers like ঁ), and the
     * independent letter at a word start or after non-Bengali text.
     */
    fun vowelFormAfter(previous: Char?): VowelKeyForm = when {
        previous == null -> VowelKeyForm.INDEPENDENT
        karAttachesTo(previous) -> VowelKeyForm.KAR
        isVowelSign(previous) || previous in 'অ'..'ঔ' || isModifier(previous) ->
            VowelKeyForm.GLIDE
        else -> VowelKeyForm.INDEPENDENT
    }

    /**
     * Output of a vowel key whose base (kar) form is [kar], in the given
     * [form]. Falls back to the independent letter for vowels that have no
     * glide form (ই, উ, ও … stay independent after a vowel: খাই, বউ, খাও).
     */
    fun vowelKeyText(kar: Char, form: VowelKeyForm): String? {
        val independent = KAR_TO_VOWEL[kar] ?: return null
        return when (form) {
            VowelKeyForm.KAR -> kar.toString()
            VowelKeyForm.GLIDE -> KAR_TO_GLIDE[kar] ?: independent
            VowelKeyForm.INDEPENDENT -> independent
        }
    }

    /** Anything in the Bengali block: letters, signs, digits and punctuation. */
    fun isBengali(c: Char): Boolean = c.code in 0x0980..0x09FF

    private fun isConsonant(c: Char) =
        c.code in 0x0995..0x09B9 || c.code in 0x09DC..0x09DF || c == 'ৎ' || c == 'ৰ' || c == 'ৱ'

    private fun isVowelSign(c: Char) =
        c.code in 0x09BE..0x09C4 || c.code in 0x09C7..0x09C8 ||
            c.code in 0x09CB..0x09CC || c == 'ৗ' || c.code in 0x09E2..0x09E3

    /** Candrabindu, anusvara, visarga. */
    private fun isModifier(c: Char) = c.code in 0x0981..0x0983

    /**
     * How many chars to remove from the end of [text] to delete the last
     * Bengali cluster as one unit. Returns 0 when [text] is empty; falls
     * back to 1 (or 2 for a surrogate pair) for non-Bengali endings.
     */
    fun clusterDeleteLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        var i = text.length - 1

        val last = text[i]
        if (!isBengali(last) && last != ZWJ && last != ZWNJ) {
            return if (text.length >= 2 && Character.isSurrogatePair(text[i - 1], last)) 2 else 1
        }
        // A bare trailing hasant deletes alone: typing an explicit hasant
        // then backspace should undo just the hasant.
        if (last == HASANT) return 1

        // Trailing modifiers, vowel signs, nukta and joiners.
        while (i >= 0 && (isVowelSign(text[i]) || isModifier(text[i]) ||
                text[i] == NUKTA || text[i] == ZWJ || text[i] == ZWNJ)
        ) i--
        if (i < 0) return text.length

        if (!isConsonant(text[i])) {
            // Independent vowel, digit or sign: it plus any trailing marks.
            return text.length - i
        }
        i-- // the base consonant

        // Walk back over (consonant + hasant [+ joiner] [+ nukta]) pairs.
        while (i >= 0) {
            var j = i
            if (text[j] == ZWJ || text[j] == ZWNJ) j--
            if (j < 0 || text[j] != HASANT) break
            j--
            if (j >= 0 && text[j] == NUKTA) j--
            if (j < 0 || !isConsonant(text[j])) break
            i = j - 1
        }
        return text.length - (i + 1)
    }
}
