package com.wasimaster.wmkeyboard.core.tools

/**
 * Reads a model's answer into blocks a chat bubble can draw: paragraphs,
 * headings, list items, quotes and fenced code.
 *
 * [AiMarkdown] answers a different question. It removes the markers, because a
 * text field cannot draw them. A chat bubble can, and a fenced block there is
 * worth more than its text: it is the part of an answer the user most often
 * wants to copy alone, so the bubble has to know where one starts and stops.
 *
 * Deliberately a small subset, and no Compose: the drawing is the caller's, so
 * this runs on the JVM under test. The inline rules are the conservative ones
 * [AiMarkdown] uses, for the same reason: `2 * 3 * 4` and `file_name` are not
 * emphasis.
 *
 * The answer arrives as a stream, so every rule has to read a half-written
 * text sensibly. A fence that has not closed yet is a code block that runs to
 * the end ([Block.Code.closed] is false), and an emphasis marker without its
 * partner is plain text until the partner arrives.
 */
object AiMarkdownBlocks {

    enum class Style { BOLD, ITALIC, CODE }

    /** [style] over `[start, end)` of [Inline.text]. */
    data class Span(val start: Int, val end: Int, val style: Style)

    /** One run of text with its markers removed and turned into [spans]. */
    data class Inline(val text: String, val spans: List<Span> = emptyList())

    sealed interface Block {
        data class Paragraph(val inline: Inline) : Block
        data class Heading(val level: Int, val inline: Inline) : Block

        /** [marker] is what the bubble draws in front: a dot, or `3.` */
        data class Bullet(val indent: Int, val marker: String, val inline: Inline) : Block
        data class Quote(val inline: Inline) : Block

        /** [code] has no fences and no trailing newline. */
        data class Code(val language: String, val code: String, val closed: Boolean) : Block
        data object Rule : Block
    }

    private val FENCE = Regex("""^ {0,3}(```|~~~)\s*([\w+#.-]*).*$""")
    private val HEADING = Regex("""^ {0,3}(#{1,6}) +(.*?)( +#+ *)?$""")
    private val BULLET = Regex("""^( *)[-*+] +(.*)$""")
    private val ORDERED = Regex("""^( *)(\d{1,3})[.)] +(.*)$""")
    private val QUOTE = Regex("""^ {0,3}(?:> ?)+(.*)$""")
    private val RULE = Regex("""^ {0,3}([-*_])( *\1){2,} *$""")

    private val BOLD = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")
    private val ITALIC = Regex("""(?<![\w*_])([*_])(?=\S)([^*_\n]+?)(?<=\S)\1(?![\w*_])""")
    private val CODE = Regex("""`([^`\n]+)`""")
    private val LINK = Regex("""\[([^\]\n]*)]\((\S*?)\)""")

    fun parse(text: String): List<Block> {
        val blocks = ArrayList<Block>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isEmpty()) return
            blocks += Block.Paragraph(inline(paragraph.joinToString("\n")))
            paragraph.clear()
        }

        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                flush()
                val mark = fence.groupValues[1]
                val code = ArrayList<String>()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith(mark) && lines[i].trim().all { it == mark[0] }) {
                        closed = true
                        i++
                        break
                    }
                    code += lines[i]
                    i++
                }
                blocks += Block.Code(fence.groupValues[2], code.joinToString("\n"), closed)
                continue
            }
            i++
            if (line.isBlank()) {
                flush()
                continue
            }
            // Before the bullet rule: `* * *` is a rule, not a list of stars.
            if (RULE.matches(line)) {
                flush()
                blocks += Block.Rule
                continue
            }
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val ordered = ORDERED.matchEntire(line)
            val quote = QUOTE.matchEntire(line)
            when {
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[1].length, inline(heading.groupValues[2]))
                }
                bullet != null -> {
                    flush()
                    blocks += Block.Bullet(bullet.groupValues[1].length / 2, "•", inline(bullet.groupValues[2]))
                }
                ordered != null -> {
                    flush()
                    blocks += Block.Bullet(
                        ordered.groupValues[1].length / 2,
                        ordered.groupValues[2] + ".",
                        inline(ordered.groupValues[3]),
                    )
                }
                quote != null -> {
                    flush()
                    blocks += Block.Quote(inline(quote.groupValues[1]))
                }
                else -> paragraph += line.trimEnd()
            }
        }
        flush()
        return blocks
    }

    /** [text] with its inline markers turned into spans. */
    fun inline(text: String): Inline {
        val out = StringBuilder()
        val spans = ArrayList<Span>()
        var at = 0
        while (at < text.length) {
            val next = listOfNotNull(
                CODE.find(text, at), LINK.find(text, at), BOLD.find(text, at), ITALIC.find(text, at),
            ).minByOrNull { it.range.first }
            if (next == null) break
            out.append(text, at, next.range.first)
            val start = out.length
            when (next.value.first()) {
                '`' -> {
                    out.append(next.groupValues[1])
                    spans += Span(start, out.length, Style.CODE)
                }
                '[' -> {
                    val (label, url) = next.destructured
                    out.append(
                        when {
                            label.isBlank() -> url
                            url.isBlank() || url == label -> label
                            else -> "$label ($url)"
                        },
                    )
                }
                else -> {
                    val bold = next.groupValues[1].length == 2
                    // Emphasis nests: bold text can hold italics and code.
                    val inner = inline(next.groupValues[2])
                    out.append(inner.text)
                    inner.spans.mapTo(spans) { it.copy(start = it.start + start, end = it.end + start) }
                    spans += Span(start, out.length, if (bold) Style.BOLD else Style.ITALIC)
                }
            }
            at = next.range.last + 1
        }
        out.append(text, at, text.length)
        return Inline(out.toString(), spans)
    }
}
