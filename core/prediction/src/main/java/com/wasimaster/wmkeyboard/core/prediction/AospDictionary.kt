package com.wasimaster.wmkeyboard.core.prediction

/**
 * Reading a compiled AOSP `.dict` dictionary.
 *
 * This is the binary every AOSP-derived keyboard ships and every dictionary
 * repository publishes: HeliBoard's and LeanType's downloads, FUTO's, the ones
 * pulled out of a GApps package, and anything built with the `dicttool`
 * compiler. It is also the only form most of those dictionaries exist in, so
 * without this a user moving over has to find the source word list again, and
 * for most languages there is not one.
 *
 * **Written from the published format**, on the same footing as the layout
 * converters in `:core:language`: AOSP's `FormatSpec` documents the byte layout
 * in its own header comment and that description is what this follows. No
 * upstream code is copied; AOSP LatinIME is Apache-2.0 and this app is MIT.
 *
 * ### What it reads
 *
 * All three things a dictionary holds, each for its own destination:
 *
 * - **Words** and their frequencies, for the imported word list.
 * - **Word pairs** (the file's bigrams), for next-word prediction. A pair names
 *   its second word by the address of that word's node, so the walk records
 *   where every word starts and the pairs are resolved once it is done.
 * - **Shortcuts** (`omw` offering `on my way`), for the strip. Kept as
 *   trigger and expansion, never as a word: nobody typed the expansion, and it
 *   is often a phrase.
 *
 * Version 2 files, in all of their revisions (2, 201, 202, 203), static or
 * dynamic, with or without a `codePointTable`. Version 4 is a directory of two
 * files rather than one, and [AospDictionaryV4] reads it from both.
 *
 * ### The code point table
 *
 * From 201 on, a compiler may put the 224 characters a dictionary uses most on
 * the one-byte codes 0x20..0xFF, and name them in a `codePointTable` header
 * attribute: the n-th character of that string is what byte `0x20 + n` means.
 * A Russian or Greek dictionary built that way spells every letter in one byte
 * instead of three. Read without the table the file does not fail, it produces
 * different words, so the table is applied to every node's characters. It is
 * not applied to header strings or shortcut targets: the compiler writes those
 * without it, and AOSP's own reader reads them the same way.
 */
object AospDictionary {

    /** The first four bytes of every version of this format. */
    const val MAGIC: Int = -0x643EC502 // 0x9BC13AFE

    /** Enough of the file to answer [looksLikeDictionary]. */
    const val MAGIC_BYTES: Int = 4

    /**
     * A word pair or triple, in the order the words are typed: [context] is
     * one word for a pair and two for a triple, oldest first.
     *
     * [count] is already on the scale of a downloaded pack's counts (see
     * [AospScores]), so an imported pair and a downloaded one are read by the
     * same code with the same weight.
     */
    data class Ngram(val context: List<String>, val word: String, val count: Int)

    /**
     * Typing [trigger] offers [expansion]. [whitelist] marks the ones the
     * source keyboard applied on its own rather than offering; it is kept so a
     * caller can tell, but this app only ever offers.
     */
    data class Shortcut(val trigger: String, val expansion: String, val whitelist: Boolean)

    /** What [read] gives back. */
    sealed interface Result {

        /**
         * Everything the file holds. [words] are on this app's 1..10000 scale.
         * [attributes] are the header's own key/value pairs: `locale` and
         * `description` are the ones worth showing a user before an import.
         */
        data class Contents(
            val words: List<Pair<String, Int>>,
            val ngrams: List<Ngram>,
            val shortcuts: List<Shortcut>,
            val attributes: Map<String, String>,
        ) : Result {
            val isEmpty: Boolean get() = words.isEmpty() && ngrams.isEmpty() && shortcuts.isEmpty()
        }

        /** Not this format at all. */
        data object NotADictionary : Result

        /**
         * The header of a version 4 dictionary. Its words are in the `.body`
         * file beside it, so this alone cannot be read: [AospDictionaryV4]
         * needs both.
         */
        data class HeaderOnly(val version: Int) : Result

        /**
         * This format, but a revision this reader does not know. 402 is the
         * one real case: an older layout of version 4 that no current keyboard
         * writes.
         */
        data class Unsupported(val version: Int) : Result
    }

