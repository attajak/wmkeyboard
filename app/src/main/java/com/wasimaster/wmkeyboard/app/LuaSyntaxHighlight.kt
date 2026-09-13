package com.wasimaster.wmkeyboard.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaAnalysis
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaApiEntry
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaApiLookup
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaCompletion
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaCompletionItem
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaCompletionKind
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaDiagnostics
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaDocuments
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaFormat
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaHostShape
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaLexer
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaNavigation
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaTokenKind
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaTokens

/**
 * Lua, as the plugin editor needs it: coloured, re-indented and paired up.
 *
 * Everything here reads tokens from [LuaLexer], never a parse tree. A plugin
 * being typed is broken Lua most of the time, and colouring or bracket matching
 * that went dark whenever the file stopped parsing would be dark while the user
 * is actually typing.
 */
internal object LuaCode : CodeLanguage {

    private const val OPENERS = "({["
    private const val CLOSERS = ")}]"

    /**
     * The last document lexed. The editor asks for colours and for brackets of
     * the same string on every change, so the second question costs nothing.
     */
    @Volatile
    private var cached: LuaTokens? = null

    private fun tokensOf(source: String): LuaTokens {
        cached?.let { if (it.source === source) return it }
        return LuaLexer.lex(source).also { cached = it }
    }

    override val lineComment: String get() = "--"

    override val blockComment: Pair<String, String> get() = "--[[" to "]]"

    override val smartRules: CodeSmartRules get() = LuaSmartRules

    /**
     * One pass over the tokens. Operators and plain names carry no span, and
     * neighbouring tokens of one colour share a span across the space between
     * them, because the span count is what the text layout pays for.
     */
    override fun highlight(source: String, colors: CodeColors): AnnotatedString {
        val tokens = tokensOf(source)
        val styles = Styles(colors)
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        var pending: SpanStyle? = null
        var pendingStart = 0
        var pendingEnd = 0
        for (index in 0 until tokens.size) {
            val kind = tokens.kind(index)
            if (kind == LuaTokenKind.WHITESPACE) continue
            val style = styleOf(tokens, index, kind, styles)
            if (style != null && style === pending) {
                pendingEnd = tokens.end(index)
                continue
            }
            pending?.let { spans += AnnotatedString.Range(it, pendingStart, pendingEnd) }
            pending = style
            pendingStart = tokens.start(index)
            pendingEnd = tokens.end(index)
        }
        pending?.let { spans += AnnotatedString.Range(it, pendingStart, pendingEnd) }
        return AnnotatedString(source, spans)
    }

    /** Re-indents from the tokens, so it works on a file that does not parse and never touches a string. */
    override fun format(source: String): String = LuaFormat.reindent(source)

    /**
     * Everything wrong with [source], for a plugin whose manifest is shaped like
     * [host]. With [host] null, the checks that need the manifest are skipped.
     */
    fun diagnostics(source: String, host: LuaHostShape?): List<CodeDiagnostic> {
        val document = LuaDocuments.of(source)
        document.analysis?.let { lastAnalysis = it }
        return LuaDiagnostics.of(document, host).map { it.toCodeDiagnostic() }
    }

    override fun diagnostics(source: String): List<CodeDiagnostic> = diagnostics(source, null)

    /** The API name at [caret], for the strip under the code, from the tokens already lexed to colour it. */
    fun apiAt(source: String, caret: Int): LuaApiEntry? = LuaApiLookup.at(tokensOf(source), caret)

    override fun foldRegions(source: String): List<TextRange> =
        LuaNavigation.foldRegions(tokensOf(source)).map { TextRange(it.start, it.end) }

    /**
     * The analysis of the last text that parsed. Completion reads its locals while
     * the file is broken, which is most of the time a list is open, and a name
     * from a few keystrokes ago is still the name the author wants.
     */
    @Volatile
    private var lastAnalysis: LuaAnalysis? = null

