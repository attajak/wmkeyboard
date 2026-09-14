package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.gesture.GlideCase

/**
 * What a lifted glide asks the service to do with the stroke's last word.
 *
 * The ambiguity picker's answer, carried on the existing glide callbacks in
 * place of the old `chosen: String?` — a type change rather than a new
 * [KeyboardScreen] parameter, because `ServiceKeyboardContent` sits against
 * the JVM's 64K method ceiling (see `DeleteSwipeCallbacks` for the same
 * constraint). Three answers rather than a nullable word because the picker
 * has a third thing to say: the finger was dragged down out of it, and a
 * lift there should type nothing at all.
 *
 * [cases] ride along here for the same reason: a stroke that drew through
 * the shift key is asking for capitals (#115, #163), and that is one more
 * thing the lift has to say about the words rather than one more callback.
 */
sealed interface GlideVerdict {

    /**
     * What each word segment's crossings of the shift key asked for, in
     * segment order; a single glide has one. Empty for an ordinary stroke.
     * Under the whole-word reading a stroke's crossings are counted together
     * and carried on the first segment, which is the only word a held shift
     * ever reached either.
     */
    val cases: List<GlideCase> get() = emptyList()

    /**
     * No choice was made — the picker never opened, or the finger lifted off
     * it — so the decoder's own first word commits, exactly as a glide with no
     * picker would.
     */
    data class Leader(override val cases: List<GlideCase> = emptyList()) : GlideVerdict

    /**
     * The finger lifted on a picker target. Committed in place of the
     * decoder's first choice and otherwise treated identically: still learned,
     * still spaced, still revertible, still first on the strip.
     */
    data class Word(val word: String, override val cases: List<GlideCase> = emptyList()) : GlideVerdict

    /**
     * The finger lifted in the picker's cancel zone. Nothing commits, nothing
     * is learned; the service only retires the stroke's previews.
     */
    data object Cancel : GlideVerdict
}

/** What segment [index]'s crossings asked for; [GlideCase.None] past the end. */
fun GlideVerdict.caseAt(index: Int): GlideCase = cases.getOrNull(index) ?: GlideCase.None

/**
 * This verdict with [cases] recorded against it, one per segment.
 *
 * A cancelled stroke keeps none: it types nothing, so there is nothing to
 * capitalize. A list with nothing to say returns the verdict untouched, which
 * is every ordinary glide and the reason this allocates nothing in the common
 * case.
 */
fun GlideVerdict.withCases(cases: List<GlideCase>): GlideVerdict = when {
    cases.all { it == GlideCase.None } -> this
    this is GlideVerdict.Leader -> GlideVerdict.Leader(cases)
    this is GlideVerdict.Word -> GlideVerdict.Word(word, cases)
    else -> this
}