    /** True when [head] (at least [MAGIC_BYTES] long) starts with the magic number. */
    fun looksLikeDictionary(head: ByteArray): Boolean =
        head.size >= MAGIC_BYTES && readInt(head, 0) == MAGIC

    /**
     * Reads everything out of a one-file dictionary.
     *
     * Never throws: a truncated file, a cyclic children address and a file that
     * is not a dictionary at all are all ordinary outcomes. The walk is bounded
     * on both axes ([MAX_DEPTH], [MAX_WORDS]) so a corrupt file cannot spin.
     */
    fun read(bytes: ByteArray): Result {
        if (!looksLikeDictionary(bytes)) return Result.NotADictionary
        val header = readHeader(bytes) ?: return Result.NotADictionary
        if (header.version >= FIRST_VERSION_4) {
            return if (AospDictionaryV4.isSupported(header.version)) {
                Result.HeaderOnly(header.version)
            } else {
                Result.Unsupported(header.version)
            }
        }
        val walk = Walk(Cursor(bytes, header.codePointTable), header.dynamic)
        runCatching { walk.array(header.size, "", depth = 0) }
        val contents = walk.contents(header.attributes)
        // A file that parsed into nothing is more likely to be one this reader
        // misread than a dictionary with nothing in it.
        return if (contents.words.isEmpty()) Result.NotADictionary else contents
    }

    // ---- header ----

    /** The fixed fields and the attributes, shared with [AospDictionaryV4]. */
    internal class Header(
        val version: Int,
        val size: Int,
        val dynamic: Boolean,
        val attributes: Map<String, String>,
    ) {
        /** Byte `0x20 + n` means the n-th code point here, or null for none. */
        val codePointTable: IntArray? =
            attributes[CODE_POINT_TABLE_KEY]?.takeIf { it.isNotEmpty() }?.codePoints()?.toArray()
    }

    /**
     * Magic, version, option flags, header size, then key/value attribute
     * strings until the header size is reached.
     */
    internal fun readHeader(bytes: ByteArray): Header? {
        if (bytes.size < MIN_HEADER_BYTES || !looksLikeDictionary(bytes)) return null
        val version = readShort(bytes, VERSION_OFFSET)
        val optionFlags = readShort(bytes, OPTION_FLAGS_OFFSET)
        val headerSize = readInt(bytes, HEADER_SIZE_OFFSET)
        if (headerSize < MIN_HEADER_BYTES || headerSize > bytes.size) return null
        // Header strings are never written through the code point table.
        val cursor = Cursor(bytes, codePointTable = null).apply { pos = MIN_HEADER_BYTES }
        val attributes = LinkedHashMap<String, String>()
        runCatching {
            while (cursor.pos < headerSize) {
                val key = cursor.readString(headerSize) ?: break
                val value = cursor.readString(headerSize) ?: break
                attributes[key] = value
            }
        }
        return Header(
            version = version,
            size = headerSize,
            dynamic = (optionFlags and SUPPORTS_DYNAMIC_UPDATE) != 0,
            attributes = attributes,
        )
    }

    // ---- the trie ----

    /**
     * One pass over the node trie. Words land in [words] as they are met;
     * pairs are held as (word, address of the second word, strength) until
     * [contents], when every address has a word to resolve to.
     */
    private class Walk(private val cursor: Cursor, private val dynamic: Boolean) {

        val words = ArrayList<Pair<String, Int>>()
        private val wordAt = HashMap<Int, String>()
        private val frequencyAt = HashMap<Int, Int>()
        private val notWords = HashSet<Int>()
        private val pendingPairs = ArrayList<PendingPair>()
        private val shortcuts = ArrayList<Shortcut>()

        private class PendingPair(val first: String, val target: Int, val strength: Int)

        /**
         * One node array: a count, that many nodes, and in a dynamic file a
         * link to the array that continues it.
         *
         * Iterative over forward links and recursive over children, which is
         * the shape of the format: links chain sideways without bound, children
         * nest only as deep as the longest word.
         */
        fun array(startPos: Int, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            var arrayPos = startPos
            var links = 0
            while (arrayPos in 0 until cursor.size && words.size < MAX_WORDS && links <= MAX_FORWARD_LINKS) {
                cursor.pos = arrayPos
                val count = cursor.readNodeCount()
                if (count <= 0 || count > MAX_NODES_IN_ARRAY) return
                repeat(count) {
                    if (words.size < MAX_WORDS) node(prefix, depth)
                }
                if (!dynamic) return
                val linkFieldPos = cursor.pos
                val link = cursor.u24()
                if (link == NO_FORWARD_LINK) return
                arrayPos = linkFieldPos + link
                links++
            }
        }

