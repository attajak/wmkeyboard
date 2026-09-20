package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.script.ScriptRegistry

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
 * one.
 *
 * "Where the caret sits" is read here from the text in front of it
 * ([startsSentence]) rather than from the shift key's own rule. The two are
 * not the same question. A field that never asks for sentence capitals — a
 * plain `inputType="text"`, a terminal's null field — leaves the shift key
 * alone, and so does the Automatic capitals setting when it is off; none of
 * that makes the empty field the caret is sitting in any less the start of a
 * sentence. Deciding it by the shift rule lowered the first word of a
 * dictation into an empty field in most apps on the device.
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
     * How much text before the caret [startsSentence] wants to see: a
     * terminator, whatever closed the quote or bracket in front of it, and
     * the space the last utterance left behind — `said."' ` is seven.
     */
    const val CONTEXT_CHARS = 8

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

    /**
     * Whether a phrase landing at the caret opens a sentence, judged from
     * [before] — the last [CONTEXT_CHARS] characters in front of it, or fewer
     * when the field holds fewer.
     *
     * Nothing in front of the caret is the plainest sentence start there is,
     * and so is a line of its own. Otherwise the last thing written has to
     * have ended a sentence: a full stop in whatever script wrote it, behind
     * whatever closed the quote or the bracket.
     */
    fun startsSentence(before: CharSequence?): Boolean {
        if (before.isNullOrEmpty()) return true
        val blanks = before.takeLastWhile { it.isWhitespace() }
        // A phrase on a line of its own starts a sentence whatever ended the
        // line above it.
        if (blanks.any { it == '\n' || it == '\r' }) return true
        var i = before.length - blanks.length - 1
        // Nothing but blanks in front of the caret: the field starts here.
        if (i < 0) return true
        while (i >= 0 && before[i] in CLOSERS) i--
        return i >= 0 && before[i] in TERMINATORS
    }

    /** "I", or a contraction of it: I'm, I'll, I've, I'd, with either apostrophe. */
    private fun isPronounI(word: String): Boolean =
        word == "I" || (word.length > 1 && word[0] == 'I' && (word[1] == '\'' || word[1] == '’'))

    /**
     * What ends a sentence: every script's own full stop — `।` for Bengali,
     * `。` for Japanese, `։` for Armenian — taken from where they are already
     * declared for the key beside the spacebar, plus the marks no script
     * calls its own.
     */
    private val TERMINATORS: Set<Char> = buildSet {
        ScriptRegistry.all.forEach { def -> def.fullStop.lastOrNull()?.let { add(it) } }
        addAll(listOf('!', '?', '…', '！', '？'))
    }

    /** Closes a quote or a bracket, and so can stand between the terminator and the caret. */
    private val CLOSERS = setOf('"', '\'', '”', '’', '»', '›', ')', ']', '}')
}
