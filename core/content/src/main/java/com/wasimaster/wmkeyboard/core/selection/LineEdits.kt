package com.wasimaster.wmkeyboard.core.selection

/**
 * Line-wise rewrites for a selection that spans several lines: sort, drop
 * repeats, number, bullet.
 *
 * Every function answers null when it would change nothing, so the caller can
 * leave the chip off rather than draw a no-op. Numbering and bulleting are
 * toggles: a list that already carries the prefix on every line loses it, and
 * either prefix converts into the other, so a bulleted list becomes numbered
 * in one tap and a half-numbered one is renumbered. The original line ending
 * and a trailing newline both survive, and a line's own indentation stays in
 * front of the prefix.
 */
object LineEdits {

    private val NUMBER_PREFIX = Regex("""^(\s*)\d+[.)]\s+""")
    private val BULLET_PREFIX = Regex("""^(\s*)[•\-*–]\s+""")

    /**
     * Ascending in natural order (digit runs compared as numbers, case
     * ignored); a list that is already ascending turns descending instead, so
     * a second tap is the other direction rather than nothing.
     */
    fun sort(text: String): String? = edit(text) { lines ->
        val ascending = lines.sortedWith(NATURAL)
        if (ascending == lines) lines.sortedWith(NATURAL.reversed()) else ascending
    }

    /** Keeps the first of every repeated line. Blank lines are never repeats. */
    fun dedupe(text: String): String? = edit(text) { lines ->
        val seen = HashSet<String>()
        lines.filter { line -> line.isBlank() || seen.add(line) }
    }

    /** `1. `, `2. `, … on every non-blank line; strips them when every line already has one. */
    fun number(text: String): String? = prefix(text, NUMBER_PREFIX) { index -> "${index + 1}. " }

    /** `• ` on every non-blank line; strips it when every line already has one. */
    fun bullet(text: String): String? = prefix(text, BULLET_PREFIX) { "• " }

    private fun prefix(text: String, own: Regex, label: (Int) -> String): String? = edit(text) { lines ->
        val content = lines.filter { it.isNotBlank() }
        val allPrefixed = content.isNotEmpty() && content.all { own.containsMatchIn(it) }
        var index = 0
        lines.map { line ->
            if (line.isBlank()) return@map line
            val stripped = strip(line)
            if (allPrefixed) stripped.indent + stripped.body else stripped.indent + label(index++) + stripped.body
        }
    }

    private class Stripped(val indent: String, val body: String)

    /** [line] without either kind of prefix, its indentation kept apart. */
    private fun strip(line: String): Stripped {
        for (regex in listOf(NUMBER_PREFIX, BULLET_PREFIX)) {
            val match = regex.find(line) ?: continue
            return Stripped(match.groupValues[1], line.substring(match.range.last + 1))
        }
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        return Stripped(indent, line.substring(indent.length))
    }

    /**
     * Splits, rewrites, joins. Null when there are fewer than two lines or
     * the rewrite changed nothing.
     */
    private fun edit(text: String, rewrite: (List<String>) -> List<String>): String? {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val trailing = text.endsWith("\n")
        val body = if (trailing) text.substring(0, text.length - newline.length) else text
        val lines = body.split("\r\n", "\n")
        if (lines.size < 2) return null
        val result = rewrite(lines).joinToString(newline) + if (trailing) newline else ""
        return result.takeIf { it != text }
    }

    /**
     * Natural order: `item2` before `item10`, case ignored, with a
     * case-sensitive tiebreak so the order is total.
     */
    private val NATURAL = Comparator<String> { a, b ->
        val byNature = compareNatural(a, b)
        if (byNature != 0) byNature else a.compareTo(b)
    }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                val byLength = na.length.compareTo(nb.length)
                if (byLength != 0) return byLength
                val byValue = na.compareTo(nb)
                if (byValue != 0) return byValue
                i = ei
                j = ej
            } else {
                val ca = a[i].lowercaseChar()
                val cb = b[j].lowercaseChar()
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}