        /** One node, leaving [cursor] on the byte after it. */
        @Suppress("CyclomaticComplexMethod")
        private fun node(prefix: String, depth: Int) {
            val nodePos = cursor.pos
            val flags = cursor.u8()
            // A dynamic file carries a parent address here. It is of no use to
            // a walk that already knows its own prefix, but it has to be
            // stepped over.
            if (dynamic) cursor.u24()
            val word = StringBuilder(prefix)
            if ((flags and FLAG_HAS_MULTIPLE_CHARS) != 0) {
                while (true) {
                    val code = cursor.readNodeCodePoint() ?: break
                    word.appendCodePoint(code)
                }
            } else {
                cursor.readNodeCodePoint()?.let { word.appendCodePoint(it) }
            }
            val frequency = if ((flags and FLAG_IS_TERMINAL) != 0) cursor.u8() else NOT_A_TERMINAL
            // "Relative to the position of this field", so the offset is added
            // to where the field starts, not to where it ends.
            val childrenFieldPos = cursor.pos
            val childrenOffset = cursor.readChildrenAddress(flags, dynamic)
            val text = word.toString()
            if ((flags and FLAG_HAS_SHORTCUT_TARGETS) != 0) readShortcuts(text)
            if ((flags and FLAG_HAS_BIGRAMS) != 0) readPairs(text)
            if (frequency != NOT_A_TERMINAL && text.isNotEmpty()) {
                // Recorded even for a not-a-word entry: a pair may still point
                // at it, and resolving to a word nobody suggests is how that
                // pair is dropped rather than mis-resolved.
                wordAt[nodePos] = text
                frequencyAt[nodePos] = frequency
                // `is not a word` marks an entry that exists only to hang a
                // shortcut or a pair off, which is not a word this app should
                // ever suggest.
                if ((flags and FLAG_IS_NOT_A_WORD) == 0) {
                    words.add(text to DictionaryLoader.scaleAospFrequency(frequency))
                } else {
                    notWords.add(nodePos)
                }
            }
            if (childrenOffset != null && text.isNotEmpty()) {
                val resume = cursor.pos
                array(childrenFieldPos + childrenOffset, text, depth + 1)
                cursor.pos = resume
            }
        }

        /** The list declares its own byte length, which is where it must end. */
        private fun readShortcuts(trigger: String) {
            val start = cursor.pos
            val length = cursor.u16()
            require(length >= SHORTCUT_LIST_SIZE_BYTES) { "shortcut list shorter than its own size" }
            val end = start + length
            var read = 0
            while (cursor.pos < end && read++ < MAX_SHORTCUTS_IN_A_NODE) {
                val flags = cursor.u8()
                val target = cursor.readString(end) ?: break
                if (trigger.isNotEmpty() && target.isNotEmpty() && target != trigger) {
                    val strength = flags and MASK_ATTRIBUTE_PROBABILITY
                    shortcuts.add(Shortcut(trigger, target, strength == WHITELIST_SHORTCUT_PROBABILITY))
                }
                if ((flags and FLAG_ATTRIBUTE_HAS_NEXT) == 0) break
            }
            // The declared length is the authority: whatever the entries said,
            // the node carries on here.
            cursor.pos = end
        }

