package com.wasimaster.wmkeyboard.core.script

/**
 * The space some languages keep in *front* of a punctuation mark, and the
 * decision of whether one belongs in front of a mark about to land.
 *
 * French is the language this exists for: `!`, `?`, `;` and `:` are "double"
 * marks there and take a space on both sides — "Bonjour !", "Quoi ?", "voici :".
 * Every other rule in the keyboard points the other way (a mark hugs the word
 * in front of it), so without this a French sentence comes out wrong however
 * the user types it: a hand-typed space is pulled back out by the
 * "remove a space before punctuation" rule, and the keyboard's own auto-space
 * — after a glided word, a strip pick, or another mark — is swallowed by the
 * mark whether that rule is on or not. That second path is why the space
 * survived sometimes and vanished other times (#215).
 *
 * [SPACE] is a no-break space rather than a plain one so the mark can never
 * wrap to the next line on its own, which is the whole point of the
 * typographic rule. It is U+00A0 and not the narrow U+202F that French
 * typesetting prefers because a phone types into somebody else's text field:
 * U+00A0 is the one every font draws, every host measures, and no web form
 * mangles, and it is what iOS commits for French too.
 */
object SpacedPunctuation {

    /** The space that goes in front of a spaced mark: no-break, so the mark never wraps alone. */
    const val SPACE = '\u00A0'

    /**
     * The marks the French typographic standard keeps a space in front of.
     *
     * Named for the convention rather than the language because French is not
     * the only language that writes by it: Breton, Occitan, Walloon, Picard,
     * Norman and Franco-Provençal are all written under French orthographic
     * norms and space these marks the same way. Haitian Creole is the
     * near miss worth naming — French-lexified, but its 1979 orthography
     * spaces punctuation the English way, so it is deliberately not here.
     */
    const val FRENCH_STYLE = "!?;:»"

    /**
     * The marks the same standard keeps a space *after* rather than in front of:
     * the opening guillemet, which is the other half of the » above. French
     * quotes as « oui », with a space on the inside of both.
     *
     * Its own list because the keyboard already knows « as a mark a word goes
     * straight up against (WORD_OPENERS, in the IME's glide spacing), which is
     * right for Spanish ¿Qué and wrong here. Rather than teach that set about
     * languages, the space is typed together with the mark: once it is in the
     * field there is no bare opener left for anything to hug.
     */
    const val FRENCH_STYLE_OPENERS = "«"

    /**
     * What may stand in front of a spaced mark and still earn the space: a word,
     * a number, or something that closes one. The same set the hug rule reads,
     * plus the closing guillemet, which ends a quotation in exactly these
     * languages.
     */
    private const val CLOSERS = ")]}\"'”’»"

    /**
     * The space characters a run in front of the mark may be made of. A line
     * break is deliberately absent: a mark at the start of a line is whatever
     * the user meant, not a spacing slip.
     */
    private fun Char.isRunSpace(): Boolean =
        this == ' ' || this == SPACE || this == '\u202F' || this == '\u2009'

    /**
     * How many characters to take out from in front of the caret before [SPACE]
     * and [mark] land, or null to leave the field completely alone.
     *
     * 0 is a real answer — "there is nothing in front of the mark, insert the
     * space" — and is why this returns a nullable Int rather than a count with
     * 0 meaning no. null covers all three ways the rule does not apply: [mark]
     * is not one of [marks]; the mark does not follow a word (start of a line,
     * after a line break, after another mark — see [CLOSERS]); or the space is
     * already exactly right, which keeps a second read of the same text from
     * churning the field and arming an undo for a change that never happened.
     *
     * [before] is the text behind the caret. A run of spaces that fills the
     * whole of it may continue past what was read, so that read is declined
     * rather than guessed at — the same caution `straySpacesBefore` takes.
     */
    fun spacesToReplace(before: CharSequence, mark: Char, marks: String): Int? {
        if (marks.isEmpty() || mark !in marks) return null
        var i = before.length
        var run = 0
        while (i > 0 && before[i - 1].isRunSpace()) {
            run++
            i--
        }
        if (i == 0) return null
        val prev = before[i - 1]
        if (!prev.isLetterOrDigit() && prev !in CLOSERS) return null
        // A colon typed straight onto a digit carries a number on past it —
        // "5:00", "16:9" — and a space there splits the number in two, the
        // same trap the space *after* a mark had to learn (#206). Only when
        // nothing stands between them: "il en reste 3 :" is a clause mark that
        // happens to follow a number, and it keeps its space.
        if (mark == ':' && run == 0 && prev.isDigit()) return null
        if (run == 1 && before[before.length - 1] == SPACE) return null
        return run
    }

    /** How many characters of the field the rule reads; longer than any honest run of spaces. */
    const val LOOKBACK = 16
}
