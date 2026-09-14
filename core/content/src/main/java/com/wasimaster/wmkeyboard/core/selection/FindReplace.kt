package com.wasimaster.wmkeyboard.core.selection

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** How a find reads its query. */
data class FindOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/** One splice: the text between [start] and [end] becomes [text]. */
data class TextEdit(val start: Int, val end: Int, val text: String)

sealed interface FindResult {
    /** Every match in document order; [truncated] when the count stopped at [FindReplace.MAX_MATCHES]. */
    data class Matches(val ranges: List<IntRange>, val truncated: Boolean) : FindResult

    /** The query is not a valid pattern. [reason] is the engine's own message. */
    data class BadPattern(val reason: String) : FindResult

    /** The pattern ran past its time budget on this text. */
    data object TimedOut : FindResult
}

/**
 * The matcher behind the Find chip and the Find & replace panel: pure, so the
 * edge cases live in a unit test and the service only chooses what to select.
 *
 * A plain query is quoted and matched without regard to case; a whole word is
 * bounded by letters, digits and the underscore in any script, which is what
 * lets a Bengali word be a whole word where `\b` would not. Regex mode hands
 * the query to the engine as written, under a time budget: Java's regex has no
 * timeout, so the input is wrapped in a sequence that watches the clock and
 * aborts a pattern that goes exponential.
 */
object FindReplace {

    const val MAX_MATCHES = 500
    const val DEFAULT_BUDGET_MS = 50L

    fun compile(query: String, options: FindOptions): Result<Pattern> {
        if (query.isEmpty()) return Result.failure(IllegalArgumentException("empty"))
        var source = if (options.regex) query else Pattern.quote(query)
        if (options.wholeWord) source = "(?<![\\p{L}\\p{N}_])(?:$source)(?![\\p{L}\\p{N}_])"
        var flags = Pattern.MULTILINE
        if (!options.matchCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return try {
            Result.success(Pattern.compile(source, flags))
        } catch (invalid: PatternSyntaxException) {
            Result.failure(invalid)
        } catch (invalid: IllegalArgumentException) {
            Result.failure(invalid)
        }
    }

    /** The user's own replacement, when the engine has no expansion for it. */
    private fun literal(replacement: String): String = replacement

    fun find(
        text: String,
        query: String,
        options: FindOptions,
        budgetMs: Long = DEFAULT_BUDGET_MS,
        clock: () -> Long = System::nanoTime,
    ): FindResult {
        if (query.isEmpty()) return FindResult.Matches(emptyList(), truncated = false)
        val pattern = compile(query, options).getOrElse { return FindResult.BadPattern(it.message.orEmpty()) }
        val watched = Watched(text, clock() + budgetMs * 1_000_000L, clock)
        val found = ArrayList<IntRange>()
        return try {
            val matcher = pattern.matcher(watched)
            while (matcher.find()) {
                if (matcher.end() > matcher.start()) found += matcher.start() until matcher.end()
                if (found.size >= MAX_MATCHES) return FindResult.Matches(found, truncated = true)
            }
            FindResult.Matches(found, truncated = false)
        } catch (ignored: BudgetExceeded) {
            // The budget itself is the result.
            FindResult.TimedOut
        } catch (ignored: StackOverflowError) {
            // A pattern deep enough to blow the stack is as good as one that hangs.
            FindResult.TimedOut
        }
    }

    /**
     * The first match at or after [from], wrapping to the start of [text] when
     * [wrap] and nothing follows. Null for no match at all, or when the only
     * match is the one that begins at [from].
     */
    fun next(text: String, query: String, options: FindOptions, from: Int, wrap: Boolean = true): IntRange? {
        val pattern = compile(query, options).getOrNull() ?: return null
        val matcher = pattern.matcher(text)
        val start = from.coerceIn(0, text.length)
        if (matcher.find(start) && matcher.end() > matcher.start()) return matcher.start() until matcher.end()
        if (!wrap) return null
        matcher.reset()
        if (matcher.find() && matcher.end() > matcher.start()) return matcher.start() until matcher.end()
        return null
    }

    /**
     * The match before [from], wrapping to the last one when [wrap] and none
     * precedes it.
     */
    fun previous(text: String, query: String, options: FindOptions, from: Int, wrap: Boolean = true): IntRange? {
        val pattern = compile(query, options).getOrNull() ?: return null
        val matcher = pattern.matcher(text)
        var before: IntRange? = null
        var last: IntRange? = null
        while (matcher.find()) {
            if (matcher.end() <= matcher.start()) continue
            val range = matcher.start() until matcher.end()
            if (range.first < from) before = range
            last = range
        }
        return before ?: if (wrap) last else null
    }

    /**
     * Replaces the one [range] with [replacement]. In regex mode `$1` names a
     * group, as Java's own replace does; a replacement naming a group the
     * pattern lacks is used as typed rather than failing.
     */
    fun replaceOne(text: String, range: IntRange, replacement: String, query: String, options: FindOptions): TextEdit {
        val pattern = if (options.regex) compile(query, options).getOrNull() else null
        return TextEdit(range.first, range.last + 1, expand(pattern, text, range, replacement))
    }

    /**
     * Every one of [ranges] replaced from the original text, as back-to-front
     * edits so each splice leaves the offsets before it untouched. Nothing a
     * replacement produces is matched again.
     */
    fun replaceAll(text: String, ranges: List<IntRange>, replacement: String, query: String, options: FindOptions): List<TextEdit> {
        val pattern = if (options.regex) compile(query, options).getOrNull() else null
        return ranges.sortedByDescending { it.first }.map { range ->
            TextEdit(range.first, range.last + 1, expand(pattern, text, range, replacement))
        }
    }

    /** [text] with back-to-front [edits] applied. */
    fun apply(text: String, edits: List<TextEdit>): String {
        var out = text
        for (edit in edits.sortedByDescending { it.start }) {
            out = out.substring(0, edit.start) + edit.text + out.substring(edit.end)
        }
        return out
    }

    private fun expand(pattern: Pattern?, text: String, range: IntRange, replacement: String): String {
        if (pattern == null) return replacement
        val matcher: Matcher = pattern.matcher(text)
        if (!matcher.find(range.first) || matcher.start() != range.first || matcher.end() != range.last + 1) return replacement
        return try {
            val buffer = StringBuffer()
            matcher.appendReplacement(buffer, replacement)
            buffer.substring(range.first)
        } catch (ignored: IllegalArgumentException) {
            // A group reference the pattern lacks: the replacement is used as typed.
            literal(replacement)
        } catch (ignored: IndexOutOfBoundsException) {
            literal(replacement)
        }
    }

    private class BudgetExceeded : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * The text as the regex engine reads it, checking the clock every so many
     * characters. A pattern that goes exponential reads characters without
     * end, so this is where it is stopped.
     */
    private class Watched(private val text: String, private val deadline: Long, private val clock: () -> Long) : CharSequence {
        private var reads = 0
        override val length: Int get() = text.length
        override fun get(index: Int): Char {
            if (++reads and CHECK_MASK == 0 && clock() > deadline) throw BudgetExceeded()
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)
        override fun toString(): String = text
    }

    private const val CHECK_MASK = 0xFF
}