        /**
         * A chain of attributes, each saying whether another follows it. The
         * address is relative to where the address field itself starts, and
         * the sign lives in the flags.
         */
        private fun readPairs(first: String) {
            var read = 0
            while (read++ < MAX_BIGRAMS_IN_A_NODE) {
                val flags = cursor.u8()
                val origin = cursor.pos
                val offset = when (flags and MASK_BIGRAM_ADDRESS_TYPE) {
                    FLAG_BIGRAM_ADDRESS_TYPE_ONEBYTE -> cursor.u8()
                    FLAG_BIGRAM_ADDRESS_TYPE_TWOBYTES -> cursor.u16()
                    FLAG_BIGRAM_ADDRESS_TYPE_THREEBYTES -> cursor.u24()
                    // A pair with no address is a file this reader cannot keep
                    // its place in, so it stops rather than drifting.
                    else -> error("bigram with no address")
                }
                val target = if ((flags and FLAG_BIGRAM_OFFSET_NEGATIVE) != 0) origin - offset else origin + offset
                if (first.isNotEmpty()) {
                    pendingPairs.add(PendingPair(first, target, flags and MASK_ATTRIBUTE_PROBABILITY))
                }
                if ((flags and FLAG_ATTRIBUTE_HAS_NEXT) == 0) return
            }
        }

        fun contents(attributes: Map<String, String>): Result.Contents {
            val ngrams = ArrayList<Ngram>(pendingPairs.size)
            for (pair in pendingPairs) {
                if (pair.target in notWords) continue
                val second = wordAt[pair.target] ?: continue
                val unigram = frequencyAt[pair.target] ?: continue
                val probability = AospScores.bigramProbability(unigram, pair.strength)
                ngrams.add(Ngram(listOf(pair.first), second, AospScores.pairCount(probability)))
            }
            return Result.Contents(words, ngrams, shortcuts, attributes)
        }
    }

    // ---- bytes ----

    internal class Cursor(private val bytes: ByteArray, private val codePointTable: IntArray?) {

        var pos: Int = 0

        val size: Int get() = bytes.size

        fun u8(): Int {
            require(pos in bytes.indices) { "past the end of the file" }
            return bytes[pos++].toInt() and BYTE_MASK
        }

        fun u16(): Int = (u8() shl BITS_PER_BYTE) or u8()

        fun u24(): Int = (u16() shl BITS_PER_BYTE) or u8()

        fun u32(): Int = (u16() shl BITS_PER_SHORT) or u16()

        /**
         * A signed 24-bit field, sign and magnitude: the top bit set means
         * the other 23 are negative.
         */
        fun s24(): Int {
            val raw = u24()
            return if ((raw and MSB24) != 0) -(raw and SINT24_MAX) else raw
        }

        /** 1 or 2 bytes: a top bit set means the count runs into the next byte. */
        fun readNodeCount(): Int {
            val first = u8()
            return if (first <= MAX_NODES_IN_ONE_BYTE_COUNT) {
                first
            } else {
                ((first and MAX_NODES_IN_ONE_BYTE_COUNT) shl BITS_PER_BYTE) or u8()
            }
        }

        /**
         * One character, or null at the terminator.
         *
         * Everything from 0x20 up is its own byte, which makes Latin text one
         * byte a letter. A first byte below that is the top third of a 24-bit
         * code point, which is how the format reaches the whole of Unicode, and
         * 0x1F alone ends a string — no code point can start with it.
         */
        fun readCodePoint(): Int? = readCodePoint(null)

        /** [readCodePoint] through the dictionary's code point table. */
        fun readNodeCodePoint(): Int? = readCodePoint(codePointTable)

        private fun readCodePoint(table: IntArray?): Int? {
            val first = u8()
            if (first >= MIN_ONE_BYTE_CHARACTER) {
                // A byte past the end of a short table is its own character.
                return table?.getOrNull(first - MIN_ONE_BYTE_CHARACTER) ?: first
            }
            if (first == CHARACTERS_TERMINATOR) return null
            return (first shl BITS_PER_SHORT) or u16()
        }

        /** The children offset, or null when the node has no children. */
        fun readChildrenAddress(flags: Int, dynamic: Boolean): Int? {
            if (dynamic) {
                val raw = u24()
                if (raw == 0) return null
                return if ((raw and MSB24) != 0) -(raw and SINT24_MAX) else raw
            }
            return when (flags and MASK_CHILDREN_ADDRESS_TYPE) {
                FLAG_CHILDREN_ADDRESS_TYPE_ONEBYTE -> u8()
                FLAG_CHILDREN_ADDRESS_TYPE_TWOBYTES -> u16()
                FLAG_CHILDREN_ADDRESS_TYPE_THREEBYTES -> u24()
                else -> null
            }
        }

