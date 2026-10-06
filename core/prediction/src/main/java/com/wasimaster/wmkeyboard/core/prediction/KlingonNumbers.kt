package com.wasimaster.wmkeyboard.core.prediction

/**
 * A number written out in Klingon, for the strip to offer after digits typed
 * on a Klingon layout: `42` → `loSmaH cha'`, `2026` → `cha'SaD cha'maH jav`.
 *
 * Klingon counts in tens. Each non-zero digit is said as its word with the
 * word for its place joined on (`loS` four, `maH` ten: `loSmaH` forty), and
 * the places are said largest first with a space between, ones last and bare.
 * Zero places are skipped, and nought on its own is `pagh`.
 */
object KlingonNumbers {

    private val DIGITS = listOf("pagh", "wa'", "cha'", "wej", "loS", "vagh", "jav", "Soch", "chorgh", "Hut")

    /** The places from tens up: ten, hundred, thousand, ten thousand, hundred thousand, million. */
    private val PLACES = listOf("maH", "vatlh", "SaD", "netlh", "bIp", "'uy'")

    /**
     * [digits] in words, or null for anything this does not read: not all
     * ASCII digits, a leading zero (a code or a PIN rather than a number), or
     * more places than Klingon has words for.
     */
    fun spell(digits: String): String? {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        if (digits == "0") return DIGITS[0]
        if (digits[0] == '0' || digits.length > PLACES.size + 1) return null
        val words = ArrayList<String>(digits.length)
        for ((i, c) in digits.withIndex()) {
            val digit = c - '0'
            if (digit == 0) continue
            val place = digits.length - 1 - i
            words.add(if (place == 0) DIGITS[digit] else DIGITS[digit] + PLACES[place - 1])
        }
        return words.joinToString(" ")
    }
}
