package com.wasimaster.wmkeyboard.core.prediction

import kotlin.math.ln

/** Which script a buffer typed on a phonetic layout is committed in. */
enum class PhoneticScript { NATIVE, LATIN }

/**
 * Whether a buffer typed on a phonetic layout (Avro, Hindi phonetic) is a word
 * of the layout's language or an English word typed without switching away.
 *
 * The letters cannot say. `kemon` is only Bengali and `because` is only
 * English, and those are easy: whichever side knows the word has it. The rest
 * is the overlap, which is large, because a romanization is made of the same
 * letters English is — `to` is তো, `are` is আরে, `make` is মাকে — and because
 * the spelling maps list English words on purpose (`hello` → হ্যালো). What
 * settles those is what settles them for a person reading over the typist's
 * shoulder: which language the rest of the field is in, and failing that,
 * which of the two words anybody actually writes.
 *
 * Pure arithmetic over evidence the engine gathers, so the rule can be read,
 * tested and tuned against the real word lists without an engine around it.
 */
object PhoneticScriptVerdict {

    /**
     * @param latinCommonness how common the buffer is as an English word, in
     *        [0, 1] (see [commonness]); null when English does not know it
     * @param nativeCommonness the same for the best reading the language's own
     *        lists have for it; null when only the raw rules produce anything
     * @param loanword the native reading is a spelling-map loanword entry: the
     *        buffer *is* the English word, and the native form is that word
     *        written in the other script
     * @param contextDelta which way the field leans, in [-1, 1]: positive when
     *        its words are English, negative when they are the layout's
     * @param choice what the user has overruled this spelling to before
     *        ([PhoneticScriptChoices]): positive for Latin, negative for native
     */
    class Evidence(
        val latinCommonness: Double?,
        val nativeCommonness: Double?,
        val loanword: Boolean = false,
        val contextDelta: Double = 0.0,
        val choice: Int = 0,
    )

    /**
     * @param script the script a space commits the buffer in
     * @param contested whether the other script was a real possibility. Only a
     *        contested commit is worth an undo: flipping `because` to what the
     *        rules make of it would be offering rubbish.
     */
    class Verdict(val script: PhoneticScript, val contested: Boolean)

    fun decide(evidence: Evidence): Verdict {
        val latin = evidence.latinCommonness
        val native = evidence.nativeCommonness
        if (evidence.choice >= PhoneticScriptChoices.FIXED) return Verdict(PhoneticScript.LATIN, contested = true)
        if (evidence.choice <= -PhoneticScriptChoices.FIXED) {
            return Verdict(PhoneticScript.NATIVE, contested = latin != null)
        }
        return when {
            // Nobody knows it: a name, a typo, a word neither list has. The
            // layout's own script, which is what the layout is for — unless the
            // field is plainly being written in English, where the unknown word
            // is far likelier a name in an English sentence.
            latin == null && native == null -> {
                val score = W_CONTEXT * evidence.contextDelta + CHOICE_STEP * evidence.choice
                if (score >= UNKNOWN_LATIN_BAR) {
                    Verdict(PhoneticScript.LATIN, contested = true)
                } else {
                    Verdict(PhoneticScript.NATIVE, contested = false)
                }
            }
            // English alone has it. A loanword entry is the one case with
            // something real on the other side — কিবোর্ড is a spelling people
            // use — so that one can still be flipped, and remembered.
            native == null -> Verdict(PhoneticScript.LATIN, contested = evidence.loanword)
            latin == null -> Verdict(PhoneticScript.NATIVE, contested = false)
            else -> {
                val prior = if (evidence.loanword) LOANWORD_PRIOR else NATIVE_PRIOR
                val score = W_COMMON * (latin - native) + W_CONTEXT * evidence.contextDelta +
                    CHOICE_STEP * evidence.choice - prior
                Verdict(if (score > 0) PhoneticScript.LATIN else PhoneticScript.NATIVE, contested = true)
            }
        }
    }

    /**
     * A frequency on the scale of its own list, in [0, 1], so two lists
     * counted differently can be compared. Log because both lists are Zipfian
     * and the question is one of rank, not of raw count: `ln(1 + f) / ln(1 +
     * max)`. Then stretched from [COMMONNESS_FLOOR] up, because a word list is
     * a list of words somebody bothered to include — its rarest entry already
     * sits halfway up the log scale, and left alone that squeezes তো and অফ
     * into the same top third where no prior can tell them apart.
     */
    fun commonness(frequency: Int, maxFrequency: Int): Double {
        if (frequency <= 0 || maxFrequency <= 0) return 0.0
        val logShare = ln(1.0 + frequency) / ln(1.0 + maxFrequency)
        return ((logShare - COMMONNESS_FLOOR) / (1.0 - COMMONNESS_FLOOR)).coerceIn(0.0, 1.0)
    }

    /**
     * What a reading found only by the lenient fold is worth, against one the
     * rules or a listed spelling produce exactly. The fold forgives a great
     * deal on purpose (`for` reaches পর), which is right for offering a word
     * and wrong for claiming the typist meant it.
     */
    fun foldOnly(commonness: Double): Double = (commonness - FOLD_ONLY_DISCOUNT).coerceAtLeast(0.0)

    /**
     * Commonness credited to a listed romanization whose native form has no
     * frequency to read: a language running with no word list behind it
     * (Hindi before its download). Listed means somebody writes it, and that
     * is all that is known.
     */
    const val UNRANKED_LISTED = 0.3

    private const val COMMONNESS_FLOOR = 0.5

    private const val FOLD_ONLY_DISCOUNT = 0.2

    private const val W_COMMON = 1.0

    /**
     * What a field written wholly in one language is worth. More than
     * [NATIVE_PRIOR] and the widest commonness gap between two real words put
     * together, so a field that has made its mind up wins every collision.
     */
    private const val W_CONTEXT = 0.6

    /**
     * How much commoner the English word has to be before it takes a collision
     * in a field that says nothing. The layout was chosen to write the other
     * language, so a tie is not a tie.
     */
    private const val NATIVE_PRIOR = 0.35

    /**
     * The same for a loanword entry, and the other way round: the typist
     * spelled an English word in English letters, so English has it unless the
     * native spelling is clearly the commoner word or the field says otherwise.
     */
    private const val LOANWORD_PRIOR = -0.35

    /** One overruling, short of [PhoneticScriptChoices.FIXED]: a thumb on the scale. */
    private const val CHOICE_STEP = 0.2

    /**
     * How far the field has to lean English before an unknown word stays Latin.
     * Past what one English word is worth: a name after `hello` is still far
     * likelier the layout's own language than the second word of an English
     * sentence. Two in a row clear it.
     */
    private const val UNKNOWN_LATIN_BAR = 0.45
}
