package com.wasimaster.wmkeyboard.core.selection

import com.wasimaster.wmkeyboard.core.script.NumeralSystem

/**
 * Digits written in another script, read back as the ASCII ones.
 *
 * `mapDigits` in `Numerals.kt` only goes the other way: it localises what the
 * keyboard commits. This is the inverse, for a selection that arrived in
 * Bengali, Devanagari or Arabic-Indic figures and is wanted as `0-9`. The
 * table is every [NumeralSystem] the keyboard can type, with `Character.digit`
 * behind it for any other Unicode digit (fullwidth, Gujarati, Tamil) so the
 * chip never leaves a figure it recognised half converted.
 */
object DigitScripts {

    /** Local glyph → ASCII digit, for every numeral system the keyboard knows. */
    private val ascii: Map<Char, Char> = buildMap {
        for (system in NumeralSystem.entries) {
            val digits = system.digits ?: continue
            digits.forEachIndexed { index, glyph -> put(glyph, '0' + index) }
        }
    }

    /**
     * The lowest code point of any non-ASCII digit (Arabic-Indic zero). Every
     * character below it is answered without a map lookup, which is what
     * keeps the scan over a 4,000-character selection cheap.
     */
    private const val FIRST_FOREIGN = 0x0660

    /** The value of [c] as a digit in any script, or -1. */
    fun digitValue(c: Char): Int {
        if (c in '0'..'9') return c - '0'
        if (c.code < FIRST_FOREIGN) return -1
        ascii[c]?.let { return it - '0' }
        return if (Character.isDigit(c)) Character.digit(c, 10) else -1
    }

    /** Whether [text] holds a digit that is not already `0-9`. */
    fun hasForeignDigits(text: CharSequence): Boolean =
        text.any { it.code >= FIRST_FOREIGN && digitValue(it) >= 0 }

    /** [text] with every foreign digit replaced by its ASCII value, or null when there is none. */
    fun toAsciiDigits(text: String): String? {
        if (!hasForeignDigits(text)) return null
        val out = StringBuilder(text.length)
        for (c in text) {
            val value = if (c.code >= FIRST_FOREIGN) digitValue(c) else -1
            out.append(if (value >= 0) '0' + value else c)
        }
        return out.toString()
    }
}