        /**
         * A string up to its terminator, or null when [limit] comes first.
         * Header keys and values, shortcut targets: never through the table.
         */
        fun readString(limit: Int): String? {
            val out = StringBuilder()
            while (pos < limit) {
                val code = readCodePoint() ?: return out.toString()
                out.appendCodePoint(code)
            }
            return null
        }
    }

    internal fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and BYTE_MASK shl BITS_PER_BYTE * 3) or
            (bytes[offset + 1].toInt() and BYTE_MASK shl BITS_PER_BYTE * 2) or
            (bytes[offset + 2].toInt() and BYTE_MASK shl BITS_PER_BYTE) or
            (bytes[offset + 3].toInt() and BYTE_MASK)

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and BYTE_MASK shl BITS_PER_BYTE) or (bytes[offset + 1].toInt() and BYTE_MASK)

    // Byte layout, all transcribed from the published format.
    private const val VERSION_OFFSET = 4
    private const val OPTION_FLAGS_OFFSET = 6
    private const val HEADER_SIZE_OFFSET = 8
    private const val MIN_HEADER_BYTES = 12

    private const val SUPPORTS_DYNAMIC_UPDATE = 0x2
    private const val FIRST_VERSION_4 = 399

    private const val MASK_CHILDREN_ADDRESS_TYPE = 0xC0
    private const val FLAG_CHILDREN_ADDRESS_TYPE_ONEBYTE = 0x40
    private const val FLAG_CHILDREN_ADDRESS_TYPE_TWOBYTES = 0x80
    private const val FLAG_CHILDREN_ADDRESS_TYPE_THREEBYTES = 0xC0
    internal const val FLAG_HAS_MULTIPLE_CHARS = 0x20
    internal const val FLAG_IS_TERMINAL = 0x10
    private const val FLAG_HAS_SHORTCUT_TARGETS = 0x08
    private const val FLAG_HAS_BIGRAMS = 0x04
    private const val FLAG_IS_NOT_A_WORD = 0x02

    private const val FLAG_ATTRIBUTE_HAS_NEXT = 0x80
    private const val FLAG_BIGRAM_OFFSET_NEGATIVE = 0x40
    private const val MASK_BIGRAM_ADDRESS_TYPE = 0x30
    private const val FLAG_BIGRAM_ADDRESS_TYPE_ONEBYTE = 0x10
    private const val FLAG_BIGRAM_ADDRESS_TYPE_TWOBYTES = 0x20
    private const val FLAG_BIGRAM_ADDRESS_TYPE_THREEBYTES = 0x30
    private const val MASK_ATTRIBUTE_PROBABILITY = 0x0F
    private const val WHITELIST_SHORTCUT_PROBABILITY = 15

    private const val CHARACTERS_TERMINATOR = 0x1F
    private const val MIN_ONE_BYTE_CHARACTER = 0x20
    private const val MAX_NODES_IN_ONE_BYTE_COUNT = 0x7F
    private const val MAX_NODES_IN_ARRAY = 0x7FFF
    private const val MAX_BIGRAMS_IN_A_NODE = 10_000
    private const val MAX_SHORTCUTS_IN_A_NODE = 1_000
    private const val SHORTCUT_LIST_SIZE_BYTES = 2
    private const val NO_FORWARD_LINK = 0
    internal const val MSB24 = 0x800000
    internal const val SINT24_MAX = 0x7FFFFF
    private const val CODE_POINT_TABLE_KEY = "codePointTable"

    private const val NOT_A_TERMINAL = -1
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8
    private const val BITS_PER_SHORT = 16

    /** Longer than any word in any of these dictionaries; a stop, not a limit. */
    internal const val MAX_DEPTH = 64

    /** Bounds a corrupt file rather than the honest ones: the largest ship ~250k. */
    internal const val MAX_WORDS = 1_000_000

    /** A static file has none of these at all; a dynamic one has a handful. */
    internal const val MAX_FORWARD_LINKS = 1024
}

/**
 * The one place AOSP's strengths become this app's numbers.
 *
 * AOSP ranks on a log scale of 0..255 for words and pairs alike, and a user
 * history dictionary keeps raw counts instead. This app keeps words on 1..10000
 * ([DictionaryLoader.scaleAospFrequency]) and pairs as downloaded packs count
 * them, where one sighting of a pair the user typed is worth [PERSONAL_USE]
 * corpus sightings (the reranker's own damping). Every reader goes through
 * here, so a pair from a `.combined` list, a `.dict` and a user history file
 * all land on one scale.
 */
