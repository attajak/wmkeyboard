package com.wasimaster.wmkeyboard.core.voice

/**
 * The rules around an AI rewrite of a dictated phrase (#499): which phrases
 * are worth sending, which answers are taken, and how an accepted answer
 * takes the place of what the recognizer wrote.
 *
 * The rewrite itself is the AI tool's provider, asked by the service. What
 * lives here is everything that must hold whatever that model says: a model
 * that answers the dictation instead of cleaning it, wraps it in quotes, or
 * comes back with nothing, never gets into the field.
 */
object VoiceTidy {

    /**
     * Letters and digits a phrase needs before it is sent. A word or two has
     * no filler to take out and no correction to fold in, and every send holds
     * the next phrase back for as long as the model takes.
     */
    const val MIN_CHARS = 10

    /**
     * How much longer than the phrase an answer may be: a cleaned phrase only
     * gets shorter, give or take punctuation, so an answer this much longer is
     * the model replying to the words rather than tidying them.
     */
    private const val MAX_GROWTH_FACTOR = 1.5
    private const val MAX_GROWTH_SLACK = 24

    /** Quote pairs a model puts around an answer it was told to give bare. */
    private val QUOTES = listOf("\"" to "\"", "“" to "”", "'" to "'", "‘" to "’", "«" to "»", "「" to "」")

    /** Whether [phrase] is long enough to be worth a rewrite. See [MIN_CHARS]. */
    fun worthTidying(phrase: String): Boolean =
        phrase.count { it.isLetterOrDigit() } >= MIN_CHARS

    /**
     * The model's [answer] for [heard], ready to replace it, or null when it
     * is not to be used: blank, the same words, or grown past what a cleanup
     * can account for. Reasoning is already gone from [answer].
     */
    fun accept(answer: String, heard: String): String? {
        val core = heard.trim()
        var text = answer.trim()
        val wrapped = QUOTES.firstOrNull { (open, close) ->
            text.length > open.length + close.length &&
                text.startsWith(open) && text.endsWith(close) &&
                !(core.startsWith(open) && core.endsWith(close))
        }
        if (wrapped != null) text = text.substring(wrapped.first.length, text.length - wrapped.second.length).trim()
        if (text.isEmpty() || text == core) return null
        if (text.length > core.length * MAX_GROWTH_FACTOR + MAX_GROWTH_SLACK) return null
        return text
    }

    /**
     * [tidied] wearing the spaces [landed] was committed with, so the rewrite
     * sits exactly where the phrase did: the space in front of it that joined
     * it to the text before, and the one behind it that the next phrase or the
     * text after the caret expects.
     */
    fun land(landed: String, tidied: String): String =
        landed.takeWhile { it.isWhitespace() } + tidied.trim() + landed.takeLastWhile { it.isWhitespace() }
}
