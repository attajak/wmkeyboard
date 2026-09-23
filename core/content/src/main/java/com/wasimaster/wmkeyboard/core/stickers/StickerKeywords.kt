package com.wasimaster.wmkeyboard.core.stickers

/**
 * The keywords field of the sticker editor: [CustomSticker.keywords] shown as
 * one line of text and read back into a list.
 *
 * Commas separate keywords, so a keyword can be a phrase ("good night").
 * Emoji need no separator at all — "😂🤣" is two keywords — and an emoji
 * typed inside a phrase is lifted out as a keyword of its own, since a phrase
 * with an emoji in the middle is something nobody would ever type to find it.
 */
object StickerKeywords {

    /** Most keywords one sticker keeps; the rest of a pasted list is dropped. */
    const val MAX_KEYWORDS = 40

    /** Longest keyword kept. Longer ones could never be typed as a trigger anyway. */
    const val MAX_LENGTH = StickerTriggerIndex.MAX_TRIGGER_LENGTH

    /** [keywords] as the one line the field shows. */
    fun format(keywords: List<String>): String =
        keywords.filter { it.isNotBlank() }.joinToString(", ")

    /**
     * What the user typed, as keywords: split on commas (and the Arabic,
     * full-width and ideographic ones), emoji lifted out one by one, blanks
     * and repeats dropped. Repeats are judged the way typing matches them, so
     * "Cat" and "cat" are one keyword and the first spelling is kept.
     */
    fun parse(text: String): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(keyword: String) {
            val trimmed = keyword.trim().replace(WHITESPACE, " ")
            if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return
            if (seen.add(StickerTriggerIndex.normalize(trimmed))) out += trimmed
        }
        for (piece in text.split(SEPARATORS)) {
            val words = StringBuilder()
            for (cluster in clusters(piece)) {
                if (cluster.isBlank()) {
                    words.append(' ')
                } else if (isEmoji(cluster)) {
                    add(cluster)
                } else {
                    words.append(cluster)
                }
            }
            add(words.toString())
        }
        return out.take(MAX_KEYWORDS)
    }

    /**
     * What the sticker panel's search box matches: the title and every
     * keyword, as one line, so a query can run across the two.
     */
    fun haystack(sticker: CustomSticker): String =
        (listOf(sticker.name) + sticker.keywords).filter { it.isNotBlank() }.joinToString(" ")

    /**
     * True for text with no letter, digit or combining mark in it: an emoji,
     * or a run of them. Such a keyword needs no word boundary around it to
     * match, and is the only kind the emoji panel offers stickers for. The
     * marks count as word because a vowel sign is half of a Bengali syllable;
     * the emoji's own variation selectors and keycap are marks too, and don't.
     */
    fun isEmoji(text: String): Boolean = text.isNotBlank() && text.all(::isEmojiChar)

    private fun isEmojiChar(c: Char): Boolean = when {
        c.isLetterOrDigit() || c.isWhitespace() -> false
        c.code in MODIFIERS -> true
        else -> !isMark(c.code)
    }

    private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /**
     * [text] cut into emoji-sized pieces: one per code point, except that a
     * zero-width joiner or non-joiner, a variation selector, a skin tone, a
     * combining mark (a keycap, a vowel sign) or a tag character stays with
     * what it modifies, and regional indicators pair up into flags. Close
     * enough to grapheme clusters for emoji, which is all this is asked about:
     * ordinary letters come out one at a time and are glued back together by
     * the caller.
     */
    internal fun clusters(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var joinNext = false
        var regionalRun = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val regional = cp in REGIONAL_INDICATORS
            val extends = current.isNotEmpty() && (
                joinNext || cp == ZWJ || cp == ZWNJ || cp in MODIFIERS || cp in SKIN_TONES || cp in TAGS ||
                    isMark(cp) || (regional && regionalRun == 1)
                )
            if (!extends && current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
                regionalRun = 0
            }
            current.appendCodePoint(cp)
            joinNext = cp == ZWJ
            if (regional) regionalRun++
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private const val ZWJ = 0x200D
    private const val ZWNJ = 0x200C
    private val MODIFIERS = setOf(0xFE0E, 0xFE0F, 0x20E3)
    private val SKIN_TONES = 0x1F3FB..0x1F3FF
    private val TAGS = 0xE0020..0xE007F
    private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF
    private val SEPARATORS = Regex("[,，،、;\\n]")
    private val WHITESPACE = Regex("\\s+")
}
