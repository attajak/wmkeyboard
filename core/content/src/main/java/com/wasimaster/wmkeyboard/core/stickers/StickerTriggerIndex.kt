package com.wasimaster.wmkeyboard.core.stickers

/**
 * Which of the user's own stickers the text before the cursor asks for (#329).
 *
 * A sticker answers to its title typed out in full, and to any of its
 * keywords — a shorter word, a phrase, or an emoji. Matching is on the end of
 * the text, the way a smart chip's is: "see you tomorrow 👋" offers the 👋
 * stickers, and so does "tomorrow" for a sticker keyworded that way.
 *
 * Built once per change of the packs rather than per keystroke, so a match is
 * one map lookup for each distinct trigger length — a few dozen at most —
 * however many stickers there are.
 *
 * Case, emoji variation selectors and skin tones are ignored on both sides:
 * a sticker keyworded 👍 is offered for 👍🏽, and a pack keyworded ❤ (as
 * Signal's often are) for the ❤️ an emoji keyboard types.
 */
class StickerTriggerIndex private constructor(
    private val byKey: Map<String, List<Entry>>,
    /** Every key length present, longest first, so the longest trigger wins. */
    private val lengths: IntArray,
) {

    private class Entry(val packId: String, val sticker: CustomSticker, val fromTitle: Boolean)

    /** One sticker the text asks for. [span] is how many characters its trigger occupies before the cursor. */
    data class Hit(val packId: String, val sticker: CustomSticker, val span: Int)

    /**
     * What the text asks for. [trigger] is the longest trigger as it was
     * typed; every hit carries its own span, because a sticker matched by
     * "birthday" inside "happy birthday" must not take "happy" with it when it
     * is sent.
     */
    data class Match(val trigger: String, val hits: List<Hit>)

    val isEmpty: Boolean get() = byKey.isEmpty()

    /**
     * The stickers [text] ends by asking for, or null when it asks for none.
     * Whitespace after the trigger is allowed and counted into the span: the
     * space that finished the word is part of what a send takes back.
     *
     * [emojiOnly] leaves out every trigger with a letter or digit in it — the
     * emoji panel's case, where the text before the cursor was typed before
     * the panel opened and is not what the user is picking now.
     */
    fun match(text: String, emojiOnly: Boolean = false, limit: Int = MAX_HITS): Match? {
        if (byKey.isEmpty() || text.isEmpty()) return null
        val (norm, origin) = normalizeMapped(text)
        var end = norm.length
        while (end > 0 && norm[end - 1] == ' ') end--
        if (end == 0) return null
        var trigger: String? = null
        val hits = ArrayList<Hit>()
        val seen = HashSet<String>()
        for (length in lengths) {
            if (length > end) continue
            val start = end - length
            val key = norm.substring(start, end)
            val entries = byKey[key] ?: continue
            val emojiKey = StickerKeywords.isEmoji(key)
            if (emojiOnly && !emojiKey) continue
            if (start > 0) {
                val before = norm[start - 1]
                // The tail of a joined emoji ("❤️‍🔥" ends in 🔥) is not a 🔥.
                if (before == ZWJ) continue
                // A word must start where a word starts: "concat" is no "cat".
                if (!emojiKey && isWordChar(key[0]) && isWordChar(before)) continue
            }
            val originStart = origin[start]
            val span = text.length - originStart
            if (trigger == null) trigger = text.substring(originStart).trim()
            for (entry in entries) {
                if (seen.add(entry.packId + "/" + entry.sticker.id)) hits += Hit(entry.packId, entry.sticker, span)
            }
            if (hits.size >= limit) break
        }
        return trigger?.let { Match(it, hits.take(limit)) }
    }

    companion object {
        /** Characters of context a caller should read; longer than any trigger. */
        const val LOOKBEHIND = 64

        /** Longest trigger indexed. Shorter than [LOOKBEHIND], so a match never starts at a cut. */
        const val MAX_TRIGGER_LENGTH = 48

        /** Most stickers one match offers. */
        const val MAX_HITS = 24

        val EMPTY = StickerTriggerIndex(emptyMap(), IntArray(0))

        /**
         * Indexes every sticker's title and keywords. Within one trigger,
         * stickers whose title it is come before those that only carry it as
         * a keyword, and after that they keep pack order.
         */
        fun build(packs: List<StickerPack>): StickerTriggerIndex {
            val byKey = HashMap<String, MutableList<Entry>>()
            fun add(raw: String, packId: String, sticker: CustomSticker, fromTitle: Boolean) {
                val key = normalize(raw)
                if (key.isEmpty() || key.length > MAX_TRIGGER_LENGTH) return
                val list = byKey.getOrPut(key) { ArrayList() }
                if (list.none { it.packId == packId && it.sticker.id == sticker.id }) {
                    list += Entry(packId, sticker, fromTitle)
                }
            }
            for (pack in packs) {
                for (sticker in pack.stickers) {
                    add(sticker.name, pack.id, sticker, fromTitle = true)
                    sticker.keywords.forEach { add(it, pack.id, sticker, fromTitle = false) }
                }
            }
            if (byKey.isEmpty()) return EMPTY
            val sorted = byKey.mapValues { (_, list) -> list.sortedBy { !it.fromTitle } }
            val lengths = sorted.keys.map { it.length }.distinct().sortedDescending().toIntArray()
            return StickerTriggerIndex(sorted, lengths)
        }

        /** The form triggers are compared in; see the class note. */
        fun normalize(text: String): String = normalizeMapped(text).first.trim()

        /**
         * [text] lowercased one char at a time (so indices survive), emoji
         * presentation selectors and skin tones dropped, and every run of
         * whitespace folded to one space. The array maps each char of the
         * result back to where it came from in [text].
         */
        private fun normalizeMapped(text: String): Pair<String, IntArray> {
            val out = StringBuilder(text.length)
            val origin = IntArray(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '︎' || c == '️' -> i++
                    // U+1F3FB..U+1F3FF: the skin tone modifiers.
                    c == '\uD83C' && i + 1 < text.length && text[i + 1] in '\uDFFB'..'\uDFFF' -> i += 2
                    c.isWhitespace() -> {
                        if (out.isEmpty() || out[out.length - 1] != ' ') {
                            origin[out.length] = i
                            out.append(' ')
                        }
                        i++
                    }
                    else -> {
                        origin[out.length] = i
                        out.append(c.lowercaseChar())
                        i++
                    }
                }
            }
            return out.toString() to origin.copyOf(out.length)
        }

        private const val ZWJ = '‍'

        /**
         * Letters, digits and the marks that live inside a word: without the
         * marks, a Bengali title starting with a consonant would match after
         * any vowel sign, in the middle of a word.
         */
        private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || when (Character.getType(c).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }
}
