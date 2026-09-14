package com.wasimaster.wmkeyboard.core.selection

/**
 * The programmer's cases: camelCase, snake_case, kebab-case, CONSTANT_CASE.
 *
 * Each reads its input in any of the conventions, so `Hello World`,
 * `hello_world`, `helloWorld` and `HELLO_WORLD` all mean the same two words.
 * A run of capitals followed by a lower-case letter splits before that letter
 * (`XMLParser` is `xml`, `parser`), digits stay with the word before them
 * (`version2Update` is `version2`, `update`), an all-capitals token is one
 * word, apostrophes vanish (`don't` is `dont`) and letters without a case pass
 * through as they are. Several lines are converted one at a time, and every
 * function answers null when the result equals the input.
 */
object CodeCases {

    fun camel(text: String): String? = convert(text) { words ->
        words.mapIndexed { index, word ->
            if (index == 0) word else word.replaceFirstChar { it.uppercaseChar() }
        }.joinToString("")
    }

    fun snake(text: String): String? = convert(text) { it.joinToString("_") }

    fun kebab(text: String): String? = convert(text) { it.joinToString("-") }

    fun constant(text: String): String? = convert(text) { words -> words.joinToString("_") { it.uppercase() } }

    /** The lower-cased words of [text], read from whichever convention it is in. */
    fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val token = StringBuilder()
        fun flush() {
            if (token.isNotEmpty()) {
                out += token.toString().lowercase()
                token.setLength(0)
            }
        }
        val chars = text.filterNot { it == '\'' || it == '’' }
        for (i in chars.indices) {
            val c = chars[i]
            if (!c.isLetterOrDigit() && !isMark(c)) {
                flush()
                continue
            }
            if (token.isNotEmpty()) {
                val prev = chars[i - 1]
                val next = chars.getOrNull(i + 1)
                val lowerToUpper = c.isUpperCase() && (prev.isLowerCase() || prev.isDigit())
                val capitalsThenWord = c.isUpperCase() && prev.isUpperCase() && next?.isLowerCase() == true
                if (lowerToUpper || capitalsThenWord) flush()
            }
            token.append(c)
        }
        flush()
        return out
    }

    /** A vowel sign or other combining mark: part of the letter before it, not a boundary. */
    private fun isMark(c: Char): Boolean {
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun convert(text: String, join: (List<String>) -> String): String? {
        val result = text.split("\n").joinToString("\n") { line ->
            val words = words(line)
            if (words.isEmpty()) line else join(words)
        }
        return result.takeIf { it != text }
    }
}
