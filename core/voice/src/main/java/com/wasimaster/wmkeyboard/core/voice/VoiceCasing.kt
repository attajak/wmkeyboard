package com.wasimaster.wmkeyboard.core.voice

/**
 * The capital the recognizer puts on every utterance, judged against where
 * the words are going.
 *
 * A recognizer formats each utterance on its own: it cannot see the field, so
 * the first word of every phrase arrives capitalized as if it opened a
 * sentence. Under an open microphone a pause is not a full stop, and the
 * phrase after it lands mid-sentence, after a comma, or after the word the
 * user just typed by hand (issue #182). The keyboard knows what the caret
 * follows, so it takes that capital back when the place does not call for
 * one, the same way it would not shift for a typed word there.
 *
 * Only the recognizer's *automatic* capital is in question, which is the one
 * on the first word alone. A word that is a capital in its own right is left
 * as it is: the pronoun I and its contractions, and anything with a second
 * capital inside it (NASA, McKinsey, iPhone is never the case since its first
 * letter is small). A name at the start of a phrase cannot be told from a
 * sentence capital and goes with the rule; the shift key is the fix, as it is
 * for a typed name.
 */
object VoiceCasing {

    /**
     * [text] with its opening capital lowered unless [sentenceStart] says the
     * caret sits where a sentence begins, in which case it stays.
     */
    fun apply(text: String, sentenceStart: Boolean): String {
        if (sentenceStart || text.isEmpty()) return text
        val first = text[0]
        if (!first.isUpperCase()) return text
        val end = text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
        val word = text.substring(0, end)
        if (isPronounI(word)) return text
        // A second capital means the word is spelled that way, not opened
        // that way.
        if (word.drop(1).any { it.isUpperCase() }) return text
        return first.lowercaseChar() + text.substring(1)
    }

    /** "I", or a contraction of it: I'm, I'll, I've, I'd, with either apostrophe. */
    private fun isPronounI(word: String): Boolean =
        word == "I" || (word.length > 1 && word[0] == 'I' && (word[1] == '\'' || word[1] == '’'))
}