    override fun completions(source: String, caret: Int, explicit: Boolean): CodeCompletions? {
        val found = LuaCompletion.at(tokensOf(source), caret, explicit = explicit, analysis = lastAnalysis) ?: return null
        return CodeCompletions(TextRange(found.replace.start, found.replace.end), found.items.map { it.toCodeCompletion() })
    }

    /** The first thing the lexer alone can see is wrong: an unclosed string or comment, or a stray character. */
    override fun problem(source: String): CodeProblem? {
        val tokens = tokensOf(source)
        for (index in 0 until tokens.size) {
            val kind = tokens.kind(index)
            if (kind == LuaTokenKind.UNKNOWN || tokens.unterminated(index)) return CodeProblem(tokens.start(index))
        }
        return null
    }

    /** Round, curly and square brackets that are code. One inside a string or comment is not a token of its own. */
    override fun brackets(source: String): List<Int> {
        val tokens = tokensOf(source)
        val found = ArrayList<Int>()
        for (index in 0 until tokens.size) {
            if (tokens.kind(index) != LuaTokenKind.OPERATOR) continue
            val start = tokens.start(index)
            if (tokens.end(index) - start == 1 && (source[start] in OPENERS || source[start] in CLOSERS)) found += start
        }
        return found
    }

    /** Pairs only brackets of one kind, so a `(` never boxes against a `]`. */
    override fun matchingBracket(source: String, brackets: List<Int>, caret: Int): Pair<Int, Int>? {
        if (brackets.isEmpty()) return null
        // The bracket behind the caret wins, which is where a caret sits after
        // the user has typed one.
        val behind = if (caret > 0) brackets.binarySearch(caret - 1) else -1
        val index = if (behind >= 0) behind else brackets.binarySearch(caret).takeIf { it >= 0 } ?: return null
        val at = brackets[index]
        if (at >= source.length) return null
        val bracket = source[at]
        val opener = OPENERS.indexOf(bracket)
        if (opener >= 0) {
            val closer = CLOSERS[opener]
            var depth = 0
            for (i in index until brackets.size) {
                val here = source[brackets[i]]
                if (here == bracket) depth++
                if (here == closer && --depth == 0) return at to brackets[i]
            }
            return null
        }
        val openerOfThis = OPENERS[CLOSERS.indexOf(bracket)]
        var depth = 0
        for (i in index downTo 0) {
            val here = source[brackets[i]]
            if (here == bracket) depth++
            if (here == openerOfThis && --depth == 0) return brackets[i] to at
        }
        return null
    }

    private fun styleOf(tokens: LuaTokens, index: Int, kind: LuaTokenKind, styles: Styles): SpanStyle? = when (kind) {
        LuaTokenKind.COMMENT, LuaTokenKind.LONG_COMMENT, LuaTokenKind.SHEBANG ->
            if (tokens.unterminated(index)) styles.problem else styles.comment
        LuaTokenKind.STRING, LuaTokenKind.LONG_STRING ->
            if (tokens.unterminated(index)) styles.problem else styles.string
        LuaTokenKind.NUMBER -> styles.number
        LuaTokenKind.KEYWORD -> styles.keyword
        LuaTokenKind.UNKNOWN -> styles.problem
        LuaTokenKind.NAME -> nameStyle(tokens, index, styles)
        LuaTokenKind.OPERATOR, LuaTokenKind.WHITESPACE -> null
    }

    /**
     * A name that is called or declared is a function; a name after a dot or a
     * colon is a field. Decided from the neighbouring tokens only, so it needs no
     * parse and never waits for one.
     */
    private fun nameStyle(tokens: LuaTokens, index: Int, styles: Styles): SpanStyle? {
        val next = tokens.nextCode(index)
        val previous = tokens.prevCode(index)
        val called = next >= 0 && (
            tokens.matches(next, "(") || tokens.matches(next, "{") ||
                tokens.kind(next) == LuaTokenKind.STRING || tokens.kind(next) == LuaTokenKind.LONG_STRING
            )
        val declared = previous >= 0 && tokens.matches(previous, "function")
        return when {
            called || declared -> styles.function
            previous >= 0 && (tokens.matches(previous, ".") || tokens.matches(previous, ":")) -> styles.key
            else -> null
        }
    }

