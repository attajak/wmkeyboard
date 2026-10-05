package com.wasimaster.wmkeyboard.core.tools

import java.security.SecureRandom
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Password and passphrase generation for the generator tool. All entropy
 * comes from [SecureRandom]; nothing is stored or logged. Passphrase words
 * come from the bundled English dictionary (loaded once, filtered to short
 * common words) so no extra wordlist ships with the app.
 */
object PasswordGen {

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/~"
    private const val AMBIGUOUS = "Il1O0o5S8B|`'\"{}[]()"

    data class PasswordSpec(
        val length: Int = 16,
        val upper: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val excludeAmbiguous: Boolean = false,
        /**
         * The symbol pool to draw from; blank uses the built-in set.
         *
         * Sites that reject particular punctuation used to mean generating
         * again and again until one came out clean, because the pool was fixed.
         */
        val symbolPool: String = "",
    )

    data class PassphraseSpec(
        val words: Int = 4,
        val separator: String = "-",
        val capitalize: Boolean = false,
        val includeDigit: Boolean = false,
    )

    fun password(spec: PasswordSpec, random: SecureRandom = SecureRandom()): String {
        fun pool(chars: String) =
            if (spec.excludeAmbiguous) chars.filterNot { it in AMBIGUOUS } else chars
        val pools = buildList {
            add(pool(LOWER))
            if (spec.upper) add(pool(UPPER))
            if (spec.digits) add(pool(DIGITS))
            if (spec.symbols) add(pool(spec.symbolPool.ifBlank { SYMBOLS }))
        }.filter { it.isNotEmpty() }
        val all = pools.joinToString("")
        val length = spec.length.coerceIn(4, 128)
        // One character from every enabled class, the rest uniform, then a
        // Fisher-Yates shuffle so the guaranteed ones aren't front-loaded.
        val chars = buildList {
            pools.forEach { add(it[random.nextInt(it.length)]) }
            repeat(length - size) { add(all[random.nextInt(all.length)]) }
        }.toMutableList()
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return chars.take(length).joinToString("")
    }

    fun passphrase(
        wordlist: List<String>,
        spec: PassphraseSpec,
        random: SecureRandom = SecureRandom(),
    ): String {
        if (wordlist.isEmpty()) return ""
        val count = spec.words.coerceIn(2, 12)
        val words = List(count) { wordlist[random.nextInt(wordlist.size)] }
            .map { if (spec.capitalize) it.replaceFirstChar(Char::uppercase) else it }
            .toMutableList()
        if (spec.includeDigit) {
            val at = random.nextInt(words.size)
            words[at] = words[at] + random.nextInt(10)
        }
        return words.joinToString(spec.separator)
    }

    /** Rough strength estimate in bits, for the panel's meter. */
    fun passwordEntropyBits(spec: PasswordSpec): Int {
        var poolSize = LOWER.length
        if (spec.upper) poolSize += UPPER.length
        if (spec.digits) poolSize += DIGITS.length
        if (spec.symbols) poolSize += spec.symbolPool.ifBlank { SYMBOLS }.length
        if (spec.excludeAmbiguous) poolSize -= 8
        return (spec.length.coerceIn(4, 128) * ln(poolSize.toDouble()) / ln(2.0)).roundToInt()
    }

    fun passphraseEntropyBits(spec: PassphraseSpec, wordlistSize: Int): Int {
        if (wordlistSize <= 1) return 0
        var bits = spec.words.coerceIn(2, 12) * ln(wordlistSize.toDouble()) / ln(2.0)
        if (spec.includeDigit) bits += ln(10.0 * spec.words) / ln(2.0)
        return bits.roundToInt()
    }

    /** The default size of a built wordlist. */
    const val WORDLIST_LIMIT = 4096

    /**
     * Whether [word] is passphrase material: short, unambiguous, all lowercase
     * letters.
     *
     * Named rather than inlined into [buildWordlist] because the caller that
     * builds the list from a trie hands it to the walk as its filter, and the
     * two must agree on what a passphrase word is.
     */
    fun acceptsWord(word: String): Boolean =
        word.length in 4..8 && word.all { c -> c in 'a'..'z' }

    /**
     * Filters a dictionary's words down to passphrase material: common,
     * short, unambiguous, all-lowercase-letter words.
     */
    fun buildWordlist(words: Sequence<String>, limit: Int = WORDLIST_LIMIT): List<String> =
        words
            .filter(::acceptsWord)
            .distinct()
            .take(limit)
            .toList()
}