object AospScores {

    /** One personal use, in corpus-count units; matches the reranker's damping. */
    const val PERSONAL_USE = 50

    /**
     * A pair's 0..255 probability as a pack count. The top of the scale is
     * worth about forty personal uses, the middle about one, and anything the
     * source keyboard itself rated rare still counts as a pair that exists.
     */
    fun pairCount(probability: Int): Int {
        val p = probability.coerceIn(0, MAX_PROBABILITY)
        val count = PERSONAL_USE * Math.pow(2.0, (p - PAIR_MIDPOINT) / PAIR_DOUBLING)
        return count.toInt().coerceAtLeast(1)
    }

    /** A pair typed [count] times, as a pack count. */
    fun historicalPairCount(count: Int): Int =
        (count.toLong() * PERSONAL_USE).coerceIn(1, MAX_HISTORICAL_PAIR_COUNT.toLong()).toInt()

    /**
     * A version 2 pair's four-bit strength as the 0..255 probability AOSP
     * would compute for it: sixteen and a half steps between the second word's
     * own probability and the top of the scale.
     */
    fun bigramProbability(unigram: Int, strength: Int): Int {
        val u = unigram.coerceIn(0, MAX_PROBABILITY)
        val step = (MAX_PROBABILITY - u) / (BIGRAM_STEPS + MAX_BIGRAM_STRENGTH)
        return (u + (strength.coerceIn(0, MAX_BIGRAM_STRENGTH) + 1) * step).toInt().coerceAtMost(MAX_PROBABILITY)
    }

    /**
     * A word typed [count] times out of [total], on this app's word scale.
     *
     * AOSP's own conversion for a history dictionary: the share of all typing,
     * floored at a minimum total so a nearly empty history does not rate its
     * first word as the most common in the language, then onto the log scale
     * and down by the unigram backoff.
     */
    fun historicalFrequency(count: Int, total: Int): Int {
        if (count <= 0) return 1
        val share = count.toDouble() / maxOf(total, MIN_TOTAL_COUNT)
        val log2 = Math.log(share) / Math.log(2.0)
        val probability = (MAX_PROBABILITY + log2 * ENCODING_SCALE + UNIGRAM_BACKOFF).toInt()
        return DictionaryLoader.scaleAospFrequency(probability.coerceIn(0, MAX_PROBABILITY))
    }

    /**
     * A word's 0..255 probability as the count a counted list would give it,
     * for an AOSP list downloaded in place of one.
     *
     * Not [DictionaryLoader.scaleAospFrequency]. That maps linearly onto the
     * bundled lists' 0..10000, which is right for a list imported beside them
     * but squeezes a whole downloaded list into about two nats once the
     * engine weighs it as `ln(1 + f)` against edit costs: every word nearly
     * as likely as every other, so one slip would trade a common word for a
     * rare one. The scale is logarithmic, [ENCODING_SCALE] steps to a
     * doubling, so undoing it gives back counts with a counted list's spread.
     * [LIST_TOP_LOG2] sets where they sit: en_US rates `the` 222, which lands
     * near 19 million, beside the counted English list's top words.
     */
    fun listCount(probability: Int): Int {
        val p = probability.coerceIn(0, MAX_PROBABILITY)
        val log2 = LIST_TOP_LOG2 + (p - MAX_PROBABILITY) / ENCODING_SCALE
        return Math.pow(2.0, log2).toInt().coerceAtLeast(1)
    }

    private const val MAX_PROBABILITY = 255
    private const val LIST_TOP_LOG2 = 28.0
    private const val MAX_BIGRAM_STRENGTH = 15
    private const val BIGRAM_STEPS = 1.5
    private const val PAIR_MIDPOINT = 128.0
    private const val PAIR_DOUBLING = 24.0
    private const val MAX_HISTORICAL_PAIR_COUNT = 1_000_000
    private const val MIN_TOTAL_COUNT = 8192
    private const val ENCODING_SCALE = 8.58923700372
    private const val UNIGRAM_BACKOFF = -32
}
