package com.wasimaster.wmkeyboard.core.ocr

/**
 * How Tesseract turns a photo black and white before it reads it, and how
 * the scanner picks between the two reads it makes.
 *
 * Tesseract's default is one Otsu threshold for the whole image. Phone
 * photos are never evenly lit: vignetting, a shadow or the torch's hot spot
 * push part of the frame across that one threshold, and the whole region,
 * text and all, turns black. A sharp photo of print then reads as no text.
 * Sauvola sets the threshold from each pixel's neighbourhood and reads those
 * photos, but it assumes dark text on a light ground, and reads light text on
 * a dark screen as nothing. So the scanner reads with both and keeps the read
 * with more text in it ([score]).
 */
object OcrThresholding {

    /** Tesseract's `thresholding_method` values. */
    enum class Method(val tesseractValue: Int) {
        OTSU(0),
        SAUVOLA(2),
    }

    /** The reads to make, in order. A tie keeps the earlier one. */
    val passes: List<Method> = listOf(Method.SAUVOLA, Method.OTSU)

    /**
     * How much real text [text] holds: the letters, digits and marks of every
     * word of at least two of them that is mostly made of them. Stray marks a
     * poor threshold leaves behind ("|", "—", a lone "e") count for nothing,
     * so a read full of noise does not beat a clean one.
     */
    fun score(text: String): Int {
        var total = 0
        for (word in text.split(WHITESPACE)) {
            if (word.isEmpty()) continue
            var core = 0
            var all = 0
            var i = 0
            while (i < word.length) {
                val cp = word.codePointAt(i)
                all++
                if (isWordChar(cp)) core++
                i += Character.charCount(cp)
            }
            if (core >= 2 && core * 10 >= all * 6) total += core
        }
        return total
    }

    /**
     * Reads with every pass and returns the text of the one with the highest
     * [score]. When no read scores at all, the first that is not blank (a
     * lone digit scores nothing but is still what the photo said), else "".
     * A failed read (an exception from [read]) fails the whole call: a broken
     * engine is an error to show, not a photo without text.
     */
    suspend fun bestRead(read: suspend (Method) -> String): String {
        var best: String? = null
        var bestScore = 0
        var firstNonBlank: String? = null
        for (method in passes) {
            val text = read(method)
            if (firstNonBlank == null && text.isNotBlank()) firstNonBlank = text
            val score = score(text)
            if (score > bestScore) {
                best = text
                bestScore = score
            }
        }
        return best ?: firstNonBlank ?: ""
    }

    // Letters, digits and combining marks: the vowel signs and viramas of
    // Indic scripts are marks, and a Bangla word without them is half a word.
    private fun isWordChar(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(), Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }

    private val WHITESPACE = Regex("\\s+")
}