    /** One instance of each style per pass, shared by every span of that colour. */
    private class Styles(colors: CodeColors) {
        val comment = SpanStyle(color = colors.comment)
        val string = SpanStyle(color = colors.string)
        val number = SpanStyle(color = colors.number)
        val keyword = SpanStyle(color = colors.keyword)
        val function = SpanStyle(color = colors.function)
        val key = SpanStyle(color = colors.key)
        val problem = SpanStyle(color = colors.problem)
    }
}

/** Lua's pairs, with both quote marks, and blocks that open after `then`, `do` and a function header. */
internal val LuaSmartRules = CodeSmartRules(
    pairs = mapOf('(' to ')', '{' to '}', '[' to ']', '"' to '"', '\'' to '\''),
    quotes = setOf('"', '\''),
    inert = ::luaInert,
    opensBlock = ::luaOpensBlock,
)

/**
 * Whether a caret at [at] is inside a string or a comment, judged by lexing only
 * the text before it. Nothing after the caret can change the answer, so the
 * prefix is exact.
 */
internal fun luaInert(text: String, at: Int): Boolean {
    if (at <= 0) return false
    val tokens = LuaLexer.lex(text.substring(0, at.coerceAtMost(text.length)))
    if (tokens.size == 0) return false
    val last = tokens.size - 1
    return when (tokens.kind(last)) {
        LuaTokenKind.COMMENT, LuaTokenKind.SHEBANG -> true
        LuaTokenKind.STRING, LuaTokenKind.LONG_STRING, LuaTokenKind.LONG_COMMENT -> tokens.unterminated(last)
        else -> false
    }
}

/**
 * Whether [line] ends a block header: a last word of `then`, `do`, `else` or
 * `repeat`, or a closing bracket after more `function` than `end` on the line,
 * which is `function render()` but not `foo(function() return 1 end)`.
 */
internal fun luaOpensBlock(line: String): Boolean {
    val tokens = LuaLexer.lex(line)
    var last = tokens.size - 1
    while (last >= 0 && !tokens.kind(last).isCode) last--
    if (last < 0) return false
    if (tokens.kind(last) == LuaTokenKind.KEYWORD) {
        return tokens.matches(last, "then") || tokens.matches(last, "do") ||
            tokens.matches(last, "else") || tokens.matches(last, "repeat")
    }
    if (!tokens.matches(last, ")")) return false
    var functions = 0
    var ends = 0
    for (index in 0..last) {
        if (tokens.kind(index) != LuaTokenKind.KEYWORD) continue
        if (tokens.matches(index, "function")) functions++
        if (tokens.matches(index, "end")) ends++
    }
    return functions > ends
}

/** A Lua suggestion as the code field lists it. */
internal fun LuaCompletionItem.toCodeCompletion(): CodeCompletion = CodeCompletion(
    label = label,
    kind = when (kind) {
        LuaCompletionKind.LOCAL, LuaCompletionKind.PARAMETER, LuaCompletionKind.GLOBAL -> CodeCompletionKind.VARIABLE
        LuaCompletionKind.FUNCTION -> CodeCompletionKind.FUNCTION
        LuaCompletionKind.TABLE -> CodeCompletionKind.MODULE
        LuaCompletionKind.CONSTANT -> CodeCompletionKind.CONSTANT
        LuaCompletionKind.FIELD -> CodeCompletionKind.FIELD
        LuaCompletionKind.KEYWORD -> CodeCompletionKind.KEYWORD
        LuaCompletionKind.SNIPPET -> CodeCompletionKind.SNIPPET
        LuaCompletionKind.VALUE -> CodeCompletionKind.VALUE
    },
    insert = insert,
    caret = caret,
    detail = detail,
)
